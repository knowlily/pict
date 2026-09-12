package com.pict.metatool.ui.jobs

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.R
import com.pict.metatool.core.result.getOrElse
import com.pict.metatool.core.result.map
import com.pict.metatool.data.job.JobHistoryStore
import com.pict.metatool.data.job.JobIds
import com.pict.metatool.data.job.JobProgressHub
import com.pict.metatool.data.job.JobQueue
import com.pict.metatool.data.job.JobSnapshotReconciler
import com.pict.metatool.data.job.JobSpecStore
import com.pict.metatool.data.job.JobUndoOutcome
import com.pict.metatool.data.job.JobUndoRunner
import com.pict.metatool.domain.job.JobHistory
import com.pict.metatool.domain.job.JobHistoryEntry
import com.pict.metatool.domain.job.JobUndoRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 一次性提示（撤销结果、重跑没排上队）。
 *
 * 只带资源 id 与参数，不带现成文案：文案归 strings.xml，ViewModel 里出现中文
 * 就等于把一句话复制了两份。`args` 允许是字符串——错误详情是运行时才知道的。
 */
data class HistoryMessage(
    @param:StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
) {

    /** 取现成文案：把 context 收进来，省得每个调用点自己拼一遍。 */
    fun resolve(context: Context): String = context.getString(textRes, *args.toTypedArray())
}

/**
 * 任务页（T5.8）的界面状态。
 *
 * 两组数据都在这里：进行中（快照的 PENDING/RUNNING）、历史（其余）。
 * 分组与排序是域层 [JobHistory] 定的，这里只管装和换。
 */
data class JobHistoryUiState(
    val loading: Boolean = true,
    val running: List<JobHistoryEntry> = emptyList(),
    val finished: List<JobHistoryEntry> = emptyList(),

    /** 正在等确认的那一条。撤销会改文件，不能按一下就动手。 */
    val confirming: JobHistoryEntry? = null,

    /** 正在撤的那一条的 id；null 表示没有在撤。 */
    val undoing: String? = null,

    /** 正在重排的那一条的 id。 */
    val rerunning: String? = null,

    val message: HistoryMessage? = null,
) {
    val isEmpty: Boolean get() = running.isEmpty() && finished.isEmpty()
}

/**
 * 任务页 ViewModel（T5.8）。
 *
 * 干四件事：把盘上的历史读成两组、把「撤销」交给 [JobUndoRunner]（用户确认之后）、
 * 把「重跑」变成一次新的入队、把结果翻译成人话。
 *
 * 重跑不做成「就地再跑一遍同一个 jobId」：报告与快照都以 jobId 命名，覆盖它们等于
 * 把上一次的账撕掉重写（连带那次任务的备份时间戳——撤销还要靠它）。所以是**复制一份
 * 定义、换一个新 id**，旧的一行原样留在历史里。
 *
 * 时间从外面传进来（[clock] / [zone]）：撤销判定与「排队时间」都跟当下有关，
 * 单测要能把「现在」钉死。
 */
class JobHistoryViewModel(
    private val context: Context,
    private val history: JobHistoryStore,
    private val specs: JobSpecStore,
    private val undos: JobUndoRunner,
    private val reconciler: JobSnapshotReconciler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(JobHistoryUiState())
    val state: StateFlow<JobHistoryUiState> = _state.asStateFlow()

    /** 上一次因为「有任务落定」而重读的 jobId，避免广播连着来时报同一个。 */
    private var refreshedFor: String? = null

    init {
        viewModelScope.launch { load() }

        // 任务跑完的那一刻：列表要当场把这一行从「进行中」挪到「历史」，
        // 而不是等用户退出去再进来
        viewModelScope.launch {
            JobProgressHub.state.collect { live ->
                val snapshot = live?.snapshot ?: return@collect
                if (snapshot.status.isTerminal && refreshedFor != snapshot.jobId) {
                    refreshedFor = snapshot.jobId
                    load()
                }
            }
        }
    }

    /** 重读盘上历史（进页面、下拉、动作做完之后）。 */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    /** 用户按了「撤销」——先问一句，因为这一步会改文件。 */
    fun askUndo(entry: JobHistoryEntry) {
        _state.update { it.copy(confirming = entry) }
    }

    fun dismissUndo() {
        _state.update { it.copy(confirming = null) }
    }

    fun confirmUndo() {
        val entry = _state.value.confirming ?: return
        _state.update { it.copy(confirming = null, undoing = entry.jobId) }

        viewModelScope.launch {
            val outcome = undos.run(entry.jobId, clock())
            val message = outcome.map { undoneMessage(it) }.getOrElse { failure ->
                HistoryMessage(
                    R.string.jobs_history_undo_failed,
                    listOf(failure.detail ?: failure.code),
                )
            }
            _state.update { it.copy(undoing = null, message = message) }
            load()
        }
    }

    /** 用户按了「重跑」：照原样排一次新任务。 */
    fun rerun(entry: JobHistoryEntry) {
        if (_state.value.rerunning != null) return
        _state.update { it.copy(rerunning = entry.jobId) }

        viewModelScope.launch {
            val now = clock()
            val message = withContext(Dispatchers.IO) {
                val spec = specs.load(entry.jobId)
                    ?: return@withContext HistoryMessage(R.string.jobs_history_rerun_gone)

                val fresh = spec.copy(jobId = JobIds.newId(now), createdAtMillis = now)
                specs.save(fresh)
                    .map {
                        JobQueue.enqueue(context, fresh.jobId)
                        HistoryMessage(R.string.jobs_history_rerun_queued)
                    }
                    .getOrElse { failure ->
                        HistoryMessage(
                            R.string.jobs_history_rerun_failed,
                            listOf(failure.detail ?: failure.code),
                        )
                    }
            }

            _state.update { it.copy(rerunning = null, message = message) }
            load()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun load() {
        val loaded = withContext(Dispatchers.IO) {
            // 先对一遍账：被硬杀掉的任务盘上还写着 RUNNING，队列那头却早没它了。
            // 放在读之前，页面才不会把它当「进行中」摆出来。
            reconciler.reconcile(clock())
            history.load()
        }
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone)
        val entries = JobUndoRules.apply(loaded.entries, loaded.stamps, now)
        val grouped = JobHistory.from(entries)
        _state.update {
            it.copy(
                loading = false,
                running = grouped.running,
                finished = grouped.finished,
            )
        }
    }

    /** 撤销干完之后的说法：全成 / 成一半，两句话不一样。 */
    private fun undoneMessage(outcome: JobUndoOutcome): HistoryMessage =
        if (outcome.isClean) {
            HistoryMessage(
                if (outcome.deleted > 0 && outcome.restored == 0) {
                    R.string.jobs_history_undo_deleted
                } else {
                    R.string.jobs_history_undo_done
                },
                listOf(outcome.changed),
            )
        } else {
            HistoryMessage(
                R.string.jobs_history_undo_partial,
                listOf(outcome.changed, outcome.failures.size, outcome.failures.joinToString("；")),
            )
        }

    companion object {

        /** 手工装配（与其它页一致）：`applicationContext`，避免持有 Activity。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                JobHistoryViewModel(
                    context = app,
                    history = JobHistoryStore(app),
                    specs = JobSpecStore(app),
                    undos = JobUndoRunner(app),
                    reconciler = JobSnapshotReconciler(app),
                )
            }
        }
    }
}
