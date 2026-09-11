package com.pict.metatool.ui.report

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.data.job.JobProgressHub
import com.pict.metatool.data.job.JobReportExporter
import com.pict.metatool.data.job.JobReportStore
import com.pict.metatool.domain.job.JobReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 报告页状态（T5.7）。
 *
 * 与进度页同一个套路：优先用进程内广播（刚跑完那一份最新），其次盘上的存档。
 * [fromHub] 的作用和进度页一样——广播到了之后，盘上那份**不许**把新值盖回去。
 */
data class ReportUiState(
    val jobId: String,
    val report: JobReport? = null,
    val message: ReportMessage? = null,
    val fromHub: Boolean = false,
) {

    fun withReport(report: JobReport): ReportUiState = copy(report = report, fromHub = true)

    fun withStored(report: JobReport?): ReportUiState = if (fromHub) this else copy(report = report)

    fun withMessage(message: ReportMessage?): ReportUiState = copy(message = message)
}

/** 一次性提示；文案在 UI 层从 `strings.xml` 取（docs/06 §7）。 */
sealed interface ReportMessage {

    /** 导出成功（写完读回来比对一致才算）。 */
    data object Exported : ReportMessage

    /** 导出失败：目标位置写不进去、或写完读回来对不上。 */
    data object ExportFailed : ReportMessage
}

/**
 * 报告页 ViewModel（T5.7）。
 *
 * 只做两件事：把报告取出来（广播优先、盘其次）、把导出落到用户挑的位置。
 * 导出走 [JobReportExporter]，**写完读回校验**——报告导出的全部意义就是事后能拿出来看。
 */
class ReportViewModel(
    private val context: Context,
    val jobId: String,
) : ViewModel() {

    private val store = JobReportStore(context)
    private val exporter = JobReportExporter(context.contentResolver)

    private val _state = MutableStateFlow(ReportUiState(jobId = jobId))
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        JobProgressHub.liveOf(jobId)?.let { live -> _state.update { it.withReport(live.report) } }
        viewModelScope.launch {
            JobProgressHub.state.collect { live ->
                if (live != null && live.snapshot.jobId == jobId) {
                    _state.update { it.withReport(live.report) }
                }
            }
        }
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { store.load(jobId) }
            _state.update { it.withStored(stored) }
        }
    }

    fun exportCsv(target: Uri) = export { report -> exporter.writeCsv(target, report) }

    fun exportJson(target: Uri) = export { report -> exporter.writeJson(target, report) }

    fun consumeMessage() {
        _state.update { it.withMessage(null) }
    }

    private fun export(write: (JobReport) -> Boolean) {
        val report = _state.value.report ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { write(report) }
            _state.update {
                it.withMessage(if (ok) ReportMessage.Exported else ReportMessage.ExportFailed)
            }
        }
    }

    companion object {

        fun factory(context: Context, jobId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportViewModel(context.applicationContext, jobId) }
        }
    }
}
