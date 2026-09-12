package com.pict.metatool.ui.batch

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.R
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.batch.SafBatchSourceReader
import com.pict.metatool.data.job.BatchJobSpec
import com.pict.metatool.data.job.JobIds
import com.pict.metatool.data.job.JobQueue
import com.pict.metatool.data.job.JobSpecStore
import com.pict.metatool.data.preset.MergedPresetCatalog
import com.pict.metatool.domain.batch.BatchDraft
import com.pict.metatool.domain.batch.BatchMode
import com.pict.metatool.domain.batch.BatchPreviewer
import com.pict.metatool.domain.batch.BatchSourceReader
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetCatalog
import com.pict.metatool.domain.preset.PresetEditor
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetSelection
import com.pict.metatool.domain.preset.UserFieldInput
import com.pict.metatool.domain.preset.UserPresetInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批量页的 ViewModel（T5.4 / T5.5）。
 *
 * 薄适配器：状态在 [BatchUiState]，计划怎么拼在 `BatchDraft`，算 diff 在
 * `BatchPreviewer` —— 这里只做「读目录 → 改草稿 → 起协程 → 收结果」。
 * 于是整套批量逻辑都能在 JVM 单测里跑，不需要仪器化测试。
 *
 * 预览跑在 [Dispatchers.IO]：读一批文件的元数据是重 I/O，不能占主线程；
 * 每算完一张回主线程更新进度，取消靠 `viewModelScope`（用户退出页面即停）。
 */
class BatchViewModel(
    private val appContext: Context,
    private val targets: List<BatchTarget>,
    private val reader: BatchSourceReader,
    private val catalog: PresetCatalog,
    /**
     * 用户自建预设的读写口子（`files/presets/`）。
     *
     * 默认 [PresetEditor.NONE]：没接线时「自己加一个」会明确报「没有可写的预设目录」，
     * 而不是假装存下去了。
     */
    private val editor: PresetEditor = PresetEditor.NONE,
    /** 时钟与 id 可注入：排队这件事本身能单测，不必等真时间。 */
    private val clock: () -> Long = System::currentTimeMillis,
    private val newJobId: (Long) -> String = { JobIds.newId(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(
        BatchUiState(
            targets = targets,
            presets = catalog.all(),
            userPresetIssues = editor.userIssues.map { it.toString() },
        ),
    )

    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    fun setMode(mode: BatchMode) {
        updateDraft { it.copy(mode = mode) }
    }

    /**
     * 分栏点选：每栏至多一个，跨栏可同时选（[PresetSelection]）；再点一下取消该栏。
     *
     * 与编辑页同一个 [PresetSelection]：两处的「已选」语义必须一致，
     * 否则「编辑页选了三栏、批量页只认最后一个」这种事就会从缝里漏出来。
     */
    fun togglePreset(preset: Preset) {
        updateDraft { draft ->
            draft.copy(presetIds = toggledIds(draft.presetIds, preset, state.value.presets))
        }
    }

    /** 「＋ 加一个预设」：给该栏一张空表单。 */
    fun addOwnPreset(kind: PresetKind) = _state.update {
        it.openUserPresetEditor(UserPresetInput(kind = kind, rows = listOf(UserFieldInput(null, ""))))
    }

    /** 「改」：把自建预设摊回表单（坐标/抖动那类字段原样保留）。 */
    fun editOwnPreset(presetId: String) = _state.update { current ->
        current.presets.firstOrNull { it.id == presetId }
            ?.let { current.openUserPresetEditor(UserPresetInput.from(it)) }
            ?: current
    }

    fun dismissUserPresetEditor() = _state.update { it.closeUserPresetEditor() }

    /** 保存自建预设：落盘 → 重读目录 → 顺手把它选进自己那一栏（加完就能用）。 */
    fun saveUserPreset(input: UserPresetInput) {
        viewModelScope.launch {
            when (val saved = withContext(Dispatchers.IO) { editor.save(input) }) {
                is PictResult.Failure -> _state.update {
                    it.withUserPresetMessage("存不下来：${saved.failure.detail ?: saved.code}")
                }

                is PictResult.Success -> {
                    val loaded = withContext(Dispatchers.IO) { catalog.all() }
                    val issues = withContext(Dispatchers.IO) { editor.userIssues.map { it.toString() } }
                    _state.update { current ->
                        val picked = toggledIds(current.draft.presetIds, saved.value, loaded)
                        current.copy(
                            presets = loaded,
                            userPresetIssues = issues,
                            userPresetDraft = null,
                            userPresetMessage = null,
                            draft = current.draft.copy(presetIds = picked),
                            // 草稿变了，旧的预览与排队记录就都不作数了（同 updateDraft 的口径）
                            preview = null,
                            message = null,
                            queuedJobId = null,
                            queuedCount = 0,
                        )
                    }
                }
            }
        }
    }

    /** 删除自建预设（内置的不给删，`PresetEditor.delete` 会挡）。 */
    fun deleteUserPreset(presetId: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { editor.delete(presetId) }
            val loaded = withContext(Dispatchers.IO) { catalog.all() }
            val issues = withContext(Dispatchers.IO) { editor.userIssues.map { it.toString() } }
            _state.update { current ->
                if (!removed) {
                    current.withUserPresetMessage("这个预设删不掉（内置的不能删）")
                } else {
                    current.copy(
                        presets = loaded,
                        userPresetIssues = issues,
                        userPresetDraft = null,
                        userPresetMessage = null,
                        // 删掉的预设不能留在草稿里，否则「预览」会报「预设不存在」
                        draft = current.draft.copy(
                            presetIds = current.draft.presetIds.filter { id -> loaded.any { it.id == id } },
                        ),
                        preview = null,
                        message = "已删掉这个预设",
                        queuedJobId = null,
                        queuedCount = 0,
                    )
                }
            }
        }
    }

    /** 选中/取消之后统一按栏排序，草稿里存的就是**套用顺序**。 */
    private fun toggledIds(ids: List<String>, preset: Preset, presets: List<Preset>): List<String> =
        PresetSelection.orderedIds(PresetSelection.toggle(PresetSelection.of(ids, presets), preset), presets)

    fun setOverwrite(overwrite: Boolean) {
        updateDraft { it.copy(overwriteExisting = overwrite) }
    }

    /** 勾选/取消一组要清掉的字段（清除模式下才是有效操作）。 */
    fun toggleClearTarget(target: ClearTarget) {
        updateDraft { draft ->
            val next = if (target in draft.clearTargets) draft.clearTargets - target
            else draft.clearTargets + target
            draft.copy(clearTargets = next)
        }
    }

    fun setFilter(filter: PreviewFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun toggleExpanded(uri: String) {
        _state.update { state ->
            val next = if (uri in state.expanded) state.expanded - uri else state.expanded + uri
            state.copy(expanded = next)
        }
    }

    /** 回「改一改」：草稿留着，预览结果也留着，改完再点预览会重算。 */
    fun backToEdit() {
        _state.update { it.copy(step = BatchStep.EDIT, progress = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * 算这一批的改动（FR-32：只读）。
     *
     * 草稿拼不出计划时直接给提示，不启动协程——失败原因来自 `PictResult.detail`，
     * 与域层同一套措辞。
     */
    fun preview() {
        val current = _state.value
        if (current.isPreviewing || !current.hasTargets) return

        val plan = when (val result = current.draft.toPlan(catalog)) {
            is PictResult.Failure -> {
                _state.update { it.copy(message = result.failure.detail ?: "计划还不完整") }
                return
            }

            is PictResult.Success -> result.value
        }

        _state.update { it.copy(progress = 0 to current.targets.size, message = null) }

        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) {
                BatchPreviewer(reader, catalog).preview(plan, current.targets) { done, total, _ ->
                    _state.update { it.copy(progress = done to total) }
                }
            }
            _state.update {
                it.copy(preview = preview, step = BatchStep.PREVIEW, progress = null, filter = PreviewFilter.ALL)
            }
        }
    }

    /**
     * 开始执行（T5.3）：把这一批交给 WorkManager 的后台队列。
     *
     * 顺序不能反：**先把定义存下来，再入队**。worker 可能在我们入队后的下一秒就被系统拉起来，
     * 那时它唯一的信息来源就是盘上那份定义——先入队后落盘会撞上「任务起来了、定义还没写完」。
     *
     * 只管排队、不等结果：进度在通知栏（[com.pict.metatool.data.job.JobNotifier]），
     * 页面这层的责任是「计划算准 + 交出去」，不是盯着它跑完（T5.6 才是进度页）。
     */
    fun start() {
        val current = _state.value
        if (!current.canExecute) return

        val now = clock()
        val jobId = newJobId(now)
        val spec = BatchJobSpec.from(
            draft = current.draft,
            targets = current.targets,
            options = JobOptions(),
            jobId = jobId,
            nowMillis = now,
            presetNames = current.draft.presetIds
                .mapNotNull { id -> current.presets.firstOrNull { it.id == id }?.name },
        )

        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { JobSpecStore(appContext).save(spec) }
            if (saved is PictResult.Failure) {
                val reason = saved.failure.detail ?: saved.failure.error.code
                _state.update { it.copy(message = appContext.getString(R.string.batch_execute_queue_failed, reason)) }
                return@launch
            }

            JobQueue.enqueue(appContext, jobId)
            _state.update {
                it.copy(
                    queuedJobId = jobId,
                    queuedCount = spec.items.size,
                    message = appContext.getString(R.string.batch_execute_pending),
                )
            }
        }
    }

    private fun updateDraft(transform: (BatchDraft) -> BatchDraft) {
        _state.update { state ->
            // 改了草稿就丢掉旧预览：留着的话，屏幕上那些数字已经不是这份计划算出来的了。
            // 排队记录一并清掉——队列里那份是**旧**计划，改完再点执行应该排新的一次。
            state.copy(
                draft = transform(state.draft),
                preview = null,
                message = null,
                queuedJobId = null,
                queuedCount = 0,
            )
        }
    }

    companion object {

        /**
         * 手工装配（与图库/编辑页同一套路）：`applicationContext` 的 ContentResolver，
         * 预设目录走「安装包内置 + 用户自建」两份合并，避免持有 Activity。
         */
        fun factory(context: Context, targets: List<BatchTarget>): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = context.applicationContext
                    val catalog = MergedPresetCatalog.of(app)
                    BatchViewModel(
                        appContext = app,
                        targets = targets,
                        reader = SafBatchSourceReader(app.contentResolver),
                        catalog = catalog,
                        editor = catalog,
                    )
                }
            }
    }
}
