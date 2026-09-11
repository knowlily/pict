package com.pict.metatool.data.job

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pict.metatool.core.result.getOrElse
import com.pict.metatool.data.preset.AssetPresetCatalog
import com.pict.metatool.domain.job.JobCancellation
import com.pict.metatool.domain.job.JobRunner
import com.pict.metatool.domain.job.JobStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WorkManager 里的批量任务（docs/07 T5.3）。
 *
 * 这一层只做三件事，其它全在别处：**把定义读回来**（[JobSpecStore]）、
 * **把 `JobRunner` 跑起来并盯着取消**、**把进度画到通知栏**（[JobNotifier]）。
 * 真正的读/写/校验在 [BatchItemWorker]，并发与重试在 `domain/job`——
 * worker 里塞不下业务逻辑，因为它随时可能被系统重建。
 *
 * 几个必须讲清楚的取舍：
 *
 * - **入参只带 jobId**：`Data` 有大小上限（约 10KB），几百个 URI 塞不进去；
 *   定义在盘上，worker 复活的第一个动作是把它读回来。
 * - **取消靠 `isStopped` 轮询**：WorkManager 叫停 worker 时不会打断正在跑的协程，
 *   所以要自己把「被叫停」翻译成 [JobCancellation]（FR-29）。正在写的那一项会把
 *   手上这个文件写完再记 SKIPPED，不做半截文件。
 * - **收尾不重试**：被系统掐断（既没完成也没被取消）时返回 `failure` 并把
 *   结果写进 `outputData`，**不**返回 `retry`。今天重跑等于从头再来一遍，
 *   而「随机填充」再跑一次会得到另一批值——宁可如实报「被中断」。
 *   断点续跑是 T5.9 的活，接上之后再让重试有意义。
 * - **前台服务通知可能被拒**（Android 12+ 后台起前台服务受限）：拒了就照常跑，
 *   只是没有常驻通知；这一点会记进结果，不静默吞掉。
 */
class JobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private var foregroundRejected = false

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(JobQueue.KEY_JOB_ID)
            ?: return fail("入参里没有任务 id")
        val spec = JobSpecStore(applicationContext).load(jobId)
            ?: return fail("任务定义读不出来：$jobId（可能已被清理）")
        val catalog = AssetPresetCatalog(applicationContext.assets)
        val plan = spec.toPlan(catalog).getOrElse { failure ->
            return fail("计划拼不出来：${failure.detail ?: failure.error.code}")
        }

        val job = spec.toJob()
        val notifier = JobNotifier(applicationContext)
        val snapshots = JobSnapshotStore(applicationContext)
        notifier.ensureChannel()

        val cancellation = JobCancellation()
        val snapshotsPosted = postForeground(notifier, spec.label, JobSnapshot.of(job, now()))
        if (!snapshotsPosted) {
            // 通知发不出去也得继续：用户就在界面上等着，任务不该因为一条通知而拒跑
            snapshots.save(JobSnapshot.of(job, now()))
        }

        val runner = JobRunner(
            worker = BatchItemWorker(
                resolver = applicationContext.contentResolver,
                plan = plan,
                indices = spec.indices,
                writableByIds = spec.writableByIds,
                catalog = catalog,
            ),
        )

        var last = job
        var previous: JobSnapshot? = null
        var lastPostedAtMillis = 0L

        try {
            coroutineScope {
                val watchdog = launch {
                    while (isActive) {
                        if (isStopped) cancellation.cancel()
                        delay(WATCH_INTERVAL_MILLIS)
                    }
                }
                try {
                    runner.run(job, cancellation).collect { snapshotJob ->
                        last = snapshotJob
                        val moment = now()
                        val snapshot = JobSnapshot.of(snapshotJob, moment)
                        // 快照落盘不节流（进度页读的是盘），通知更新才节流（系统限频）
                        if (JobProgressThrottle.shouldPost(previous, snapshot, lastPostedAtMillis, moment)) {
                            snapshots.save(snapshot)
                            postForeground(notifier, spec.label, snapshot)
                            previous = snapshot
                            lastPostedAtMillis = moment
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            // 收尾这版要走 endOf：被叫停时域层那张 CANCELED 发不出来（看门狗抢不过 WorkManager
            // 的协程取消），不补这一刀，记录就永远停在 RUNNING、未开工项永远挂着（FR-29）
            val ended = JobSnapshot.endOf(last, stopped = isStopped, nowMillis = now())
            // 阻塞 IO：协程被取消也写得进去，别让「被掐断」这件事连记录都没有
            snapshots.save(ended)
            // 取消后就别再碰前台服务了（挂起的调用在取消状态下会立刻抛）
            if (currentCoroutineContext().isActive) postForeground(notifier, spec.label, ended)
        }

        val detail = buildString {
            append("任务 ${last.id}")
            if (foregroundRejected) append("（前台通知被系统拒绝）")
        }
        return when (last.status) {
            JobStatus.COMPLETED -> Result.success(
                workDataOf(
                    JobQueue.KEY_OUTCOME to JobQueue.OUTCOME_COMPLETED,
                    JobQueue.KEY_JOB_ID to last.id,
                ),
            )
            JobStatus.CANCELED -> Result.success(
                workDataOf(
                    JobQueue.KEY_OUTCOME to JobQueue.OUTCOME_CANCELED,
                    JobQueue.KEY_JOB_ID to last.id,
                ),
            )
            // 没完成也没被取消：只能是被系统掐了。如实记账，不偷偷再跑一遍
            else -> Result.failure(
                workDataOf(
                    JobQueue.KEY_OUTCOME to JobQueue.OUTCOME_INTERRUPTED,
                    JobQueue.KEY_JOB_ID to last.id,
                    ERROR_KEY to detail,
                ),
            )
        }
    }

    // 这版 WorkManager 里 `onStopped` 是 final，不能重写。叫停于是走两条路：
    // ① 用户按取消 → JobCancellation → JobRunner 把最后一张收尾成 CANCELED，正常返回；
    // ② 系统回收（低内存 / 强停）→ 协程被取消 → 异常传播出去，盘上的快照停在最后一个已完成项。
    // 断点续跑属于 T5.9，接上之前这里不假装能续。

    private suspend fun postForeground(
        notifier: JobNotifier,
        label: String,
        snapshot: JobSnapshot,
    ): Boolean = runCatching {
        setForeground(notifier.foreground(label, snapshot))
    }.fold(
        onSuccess = { true },
        onFailure = {
            foregroundRejected = true
            false
        },
    )

    private fun fail(detail: String) = Result.failure(
        workDataOf(
            JobQueue.KEY_OUTCOME to JobQueue.OUTCOME_UNSTARTED,
            ERROR_KEY to detail,
        ),
    )

    private fun now(): Long = System.currentTimeMillis()

    companion object {

        /** 取消轮询间隔：够快（用户按取消几乎立刻生效），又不至于占着 CPU 空转。 */
        const val WATCH_INTERVAL_MILLIS = 200L

        private const val ERROR_KEY = "error"
    }
}
