package com.pict.metatool.ui.batch

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.batch.SafBatchSourceReader
import com.pict.metatool.data.preset.AssetPresetCatalog
import com.pict.metatool.domain.batch.BatchDraft
import com.pict.metatool.domain.batch.BatchMode
import com.pict.metatool.domain.batch.BatchPreviewer
import com.pict.metatool.domain.batch.BatchSourceReader
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.preset.PresetCatalog
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
    private val targets: List<BatchTarget>,
    private val reader: BatchSourceReader,
    private val catalog: PresetCatalog,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BatchUiState(targets = targets, presets = catalog.all()),
    )

    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    fun setMode(mode: BatchMode) {
        updateDraft { it.copy(mode = mode) }
    }

    fun setPreset(presetId: String) {
        updateDraft { it.copy(presetId = presetId) }
    }

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

    private fun updateDraft(transform: (BatchDraft) -> BatchDraft) {
        _state.update { state ->
            // 改了草稿就丢掉旧预览：留着的话，屏幕上那些数字已经不是这份计划算出来的了
            state.copy(draft = transform(state.draft), preview = null, message = null)
        }
    }

    companion object {

        /**
         * 手工装配（与图库/编辑页同一套路）：`applicationContext` 的 ContentResolver，
         * 预设目录走 APK 资源，避免持有 Activity。
         */
        fun factory(context: Context, targets: List<BatchTarget>): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = context.applicationContext
                    BatchViewModel(
                        targets = targets,
                        reader = SafBatchSourceReader(app.contentResolver),
                        catalog = AssetPresetCatalog(app.assets),
                    )
                }
            }
    }
}
