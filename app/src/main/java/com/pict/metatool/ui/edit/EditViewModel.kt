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
import com.pict.metatool.data.preset.MergedPresetCatalog
import com.pict.metatool.data.source.ImageCopy
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.plan.EditPlanExecutor
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetCatalog
import com.pict.metatool.domain.preset.PresetEditor
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetResolver
import com.pict.metatool.domain.preset.UserFieldInput
import com.pict.metatool.domain.preset.UserPresetInput
import com.pict.metatool.domain.settings.AppSettings
import java.util.Random
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
    private val presets: PresetCatalog = PresetCatalog.EMPTY,
    private val presetIssues: List<String> = emptyList(),
    /**
     * 用户自建预设的读写口子（`files/presets/`）。
     *
     * 默认 [PresetEditor.NONE]：没接线时「自己加一个」会明确报「没有可写的预设目录」，
     * 而不是假装存下去了（单测默认也走这条）。
     */
    private val editor: PresetEditor = PresetEditor.NONE,
    /** 启动时生效的设置（docs/06 §3.7）：默认套用方式、默认种子、导出后缀与校验开关都从这一份取。 */
    private val settings: AppSettings = AppSettings(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        EditUiState(
            // 默认套用方式与随机种子来自设置（docs/06 §3.7）：用户改过就按用户的来
            presetOverwrite = settings.presetOverwriteDefault,
            randomFillSeed = settings.randomSeedDefault,
        ),
    )
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedUri: String? = null
    private var presetsJob: Job? = null

    init {
        // 预设是打包资源 + 用户目录：读一次、缓存进程内；空目录（单测 / 未接线）就走一遍得到空列表。
        // 读盘与解析放 IO，别卡首屏。
        presetsJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { presets.all() }
            _state.update { it.withPresetCatalog(loaded, presetIssues, userPresetIssues()) }
        }
    }

    /** 用户目录里读不动的那几个文件（坏 JSON 之类），折成 UI 上的一行提示。 */
    private suspend fun userPresetIssues(): List<String> = withContext(Dispatchers.IO) {
        editor.userIssues.map { it.toString() }
    }

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

    // ---------- 预设 / 随机填充 / 添加字段（docs/07 T3.7、T3.9） ----------

    fun openPresets() = update { it.openPresets() }

    fun closePresets() = update { it.closePresets() }

    fun setPresetOverwrite(overwrite: Boolean) = update { it.setPresetOverwrite(overwrite) }

    fun openAddField() = update { it.openAddField() }

    fun closeAddField() = update { it.closeAddField() }

    fun onAddFieldQueryChange(query: String) = update { it.withAddFieldQuery(query) }

    fun openRandomFill() = update { state -> state.openRandomFill(state.randomFillPreset) }

    fun closeRandomFill() = update { it.closeRandomFill() }

    fun chooseRandomFillPreset(presetId: String) = update { state ->
        state.presets.firstOrNull { it.id == presetId }?.let(state::withRandomFillPreset) ?: state
    }

    fun toggleRandomFillKey(key: TagKey) = update { it.toggleRandomFillKey(key) }

    /** 换一批：只换种子。种子在界面上可见，所以「换过的那一批」照样可复现。 */
    fun rerollRandomFillSeed() = update { it.withRandomFillSeed(Random().nextLong()) }

    /** 套用预设：只填缺失还是覆盖已有值，由界面上的开关决定。 */
    fun applyPreset(presetId: String) {
        val current = _state.value
        val preset = current.presets.firstOrNull { it.id == presetId } ?: return
        fill(preset, keys = null, onlyMissing = !current.presetOverwrite)
    }

    /** 分栏点选：每栏至多一个，跨栏可同时选（[PresetSelection]）。只改选择，不动草稿。 */
    fun togglePresetPick(preset: Preset) = update { it.togglePresetPick(preset) }

    /**
     * 把已选的那几栏一次套进草稿，按**套用顺序**（设备 → 位置 → 时间 → 混合）依次填。
     *
     * 顺序不是随手排的：默认「只填缺失」时后面的预设看得见前面刚落下的值，
     * 所以「机型 + 位置 + 时间」互不冲撞；打开「覆盖已有值」时就是后面的赢。
     * 每一次 [fill] 都读当前草稿当底，于是三栏叠出来的结果与逐次手点完全一致。
     */
    fun applyPickedPresets() {
        val picked = _state.value.pickedPresets
        if (picked.isEmpty()) return
        picked.forEach { preset -> fill(preset, keys = null, onlyMissing = !_state.value.presetOverwrite) }
    }

    /** 「＋ 加一个预设」：给该栏一张空表单。 */
    fun addOwnPreset(kind: PresetKind) = update {
        it.openUserPresetEditor(UserPresetInput(kind = kind, rows = listOf(UserFieldInput(null, ""))))
    }

    /** 「改」：把自建预设摊回表单（坐标/抖动那类字段原样保留）。 */
    fun editOwnPreset(presetId: String) = update { state ->
        state.presets.firstOrNull { it.id == presetId }
            ?.let { state.openUserPresetEditor(UserPresetInput.from(it)) }
            ?: state
    }

    fun dismissUserPresetEditor() = update { it.closeUserPresetEditor() }

    /**
     * 保存自建预设：先过 [PresetEditor.save]（它会用 [PresetParser] 读回一遍再落盘），
     * 成功后重读目录、把新预设**顺手选进它那一栏**——加完就能用，不用再找一遍。
     */
    fun saveUserPreset(input: UserPresetInput) {
        viewModelScope.launch {
            when (val saved = withContext(Dispatchers.IO) { editor.save(input) }) {
                is PictResult.Failure -> update {
                    it.withUserPresetMessage("存不下来：${saved.failure.detail ?: saved.code}")
                }

                is PictResult.Success -> {
                    val loaded = withContext(Dispatchers.IO) { presets.all() }
                    _state.update { state ->
                        state.withPresetCatalog(loaded, presetIssues, userPresetIssues())
                            .withUserPresetSaved(saved.value)
                    }
                }
            }
        }
    }

    /** 删除自建预设（内置的不给删，`PresetEditor.delete` 会挡）。 */
    fun deleteUserPreset(presetId: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { editor.delete(presetId) }
            val loaded = withContext(Dispatchers.IO) { presets.all() }
            _state.update { state ->
                state.withPresetCatalog(loaded, presetIssues, userPresetIssues())
                    .let { fresh ->
                        if (removed) {
                            fresh.closeUserPresetEditor().withMessage("已删掉这个预设")
                        } else {
                            fresh.withUserPresetMessage("这个预设删不掉（内置的不能删）")
                        }
                    }
            }
        }
    }

    /** 随机填充勾选的字段。 */
    fun fillRandom() {
        val current = _state.value
        val preset = current.randomFillPreset ?: return
        fill(preset, keys = current.randomFillKeys, onlyMissing = false, seed = current.randomFillSeed)
    }

    /**
     * 「生成预览」：先把这次填充会写进去的字段算出来给用户看，**不动草稿**（docs/06 §3.3）。
     *
     * 与 [fillRandom] 走的是同一次纯计算（`PresetResolver.fill`，同预设 + 同勾选 + 同种子），
     * 所以预览里的值与确认后进草稿的值必然一致——预览不是另算一遍，
     * 否则「看到的」和「写进去的」就成了两件事。
     */
    fun previewRandomFill() {
        val current = _state.value
        val preset = current.randomFillPreset ?: return
        val base = current.proposed ?: current.metadata ?: return
        val filled = PresetResolver.fill(
            preset,
            base,
            keys = current.randomFillKeys,
            onlyMissing = false,
            seed = current.randomFillSeed,
        )
        when (filled) {
            is PictResult.Failure -> update {
                it.withMessage("预览失败：${filled.failure.detail ?: filled.code}", isError = true)
            }

            is PictResult.Success -> update { state -> state.withRandomFillPreview(EditDiff.rows(base, filled.value)) }
        }
    }

    /** 「返回修改」：丢掉这次预览，退回挑选那一步（草稿本来就没动过）。 */
    fun clearRandomFillPreview() = update { it.withRandomFillPreview(null) }

    /**
     * 填充的公共路径：以「源文件 + 已有草稿」为基准算目标，再把**变化项**并进草稿。
     *
     * 为什么基准不是源文件：用户可能已经手改过几项，「只填空缺」理应把那些手改当成已有值，
     * 否则一次套用就把刚改的内容盖掉了。整条路径不落盘——写文件仍然只有「应用」那一处。
     */
    private fun fill(preset: Preset, keys: Set<TagKey>?, onlyMissing: Boolean, seed: Long = 0L) {
        val current = _state.value
        val base = current.proposed ?: current.metadata ?: return
        when (val filled = PresetResolver.fill(preset, base, keys = keys, onlyMissing = onlyMissing, seed = seed)) {
            is PictResult.Failure -> update {
                it.withMessage("填充失败：${filled.failure.detail ?: filled.code}", isError = true)
            }

            is PictResult.Success -> update { it.withPresetFill(preset.name, filled.value) }
        }
    }

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
            MetadataVerifier.compare(
                before = before,
                target = target,
                after = it.set,
                fingerprintBefore = fingerprintBefore,
                fingerprintAfter = fingerprintAfter,
                // 写入器事前声明装不下的键（XMP 之类）不算缺失，否则永远报「校验未通过」
                dropped = written.droppedKeys,
            )
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

    /**
     * 导出：把「源文件 + 当前草稿」写成 [destination] 这份新文件（docs/06 §3.3「导出」）。
     *
     * 与 [apply] 的关系：折叠用的是同一份草稿、同一个执行器，差别只有写谁、写完留什么——
     * - 写的是**副本**：源文件全程只读，导出要先复制字节（[ImageCopy]），再在副本上写元数据；
     * - 副本落在用户选的目录/Provider 上，所以折叠基准与写入器都按**副本自己的读数**重新算一遍；
     * - 写完**不清草稿**：副本不是当前编辑对象，源文件还没保存。
     *
     * 副本这种格式吃不下元数据（HEIF 之类）时不算失败：复制已经完成，
     * 如实说「副本内容与源一致」比先复制一半再报错好。
     */
    fun exportTo(destination: Uri) {
        val current = _state.value
        if (!current.canExport) return
        val info = current.source ?: return
        val before = current.metadata ?: return
        val uri = current.uri?.let(Uri::parse) ?: return
        val source = ImageSource(uri = uri, info = info)

        // 先干跑：草稿本身不合法就别去建文件，省得在用户目录里留个半成品
        current.planned?.failureOrNull()?.let { failure ->
            _state.update {
                it.withMessage("改动没通过校验：${failure.detail ?: failure.code}", isError = true)
            }
            return
        }

        _state.update { it.withExporting() }
        viewModelScope.launch {
            try {
                val copied = withContext(Dispatchers.IO) {
                    ImageCopy.copy(resolver, source.uri, destination)
                }
                if (copied is PictResult.Failure) {
                    _state.update {
                        it.withExportFinished()
                            .withError(copied.error, copied.failure.detail)
                            .withMessage("导出失败：${copied.failure.detail ?: copied.error.code}", isError = true)
                    }
                    return@launch
                }

                val copy = withContext(Dispatchers.IO) { ImageSource.from(resolver, destination) }
                val writer = writers.firstOrNull { it.canWrite(copy.info) }
                if (writer == null) {
                    _state.update {
                        it.withExportFinished().withMessage(
                            "已导出副本「${copy.displayName}」：${copy.info.format.label} 不支持写元数据，副本与源一致",
                        )
                    }
                    return@launch
                }

                val copyBefore = withContext(Dispatchers.IO) { reader.read(resolver, copy) }
                    .getOrNull()?.set ?: before
                val target = when (
                    val folded = EditPlanExecutor.execute(current.draft.toPlan(dryRun = true), copyBefore)
                ) {
                    is PictResult.Success -> folded.value.target
                    is PictResult.Failure -> {
                        _state.update {
                            it.withExportFinished().withMessage(
                                "副本已导出，但改动没写进去：${folded.failure.detail ?: folded.code}",
                                isError = true,
                            )
                        }
                        return@launch
                    }
                }

                val fingerprintBefore = withContext(Dispatchers.IO) { hasher.hashOf(resolver, copy.uri) }
                    .getOrNull()
                val written = withContext(Dispatchers.IO) { writer.write(resolver, copy, target) }
                when (written) {
                    is PictResult.Failure -> _state.update {
                        it.withExportFinished()
                            .withError(written.error, written.failure.detail)
                            .withMessage(
                                "副本已导出，但写入失败：${written.failure.detail ?: written.error.code}",
                                isError = true,
                            )
                    }

                    is PictResult.Success -> {
                        if (!settings.verifyAfterExport) {
                            // 设置里关了校验：如实说「没读回来」，不拿一个像通过的字样糊过去
                            _state.update {
                                it.withExportFinished().withMessage(
                                    "已导出「${copy.displayName}」：${written.value.summary()}；按设置跳过了读回校验",
                                )
                            }
                            return@launch
                        }
                        val after = withContext(Dispatchers.IO) { reader.read(resolver, copy) }.getOrNull()
                        val fingerprintAfter = withContext(Dispatchers.IO) { hasher.hashOf(resolver, copy.uri) }
                            .getOrNull()
                        val report = after?.let {
                            MetadataVerifier.compare(
                                before = copyBefore,
                                target = target,
                                after = it.set,
                                fingerprintBefore = fingerprintBefore,
                                fingerprintAfter = fingerprintAfter,
                                dropped = written.value.droppedKeys,
                            )
                        }
                        val verifyText = report?.summary() ?: "读回失败，无法校验"
                        val failed = report?.isLossless == false
                        _state.update {
                            it.withExportFinished().withMessage(
                                text = "已导出「${copy.displayName}」：${written.value.summary()}；$verifyText" +
                                    if (failed) "（输出文件可能有问题）" else "",
                                isError = failed,
                            )
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update {
                    it.withExportFinished()
                        .withError(PictError.UNKNOWN, t.message)
                        .withMessage("导出中断：${t.message}", isError = true)
                }
            }
        }
    }

    private inline fun update(block: (EditUiState) -> EditUiState) = _state.update(block)

    companion object {

        /** 无 DI 框架时的手工装配，与详情页一致（用 applicationContext 的 resolver）。 */
        fun factory(
            context: Context,
            settings: AppSettings = AppSettings(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                // 预设 = 安装包里的内置那几份 + 用户自己加的那几份（files/presets/）。
                // 两处坏文件的问题逐条带到界面上，而不是悄悄吞掉
                val catalog = MergedPresetCatalog.of(context)
                EditViewModel(
                    resolver = context.applicationContext.contentResolver,
                    presets = catalog,
                    presetIssues = catalog.issues.map { it.toString() },
                    editor = catalog,
                    settings = settings,
                )
            }
        }
    }
}
