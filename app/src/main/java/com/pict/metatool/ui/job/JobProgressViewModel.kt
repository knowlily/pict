package com.pict.metatool.ui.job

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.data.job.JobProgressHub
import com.pict.metatool.data.job.JobQueue
import com.pict.metatool.data.job.JobReportStore
import com.pict.metatool.data.job.JobSnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务进度页 ViewModel（T5.6）。
 *
 * 只做三件事：把三个来源（进程内广播 / 盘上快照与报告 / WorkManager 队列状态）
 * 汇成一份 UI 状态、把「取消」转给 [JobQueue]、在必要时重读盘。
 * 真正的执行在 worker 里，这里一行业务逻辑都没有。
 *
 * 「取消」不自己跑逻辑：它就是把取消意图交给 WorkManager，剩下的由 `JobRunner` 在
 * 安全点上收尾（正在写的那一项写完再记 SKIPPED，FR-29）。
 */
class JobProgressViewModel(
    private val context: Context,
    val jobId: String,
) : ViewModel() {

    private val reports = JobReportStore(context)
    private val snapshots = JobSnapshotStore(context)

    private val _state = MutableStateFlow(JobProgressUiState(jobId = jobId))
    val state: StateFlow<JobProgressUiState> = _state.asStateFlow()

    init {
        // 进页面先看一眼广播：任务可能在「点进来的那一刻」就在跑
        JobProgressHub.liveOf(jobId)?.let { live -> _state.update { it.withLive(live) } }

        viewModelScope.launch {
            JobProgressHub.state.collect { live ->
                if (live != null && live.snapshot.jobId == jobId) {
                    _state.update { it.withLive(live) }
                }
            }
        }
        viewModelScope.launch { loadFromDisk() }
        viewModelScope.launch {
            JobQueue.observe(context, jobId).collect { info ->
                _state.update { it.withWorkState(info?.state) }
            }
        }
    }

    /** 从盘上重读（下拉刷新、或从报告页回来时用）。 */
    fun refresh() {
        viewModelScope.launch { loadFromDisk() }
    }

    /** 用户按了取消（FR-29）。 */ 
    fun cancel() {
        JobQueue.cancel(context, jobId)
    }

    private suspend fun loadFromDisk() {
        val snapshot = withContext(Dispatchers.IO) { snapshots.load(jobId) }
        val report = withContext(Dispatchers.IO) { reports.load(jobId) }
        _state.update { it.withStored(snapshot, report) }
    }

    companion object {

        /** 手工装配（与其它页一致）：`applicationContext`，避免持有 Activity。 */
        fun factory(context: Context, jobId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { JobProgressViewModel(context.applicationContext, jobId) }
        }
    }
}
