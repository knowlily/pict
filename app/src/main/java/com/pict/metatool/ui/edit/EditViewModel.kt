package com.pict.metatool.ui.edit

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.metadata.BitmapPixelHasher
import com.pict.metatool.data.metadata.MetadataReader
import com.pict.metatool.data.metadata.MetadataVerifier
import com.pict.metatool.data.metadata.MetadataWriter
import com.pict.metatool.data.metadata.PixelHasher
import com.pict.metatool.data.metadata.exif.ExifMetadataStore
import com.pict.metatool.data.metadata.imaging.CommonsImagingStore
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.plan.EditPlanExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑页 ViewModel（docs/07 T2.10）。
 *
 * 职责边界与详情页一致：只做编排。状态迁移全在 [EditUiState] 的纯函数里，
 * 取文件信息、读字段、写字段、重读校验这些阻塞调用统一切到 `Dispatchers.IO`。
 *
 * 写入路径只有一条：草稿 → [EditPlanExecutor] 折叠 → 选写入器 → 落盘 → 重读比对。
 * 折叠失败（字段只读、格式不接受该类型）在**动文件之前**就报错；
 * HEIF 这类不能原地写的格式靠 [MetadataWriter.canWrite] 提前拦下，
 * 由界面提示「需重编码」，而不是写坏了再回滚（docs/02 §5）。
 */
class EditViewModel(
    private val resolver: ContentResolver,
    private val reader: MetadataReader = MetadataReader(),
    private val writers: List<MetadataWriter> = listOf(ExifMetadataStore(), CommonsImagingStore()),
    private val hasher: PixelHasher = BitmapPixelHasher(),
) : ViewModel() {

    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedUri: String? = null

    /** 载入一张图；同一张已读成功的不重复读。 */
    fun load(uri: String) {
        if (loadedUri == uri && _state.value.hasMetadata) return
        loadedUri = uri
        loadJob?.cancel()
        _state.value = _state.value.withLoading(uri)
        loadJob = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { ImageSource.from(resolver, Uri.parse(uri)) }
                val read = withContext(Dispatchers.IO) { reader.read(resolver, source) }
                when (read) {
                    is PictResult.Success -> _state.update {
                        it.withLoaded(source.info, read.value.set, read.value.origins)
                            .copy(canWriteInPlace = writers.any { writer -> writer.canWrite(source.info) })
                    }

                    is PictResult.Failure -> _state.update { it.withError(read.error, read.failure.detail) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update { it.withError(PictError.UNKNOWN, t.message) }
            }
        }
    }

    fun retry() {
        val uri = _state.value.uri ?: return
        _state.value = _state.value.copy(metadata = null)
        load(uri)
    }

    fun edit(key: TagKey) = update { it.openEditor(key) }

    /** 从搜索结果里挑一个目录字段补到这张图上。 */
    fun addField(spec: FieldSpec) = update { it.beginAdd(spec) }

    fun onInput(text: String) = update { it.withInput(text) }

    fun commitInput() = update { it.commitEditor() }

    fun cancelInput() = update { it.closeEditor() }

    fun revert(key: TagKey) = update { it.revertField(key) }

    fun revertAll() = update { it.revertAll() }

    fun onQueryChange(query: String) = update { it.withQuery(query) }

    fun requestDiscard() = update { it.requestDiscard() }

    fun cancelDiscard() = update { it.cancelDiscard() }

    fun openPreview() = update { it.openPreview() }

    fun closePreview() = update { it.closePreview() }

    fun consumeMessage() = update { it.consumeMessage() }

    /**
     * 折叠 + 落盘 + 重读校验（docs/07 T2.10 的「应用」）。
     *
     * 写盘是整条链路上唯一有副作用的一步，所以前两步都做成可提前失败的纯判断：
     * 干跑折叠能挡住非法草稿，`canWrite` 能挡住不能原地写的格式。
     */
    fun apply() {
        val current = _state.value
        if (!current.canApply) return
        val info = current.source ?: return
        val before = current.metadata ?: return
        val uri = current.uri?.let(Uri::parse) ?: return
        val source = ImageSource(uri = uri, info = info)

        val target = when (val folded = EditPlanExecutor.execute(current.draft.toPlan(dryRun = true), before)) {
            is PictResult.Success -> folded.value.target

            is PictResult.Failure -> {
                _state.update {
                    it.withMessage("改动没通过校验：${folded.failure.detail ?: folded.code}", isError = true)
                }
                return
            }
        }

        val writer = writers.firstOrNull { it.canWrite(info) }
        if (writer == null) {
            _state.update {
                it.withError(PictError.ENCODE_UNSUPPORTED, "no in-place writer for ${info.format.name}")
                    .withMessage("${info.format.label} 不能原地写元数据，先另存成 JPEG/PNG 再改", isError = true)
            }
            return
        }

        _state.update { it.withApplying() }
        viewModelScope.launch {
            try {
                val fingerprintBefore = withContext(Dispatchers.IO) {
                    hasher.hashOf(resolver, source.uri)
                }.getOrNull()
                val written = withContext(Dispatchers.IO) { writer.write(resolver, source, target) }
                when (written) {
                    is PictResult.Failure -> _state.update {
                        it.withError(written.error, written.failure.detail)
                            .withMessage("写入失败：${written.failure.detail ?: written.error.code}", isError = true)
                    }

                    is PictResult.Success -> onWritten(source, before, target, written.value, fingerprintBefore)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update {
                    it.withError(PictError.UNKNOWN, t.message).withMessage("写入中断：${t.message}", isError = true)
                }
            }
        }
    }

    /** 写完之后把结果读回来比对，并把新值当成新的基线（草稿清空）。 */
    private suspend fun onWritten(
        source: ImageSource,
        before: com.pict.metatool.domain.model.MetadataSet,
        target: com.pict.metatool.domain.model.MetadataSet,
        written: com.pict.metatool.data.metadata.WriteResult,
        fingerprintBefore: String?,
    ) {
        val reread = withContext(Dispatchers.IO) { reader.read(resolver, source) }
        val after = reread.getOrNull()
        val fingerprintAfter = withContext(Dispatchers.IO) {
            hasher.hashOf(resolver, source.uri)
        }.getOrNull()
        val report = after?.let {
            MetadataVerifier.compare(before, target, it.set, fingerprintBefore, fingerprintAfter)
        }
        val verifyText = report?.summary() ?: "读回失败，无法校验"
        val failed = report?.isLossless == false
        _state.update { state ->
            val applied = state.withApplied(verifyText)
            val refreshed = if (after != null) {
                applied.copy(metadata = after.set, origins = after.origins)
            } else {
                applied
            }
            refreshed.withMessage(
                text = if (failed) "$verifyText（输出文件可能有问题）" else "${written.summary()}；$verifyText",
                isError = failed,
            )
        }
    }

    private inline fun update(block: (EditUiState) -> EditUiState) = _state.update(block)

    companion object {

        /** 无 DI 框架时的手工装配，与详情页一致（用 applicationContext 的 resolver）。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditViewModel(context.applicationContext.contentResolver) }
        }
    }
}
