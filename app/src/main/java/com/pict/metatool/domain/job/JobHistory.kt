package com.pict.metatool.domain.job

/**
 * 任务历史（docs/07 T5.8、docs/06 §3.8、docs/02 §2）。
 *
 * 这一层不碰盘、不碰 Android：历史条目由 data 层从三处已落盘的东西装配起来
 * （快照 = 计数与终态、报告 = 逐项明细、定义 = 还能不能再跑一遍），
 * 这里只回答「怎么分组排序」和「这一条能不能重跑」。
 *
 * 撤销的口径在 [JobUndoRules] / [JobUndo]，同一个文件里放着，因为两者共用
 * 「哪一条算最近」这条规则——拆开会变成两处各自解释 FR-34。
 */

/**
 * 一条任务能不能撤销（docs/01 FR-34）。
 *
 * 枚举而不是布尔：历史行上要说明**为什么**撤不了（「备份已被清理」和
 * 「这不是最近一次」对用户是两件事），只有 [AVAILABLE] 才放按钮。
 */
enum class UndoState(val canUndo: Boolean) {

    /** 最近一次任务，备份在、没过期：可以一键回到执行前。 */
    AVAILABLE(true),

    /** 已经撤过了。备份是一次性的：恢复回去之后再按一次，就是把「撤销后的样子」再改回去。 */
    UNDONE(false),

    /** 这次留了备份，但后面又跑过别的任务——FR-34 只支持撤销最近一次。 */
    NOT_LATEST(false),

    /** 跑的时候没留备份（没接备份之前的任务、没有目录授权、或已被清理）。 */
    NO_BACKUP(false),

    /** 备份还在，但已经过了保留期（7 天）。 */
    EXPIRED(false),

    /** 试运行：一个字节都没改过，没什么可撤。 */
    DRY_RUN(false),
}

/**
 * 历史列表里的一行。
 *
 * 字段全部来自快照与报告，**不含 URI**：这一层是「账本」，谁要地址谁去报告里取
 * （[JobUndo] 就是这么干的）。
 */
data class JobHistoryEntry(
    val jobId: String,
    val label: String,
    val status: JobStatus,
    val createdAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val total: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val verifyFailed: Int = 0,
    val skipped: Int = 0,
    val unsupported: Int = 0,
    val changedKeysTotal: Int = 0,
    val dryRun: Boolean = false,

    /** 定义文件还在盘上：能照着再跑一遍（重跑的定义见 [JobHistory.canRerun] 的注释）。 */
    val hasSpec: Boolean = false,

    val undoState: UndoState = UndoState.NO_BACKUP,

    /** 这次任务的备份目录名（`20260912_101530`）；没备份时为 null。 */
    val backupFolder: String? = null,

    /** 备份目录里的副本数（撤销会恢复这么多张）。 */
    val backupFiles: Int = 0,

    /** 撤销的时刻（报告里记的）；没撤过就是 null。 */
    val undoneAtMillis: Long? = null,
) {

    /** 还在队列里或正在跑（快照的 PENDING/RUNNING）。 */
    val isRunning: Boolean
        get() = status == JobStatus.PENDING || status == JobStatus.RUNNING

    val isFinished: Boolean get() = !isRunning

    /**
     * 能不能重跑：**定义还在**就行。
     *
     * 刻意不看「原来那批图还在不在」——文件可能被用户挪走、改权限，
     * 那是重跑那一刻才知道的事（重跑任务自己会逐项记 FAILED/跳过），
     * 在这里先拦下来只会让「图还在、只是刚才没跑成」的用户白等一场。
     */
    val canRerun: Boolean get() = hasSpec

    val canUndo: Boolean get() = undoState.canUndo

    /** 已经落定的项数（成功 / 失败 / 校验未通过 / 跳过 / 不支持）。 */
    val settled: Int
        get() = succeeded + failed + verifyFailed + skipped + unsupported

    /** 需要人看一眼的项（失败 + 校验未通过）。 */
    val troubled: Int get() = failed + verifyFailed

    /** 排序用的时间：优先结束时间，没有就退回创建时间。 */
    val orderMillis: Long get() = finishedAtMillis ?: createdAtMillis
}

/**
 * 任务页要画的两个分组。
 *
 * 排序：进行中按**排队时间**倒序（最近排的最上面）；历史按**结束时间**倒序，
 * 结束时间缺失（被系统掐掉、没写终态）的退回创建时间——否则那些行会全部沉底，
 * 而它们恰恰是最需要被看见的。
 */
data class JobHistory(
    val running: List<JobHistoryEntry>,
    val finished: List<JobHistoryEntry>,
) {

    val isEmpty: Boolean get() = running.isEmpty() && finished.isEmpty()

    val size: Int get() = running.size + finished.size

    companion object {

        /**
         * 历史分组最多显示这么多条。
         *
         * 20 是「一屏翻两下能到底」的量；不做分页（docs/06 §3.8 没有这一层），
         * 也不删盘上的旧记录——列表短了不代表账要撕掉。
         */
        const val FINISHED_LIMIT = 20

        fun from(entries: List<JobHistoryEntry>, finishedLimit: Int = FINISHED_LIMIT): JobHistory =
            JobHistory(
                running = entries.filter { it.isRunning }
                    .sortedByDescending { it.createdAtMillis },
                finished = entries.filterNot { it.isRunning }
                    .sortedWith(
                        compareByDescending<JobHistoryEntry> { it.orderMillis }
                            .thenByDescending { it.createdAtMillis },
                    )
                    .take(finishedLimit),
            )
    }
}
