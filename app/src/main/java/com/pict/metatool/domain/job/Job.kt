package com.pict.metatool.domain.job

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey

/**
 * 任务项状态（docs/01 §5 状态机、docs/07 T5.1）。
 *
 * ```
 * PENDING ──开始──> RUNNING ──成功──> SUCCESS
 *    │                 │  │
 *    │                 │  └──可重试失败──> RETRYING ──(重试次数 < 上限)──> RUNNING
 *    │                 │                        └──(重试耗尽)──> FAILED
 *    │                 └──校验失败──> VERIFY_FAILED
 *    ├──用户取消/跳过──> SKIPPED
 *    └──前置条件不满足──> UNSUPPORTED
 * ```
 *
 * 命名留痕：docs/07 T5.1 的行里还写了 `CANCELED`。取消在**任务级**（[JobStatus.CANCELED]），
 * 项级按 docs/01 §5 记 [SKIPPED]（「用户取消/跳过」）——两层不是一个东西，别混用。
 */
enum class JobItemStatus(val label: String) {
    PENDING("等待"),
    RUNNING("处理中"),
    RETRYING("重试中"),
    SUCCESS("成功"),
    FAILED("失败"),
    VERIFY_FAILED("校验未通过"),
    SKIPPED("已跳过"),
    UNSUPPORTED("不支持"),
    ;

    /** 终态（docs/01 §5）：SUCCESS / FAILED / VERIFY_FAILED / SKIPPED / UNSUPPORTED。 */
    val isTerminal: Boolean
        get() = when (this) {
            PENDING, RUNNING, RETRYING -> false
            SUCCESS, FAILED, VERIFY_FAILED, SKIPPED, UNSUPPORTED -> true
        }

    /** 是否算「这一项干成了」；校验未通过不算（FR-33 单独一档）。 */
    val isSuccess: Boolean get() = this == SUCCESS

    /**
     * 状态机是否允许这一步迁移。
     *
     * RUNNING → SKIPPED 留给「取消时在安全点中止」（FR-29）：正在处理的那一项不硬砍，
     * 它自己认输并记账。
     *
     * 两条 docs/01 §5 的图里没画的边，都是被真实路径逼出来的（留痕在 docs/09）：
     * - RUNNING → FAILED：不可重试的失败（如 E-STORAGE-READONLY）必须能当场记账。绕道
     *   RETRYING 会让界面凭空显示「重试中」，而它根本不会再试。
     * - RUNNING → UNSUPPORTED：「这个容器支不支持」得把文件打开才知道，等 Worker 回报时
     *   项已经在跑了；不放行就会卡在 RUNNING 永远不收尾。
     */
    fun canMoveTo(next: JobItemStatus): Boolean = when (this) {
        PENDING -> next == RUNNING || next == SKIPPED || next == UNSUPPORTED
        RUNNING -> next == SUCCESS || next == RETRYING || next == FAILED ||
            next == VERIFY_FAILED || next == SKIPPED || next == UNSUPPORTED
        RETRYING -> next == RUNNING || next == FAILED || next == SKIPPED
        SUCCESS, FAILED, VERIFY_FAILED, SKIPPED, UNSUPPORTED -> false
    }
}

/** 任务级状态。CANCELED / COMPLETED 为终态。 */
enum class JobStatus(val label: String) {
    PENDING("等待中"),
    RUNNING("进行中"),
    CANCELED("已取消"),
    COMPLETED("已完成"),

    /**
     * 跑着跑着进程没了（被系统回收、强杀、崩溃），终于没能收尾。
     *
     * 与 [CANCELED] 分开记：那是「有人按了停」，这是「没人按，它自己断了」——历史行
     * 说「已取消」会把责任扣到用户头上。域层自己不产生它（被叫停一律走 [CANCELED]），
     * 由数据层拿队列那头的说法对账时写入（docs/08 §3「处理中杀进程 → 重启」）。
     */
    INTERRUPTED("已中断"),
    ;

    val isTerminal: Boolean get() = this == CANCELED || this == COMPLETED || this == INTERRUPTED
}

/**
 * 批处理选项（docs/07 T5.1；取值口径见 docs/01 FR-30、FR-35）。
 *
 * 越界值一律**夹回**合法区间而不是抛异常：这些值来自设置页与历史任务，
 * 用户改小并发数或旧配置少了一个字段，都不该让整个任务起不来。
 */
data class JobOptions(
    /** 并发数，1–8，默认 = CPU 核心数 / 2（FR-35）。 */
    val concurrency: Int = defaultConcurrency(),
    /** 每项重试次数上限，0–2（FR-30：最多重试 2 次）。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** 只预览不落盘（FR-32）：任何文件与临时文件都不得被创建或修改。 */
    val dryRun: Boolean = false,
) {

    fun normalized(): JobOptions = copy(
        concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY),
        maxRetries = maxRetries.coerceIn(0, DEFAULT_MAX_RETRIES),
    )

    companion object {
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 8

        /** FR-30：每项最多重试 2 次（500 ms / 2 s 指数退避）。 */
        const val DEFAULT_MAX_RETRIES = 2

        /** 默认并发 = CPU 核心数 / 2，夹在 1–8（FR-35）。 */
        fun defaultConcurrency(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
    }
}

/**
 * 一个任务项（= 一张图；docs/07 T5.1）。
 *
 * 域层不碰 Android：源只以 [uri] 字符串记名，真实 `Uri` 解析留给 data 层。
 * 每项按 docs/01 §5 记账：开始/结束时间、耗时、变更字段、错误码。
 */
data class JobItem(
    /** 稳定标识，报告与重跑都按它对齐；同一任务内唯一。 */
    val id: String,
    /** 源文件的不透明标识（`content://...`），域层只当字符串用。 */
    val uri: String,
    /** 展示与路由信息（文件名、MIME、大小、格式提示）。 */
    val source: SourceInfo,
    val status: JobItemStatus = JobItemStatus.PENDING,
    /** 已开工次数（含首次）；0 = 还没动过。 */
    val attempts: Int = 0,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    /** 与源文件相比真正变化的键（dry-run 时就是「将会变」的键）。 */
    val changedKeys: Set<TagKey> = emptySet(),
    val error: PictError? = null,
    val detail: String? = null,
    /** 跳过/不支持的原因，给用户看的短句。 */
    val note: String? = null,
    /** 输出落点（导出副本用；FR-31 撤销也靠它）。 */
    val outputUri: String? = null,
    /** 覆写前留下的备份副本地址（FR-34 撤销靠它；没留备份时为 null）。 */
    val backupUri: String? = null,
    /** 备份所在的时间戳目录名（`20260912_101530`），撤销与过期判定都用它。 */
    val backupFolder: String? = null,
) {

    val isTerminal: Boolean get() = status.isTerminal

    /** 走了几个字段（FR-31 报告的「变更字段数」）。 */
    val changedCount: Int get() = changedKeys.size

    /** 处理耗时；未完成时为 null，报告里显示「—」。 */
    val durationMillis: Long?
        get() {
            val from = startedAtMillis ?: return null
            val to = finishedAtMillis ?: return null
            return (to - from).coerceAtLeast(0)
        }
}

/** 进度快照（FR-29：总进度 + 当前项 + 已完成/总数 + 预估剩余）。 */
data class JobProgress(
    val total: Int,
    val finished: Int,
    val running: Int,
    val succeeded: Int,
    val failed: Int,
    val verifyFailed: Int,
    val skipped: Int,
    val unsupported: Int,
    /** 正在处理（含重试中）的文件名；没有则 null。 */
    val currentName: String?,
    val elapsedMillis: Long,
    /** 预估剩余毫秒；样本不足（还没完成任何一项、都没有耗时）时为 null。 */
    val remainingMillis: Long?,
) {

    val fraction: Float get() = if (total <= 0) 1f else finished.toFloat() / total

    val isDone: Boolean get() = finished >= total
}

/**
 * 一次批处理任务（docs/07 T5.1）。
 *
 * 不可变：每次状态变化都产出新快照，UI 与通知（T5.6）直接比对快照即可。
 * 所有「现在几点」都从外部传入（[nowMillis]），因此状态机本身可单测、不依赖系统时钟。
 */
data class Job(
    val id: String,
    /** 人类可读的任务描述（「按「iPhone 15」预设填充 · 32 张」），报告与历史列表用。 */
    val label: String,
    val createdAtMillis: Long,
    val items: List<JobItem>,
    val options: JobOptions = JobOptions(),
    val status: JobStatus = JobStatus.PENDING,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
) {

    init {
        require(items.map { it.id }.toSet().size == items.size) {
            "任务项 id 必须唯一：报告与重跑都按 id 对账"
        }
    }

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    /** 取消是任务级状态；项级只记 SKIPPED。 */
    val isCanceled: Boolean get() = status == JobStatus.CANCELED

    val isTerminal: Boolean get() = status.isTerminal

    fun item(id: String): JobItem? = items.firstOrNull { it.id == id }

    fun count(status: JobItemStatus): Int = items.count { it.status == status }

    /** 开工：PENDING → RUNNING。重复调用无副作用。 */
    fun start(nowMillis: Long): Job = when (status) {
        JobStatus.PENDING -> copy(status = JobStatus.RUNNING, startedAtMillis = nowMillis)
        else -> this
    }

    fun withOptions(options: JobOptions): Job = copy(options = options.normalized())

    /**
     * 用户取消（FR-29）：立刻停掉未开工项——它们记 SKIPPED（docs/01 §5「用户取消/跳过」）。
     * 正在处理（RUNNING / RETRYING）的项不在这里改：它们由执行侧在安全点自行收尾，
     * 免得出现「状态说跳过、文件其实正在写」的幻象。
     */
    fun cancel(nowMillis: Long): Job {
        if (isTerminal) return this
        val stopped = items.map { item ->
            if (item.status == JobItemStatus.PENDING) {
                item.copy(
                    status = JobItemStatus.SKIPPED,
                    note = CANCELED_NOTE,
                    finishedAtMillis = nowMillis,
                )
            } else {
                item
            }
        }
        return copy(
            items = stopped,
            status = JobStatus.CANCELED,
            finishedAtMillis = nowMillis,
        )
    }

    /** 所有项都到终态（且没被取消）→ 任务完成。 */
    fun settle(nowMillis: Long): Job = when {
        isCanceled -> this
        items.all { it.isTerminal } -> copy(status = JobStatus.COMPLETED, finishedAtMillis = nowMillis)
        else -> this
    }

    /**
     * 用新的一项替换旧的一项，并顺带推进任务状态。
     *
     * [cancelRequested] 由执行侧在「取消已下达」时传 true：此时**不能**把任务记为已完成。
     * 不传就会撞上这个口子——最后一项收尾的那一刻，前面跑的加上后面跳的恰好都到了终态，
     * 于是「用户按了取消、报告却写已完成」。
     *
     * 非法迁移直接抛异常：这是执行侧的编程错误（比如从终态再改一次），
     * 静默吞掉会写出自相矛盾的报告——宁可当场响。
     */
    fun update(item: JobItem, nowMillis: Long, cancelRequested: Boolean = false): Job {
        val index = items.indexOfFirst { it.id == item.id }
        require(index >= 0) { "任务里没有这一项：${item.id}" }
        val previous = items[index]
        require(previous.status.canMoveTo(item.status)) {
            "非法状态迁移：${previous.status} → ${item.status}（${item.id}，docs/01 §5）"
        }
        val updated = items.toMutableList().also { it[index] = item }
        val next = copy(items = updated)
        return if (cancelRequested) next.cancel(nowMillis) else next.settle(nowMillis)
    }

    fun progress(nowMillis: Long): JobProgress {
        val finished = items.filter { it.isTerminal }
        val durations = finished.mapNotNull { it.durationMillis }
        val average = if (durations.isEmpty()) null else durations.average().toLong()
        return JobProgress(
            total = size,
            finished = finished.size,
            running = count(JobItemStatus.RUNNING) + count(JobItemStatus.RETRYING),
            succeeded = count(JobItemStatus.SUCCESS),
            failed = count(JobItemStatus.FAILED),
            verifyFailed = count(JobItemStatus.VERIFY_FAILED),
            skipped = count(JobItemStatus.SKIPPED),
            unsupported = count(JobItemStatus.UNSUPPORTED),
            currentName = items
                .firstOrNull { it.status == JobItemStatus.RUNNING || it.status == JobItemStatus.RETRYING }
                ?.source?.displayName,
            elapsedMillis = (nowMillis - (startedAtMillis ?: createdAtMillis)).coerceAtLeast(0),
            remainingMillis = average?.let { it * (size - finished.size) },
        )
    }

    companion object {
        /** 取消时未开工项的记事（docs/01 §5：用户取消 → SKIPPED）。 */
        const val CANCELED_NOTE = "任务已取消"
    }
}
