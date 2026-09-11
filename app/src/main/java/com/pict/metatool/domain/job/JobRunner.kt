package com.pict.metatool.domain.job

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.TagKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单项处理的结果，由执行侧（`data` 层，真正读/写文件的那一段）回报给任务层。
 *
 * 域层不碰文件系统：谁去读 URI、谁去落盘、谁去校验，都在 app/data 层实现
 * [JobItemWorker]，任务层只负责并发、重试、记账（docs/07 T5.2）。
 */
sealed interface ItemResult {

    /**
     * 成功；[changedKeys] 只放真正变了值的键（dry-run 时是「将会变」的键）。
     *
     * [note] 给「成了但有话要说」用（眼下只有一种：写入器事前声明丢过键），
     * 正常成功是 null——不要拿它当成功提示的抽屉。
     */
    data class Done(
        val changedKeys: Set<TagKey> = emptySet(),
        val outputUri: String? = null,
        val note: String? = null,
    ) : ItemResult

    /** 失败；[error] 决定要不要重试（见 [isRetryable]）。 */
    data class Failed(
        val error: PictError,
        val detail: String? = null,
    ) : ItemResult

    /**
     * 写后校验未通过（FR-33）：不算失败也不算成功，单独一档。
     *
     * 带上 [changedKeys]：**值确实写进去了**，只是没通过读回比对，计数不该显示成 0
     * （那会让人以为一个字段都没动）。
     */
    data class VerifyFailed(
        val error: PictError = PictError.META_VERIFY,
        val detail: String? = null,
        val changedKeys: Set<TagKey> = emptySet(),
    ) : ItemResult

    /** 前置条件不满足（格式不支持 / 无写权限），跳过但不报错。 */
    data class Unsupported(val note: String) : ItemResult

    /** 主动跳过（用户取消或按规则跳过）。 */
    data class Skipped(val note: String? = null) : ItemResult
}

/** 干单项活儿的函数：读源 → 计划 → 落盘 → 校验（FR-31/32/33 都在实现里体现）。 */
fun interface JobItemWorker {
    suspend fun run(item: JobItem, options: JobOptions): ItemResult
}

/**
 * 取消信号（FR-29）。
 *
 * 独立于协程取消：任务被 UI / WorkManager 叫停时，我们希望**未开工项干净地记 SKIPPED**，
 * 而不是把收集协程连根拔起。执行侧可以拿到同一个对象，在安全点（写完一个文件之后）自查。
 */
class JobCancellation {

    private val flag = AtomicBoolean(false)

    val isCanceled: Boolean get() = flag.get()

    fun cancel() {
        flag.set(true)
    }
}

/**
 * 任务执行器（docs/07 T5.2）：并发跑项、按 FR-30 重试、按 FR-29 取消、把每次状态变化
 * 做成 [Flow] 快照推给 UI 与通知。
 *
 * 设计取舍：
 * - **依赖倒置**：真正的读/写/校验由外部注入的 [JobItemWorker] 完成，所以这一层纯 Kotlin、
 *   可在 JVM 单测里跑满（并发上限、重试次数、取消语义都不需要设备）。
 * - **时钟与等待可注入**：测试里不用真等 500 ms / 2 s，也不会因为机器快慢而抖。
 * - **冷流**：`run()` 被收集才开始跑，重复收集 = 重复执行（历史重跑 T5.8 自己建新任务）。
 */
class JobRunner(
    private val worker: JobItemWorker,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: (attempt: Int) -> Long = ::backoffMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * 跑完整个任务，边跑边发快照；最后一次发射一定是终态
     * （[JobStatus.COMPLETED] 或 [JobStatus.CANCELED]）。
     */
    fun run(job: Job, cancellation: JobCancellation = JobCancellation()): Flow<Job> = channelFlow {
        val state = MutableStateFlow(job.withOptions(job.options).start(clock()))

        // 状态一变就发一版；收尾时手动补发终态（那时 pump 已经停掉）。
        val pump = launch { state.collect { trySend(it) } }

        try {
            dispatch(state, cancellation)
        } finally {
            pump.cancel()
            state.update { if (cancellation.isCanceled) it.cancel(clock()) else it.settle(clock()) }
            trySend(state.value)
        }
    }

    /** 扇出：并发数由 [JobOptions.concurrency] 把关（FR-35）。 */
    private suspend fun ProducerScope<Job>.dispatch(
        state: MutableStateFlow<Job>,
        cancellation: JobCancellation,
    ) {
        coroutineScope {
            val gate = Semaphore(state.value.options.concurrency)
            state.value.items.forEach { item ->
                launch(dispatcher) { gate.withPermit { process(item.id, state, cancellation) } }
            }
        }
    }

    private suspend fun process(
        itemId: String,
        state: MutableStateFlow<Job>,
        cancellation: JobCancellation,
    ) {
        val queued = state.value.item(itemId) ?: return
        if (cancellation.isCanceled) {
            // 取消已经下达：这一项一步都不该走（`update` 会顺手把任务记成已取消）
            move(state, queued, JobItemStatus.SKIPPED, cancellation) {
                it.copy(status = JobItemStatus.SKIPPED, note = Job.CANCELED_NOTE, finishedAtMillis = clock())
            }
            return
        }

        var current = move(state, queued, JobItemStatus.RUNNING, cancellation) {
            it.copy(
                status = JobItemStatus.RUNNING,
                attempts = it.attempts + 1,
                startedAtMillis = it.startedAtMillis ?: clock(),
                error = null,
                detail = null,
                note = null,
            )
        }

        while (true) {
            val result = attempt(current, state.value.options)
            when (result) {
                is ItemResult.Done -> {
                    move(state, current, JobItemStatus.SUCCESS, cancellation) {
                        it.copy(
                            status = JobItemStatus.SUCCESS,
                            changedKeys = result.changedKeys,
                            outputUri = result.outputUri ?: it.outputUri,
                            note = result.note,
                            finishedAtMillis = clock(),
                        )
                    }
                    return
                }

                is ItemResult.VerifyFailed -> {
                    move(state, current, JobItemStatus.VERIFY_FAILED, cancellation) {
                        it.copy(
                            status = JobItemStatus.VERIFY_FAILED,
                            error = result.error,
                            detail = result.detail,
                            changedKeys = result.changedKeys,
                            finishedAtMillis = clock(),
                        )
                    }
                    return
                }

                is ItemResult.Unsupported -> {
                    move(state, current, JobItemStatus.UNSUPPORTED, cancellation) {
                        it.copy(
                            status = JobItemStatus.UNSUPPORTED,
                            note = result.note,
                            finishedAtMillis = clock(),
                        )
                    }
                    return
                }

                is ItemResult.Skipped -> {
                    move(state, current, JobItemStatus.SKIPPED, cancellation) {
                        it.copy(
                            status = JobItemStatus.SKIPPED,
                            note = result.note,
                            finishedAtMillis = clock(),
                        )
                    }
                    return
                }

                is ItemResult.Failed -> {
                    val attempts = current.attempts
                    val retryLeft = attempts <= state.value.options.maxRetries
                    if (!retryLeft || !result.error.isRetryable) {
                        // 不可重试或没额度了：当场记账，不再绕道 RETRYING（否则界面会
                        // 显示一个永远不会发生的「重试中」）
                        move(state, current, JobItemStatus.FAILED, cancellation) {
                            it.copy(
                                status = JobItemStatus.FAILED,
                                error = result.error,
                                detail = result.detail,
                                finishedAtMillis = clock(),
                            )
                        }
                        return
                    }

                    current = move(state, current, JobItemStatus.RETRYING, cancellation) {
                        it.copy(
                            status = JobItemStatus.RETRYING,
                            error = result.error,
                            detail = result.detail,
                            note = "第 $attempts 次失败，${result.error.code} 后重试",
                        )
                    }
                    sleep(backoff(attempts))
                    if (cancellation.isCanceled) {
                        move(state, current, JobItemStatus.SKIPPED, cancellation) {
                            it.copy(
                                status = JobItemStatus.SKIPPED,
                                note = Job.CANCELED_NOTE,
                                finishedAtMillis = clock(),
                            )
                        }
                        return
                    }
                    current = move(state, current, JobItemStatus.RUNNING, cancellation) {
                        it.copy(
                            status = JobItemStatus.RUNNING,
                            attempts = it.attempts + 1,
                            error = null,
                            detail = null,
                            note = null,
                        )
                    }
                }
            }
        }
    }

    /**
     * 跑一次 worker；异常不炸整个任务，按 UNKNOWN 记账（FR-30：其余项继续）。
     * 协程取消（用户离开/进程收尾）照旧往上抛，不吞。
     */
    private suspend fun attempt(item: JobItem, options: JobOptions): ItemResult =
        try {
            worker.run(item, options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            ItemResult.Failed(PictError.UNKNOWN, throwable.message ?: throwable::class.java.simpleName)
        }

    /**
     * 落地一次状态迁移。
     *
     * 迁移被拒（项已经到终态，通常是取消抢先标记过了）就当没发生——不抛异常，
     * 因为并发下这是正常竞态，不是编程错误。
     */
    private fun move(
        state: MutableStateFlow<Job>,
        item: JobItem,
        next: JobItemStatus,
        cancellation: JobCancellation,
        transform: (JobItem) -> JobItem,
    ): JobItem {
        var moved = item
        state.update { job ->
            val from = job.item(item.id) ?: item
            if (!from.status.canMoveTo(next)) {
                return@update job
            }
            moved = transform(from).copy(id = from.id, uri = from.uri, source = from.source)
            job.update(moved, clock(), cancelRequested = cancellation.isCanceled)
        }
        return moved
    }

    companion object {

        /** FR-30：500 ms / 2 s 指数退避（第 1 次重试等 500 ms，之后 2 s）。 */
        fun backoffMillis(attempt: Int): Long = if (attempt <= 1) 500L else 2_000L

        /**
         * 只重试「可能是瞬时」的失败。
         *
         * 不可写（E-STORAGE-READONLY）、格式不支持、字段非法、备份缺失、内存不足、用户取消
         * 都是确定性结论：再等 2 秒重来一遍只会重复伤害或白等。I/O 与未分类错误才值得再来。
         */
        val RETRYABLE_ERRORS: Set<PictError> = setOf(
            PictError.IO_OPEN,
            PictError.IO_READ,
            PictError.IO_WRITE,
            PictError.UNKNOWN,
        )
    }
}

/** 这个错误值不值得再试一次（策略与理由见 [JobRunner.RETRYABLE_ERRORS]）。 */
val PictError.isRetryable: Boolean
    get() = this in JobRunner.RETRYABLE_ERRORS
