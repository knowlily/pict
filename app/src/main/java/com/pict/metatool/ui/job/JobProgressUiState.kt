package com.pict.metatool.ui.job

import androidx.work.WorkInfo
import com.pict.metatool.data.job.JobLiveState
import com.pict.metatool.data.job.JobSnapshot
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobReport
import com.pict.metatool.domain.job.JobStatus

/**
 * 进度页状态（T5.6，docs/01 FR-29）。
 *
 * 一份状态有三个来源，优先级是**新鲜度**：
 * 1. 进程内广播（[JobLiveState]）：任务正在跑，或者刚收尾——最准，也最及时；
 * 2. 盘上的快照与报告：进程被杀之后重进这一页，只剩它俩；
 * 3. WorkManager 的队列状态：用来回答「它还在不在跑」，比盘上的快照早一步
 *    （快照落盘是节流的，队列状态不是）。
 *
 * 迁移函数都是纯函数（不碰 Android），因此可以直接单测——包括「广播到了之后
 * 盘上那份不许把新值盖回去」这条容易写错的地方。
 */
data class JobProgressUiState(
    val jobId: String,
    val snapshot: JobSnapshot? = null,
    val report: JobReport? = null,
    /** 预估剩余毫秒；样本不足或任务已结束时为 null（FR-29：不猜）。 */
    val remainingMillis: Long? = null,
    /** 这一份是不是来自进程内广播（在跑/刚跑完）。 */
    val live: Boolean = false,
    val workState: WorkInfo.State? = null,
    /** 已经收到过广播，盘上那份就不该再覆盖它。 */
    val fromHub: Boolean = false,
) {

    val label: String get() = snapshot?.label ?: report?.label.orEmpty()

    val dryRun: Boolean get() = snapshot?.dryRun ?: report?.dryRun ?: false

    val total: Int get() = snapshot?.total ?: report?.total ?: 0

    /** 已落地（终态）的项数：有快照用快照，否则从报告的逐项状态里数。 */
    val finished: Int
        get() = snapshot?.finished
            ?: report?.items?.count { it.status.isTerminal }
            ?: 0

    val percent: Int
        get() = snapshot?.percent
            ?: when {
                total <= 0 -> if (isTerminal) 100 else 0
                else -> (finished.toLong() * 100L / total.toLong()).toInt().coerceIn(0, 100)
            }

    /** 正在处理的那一张：广播/快照里有就用它，没有就从报告里找一个还在跑的。 */
    val currentName: String?
        get() = snapshot?.currentName
            ?: report?.items?.firstOrNull { it.status == JobItemStatus.RUNNING }?.name

    val status: JobStatus? get() = snapshot?.status

    val isTerminal: Boolean get() = snapshot?.isTerminal == true || workState?.isFinished == true

    /** 还没开工（队列里有、worker 还没接手）。 */
    val isWaiting: Boolean
        get() = !isTerminal && !live && workState == WorkInfo.State.ENQUEUED

    /** 能不能按「取消」：还没结束、而且确实有在跑或排队的活。 */
    val canCancel: Boolean
        get() = !isTerminal && (live || workState == WorkInfo.State.RUNNING || workState == WorkInfo.State.ENQUEUED)

    /** 逐项明细在不在（报告页要它，进度页的列表也画它）。 */
    val hasReport: Boolean get() = report != null

    fun withLive(liveState: JobLiveState): JobProgressUiState = copy(
        snapshot = liveState.snapshot,
        report = liveState.report,
        remainingMillis = liveState.remainingMillis,
        live = liveState.isLive,
        fromHub = true,
    )

    /**
     * 盘上读回来的那份。
     *
     * 已经收到过广播就不动它：磁盘上的快照是节流写入的，**天然比广播旧**，
     * 谁后到就用谁会让进度条往回跳一下。
     */
    fun withStored(snapshot: JobSnapshot?, report: JobReport?): JobProgressUiState =
        if (fromHub) this else copy(snapshot = snapshot, report = report)

    fun withWorkState(state: WorkInfo.State?): JobProgressUiState = copy(workState = state)
}
