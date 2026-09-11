package com.pict.metatool.data.job

import com.pict.metatool.domain.job.JobReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进度页看到的一份「正在跑的任务」（T5.6）。
 *
 * 三个来源各管一段：
 * - [snapshot]：计数与当前文件名——通知栏看的那份，也是「这一轮跑到哪」的唯一权威；
 * - [report]：**逐项状态**（列表里每张图一行）；快照里没有它，它是收尾时才落盘的那份明细；
 * - [remainingMillis]：FR-29 要的「预估剩余时间」，样本不足时为 null（不猜）。
 */
data class JobLiveState(
    val snapshot: JobSnapshot,
    val report: JobReport,
    val remainingMillis: Long?,
    /** 还在跑（true）还是刚刚收尾（false）。收尾那一次也要发，界面才不会停在最后一帧。 */
    val isLive: Boolean,
)

/**
 * 进程内的「当前任务」广播（T5.6）。
 *
 * 为什么需要它：`JobRunner` 的进度流只在 worker 那个协程里，界面拿不到；而快照落盘
 * 是节流的（`JobProgressThrottle`），拿盘上的快照画列表会一顿一顿。所以在同一个进程里
 * 直接广播——**进程被杀之后它自然为空**，那时界面回退到盘上的快照与报告
 * （`JobReportStore`），这正是「重启后还能看到上一轮跑到哪」的兜底。
 *
 * 单例 + `StateFlow`：写的是 worker（后台线程），读的是界面（主线程），
 * 值只有一份、读到的永远是最新的那一份。任务之间不会串——每个值都带着 jobId，
 * 界面自己按 jobId 过滤。
 */
object JobProgressHub {

    private val _state = MutableStateFlow<JobLiveState?>(null)

    val state: StateFlow<JobLiveState?> = _state.asStateFlow()

    fun publish(live: JobLiveState) {
        _state.value = live
    }

    /** 取某一个任务当前的广播；不是它（或没有）就给 null。 */
    fun liveOf(jobId: String): JobLiveState? = _state.value?.takeIf { it.snapshot.jobId == jobId }
}
