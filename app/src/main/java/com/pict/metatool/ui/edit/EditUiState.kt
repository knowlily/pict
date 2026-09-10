package com.pict.metatool.ui.edit

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.plan.EditOutcome
import com.pict.metatool.domain.plan.EditPlanExecutor
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetResolver
import com.pict.metatool.domain.settings.AppSettings

/**
 * 单文件编辑页的状态与状态迁移（docs/07 T2.10、docs/06 §3.3）。
 *
 * 分层：本文件只做**纯逻辑**（分组、搜索、草稿迁移、diff 派生），不引 Android；
 * 读文件、写盘、校验由 [EditViewModel] 负责。这样「未保存计数」「diff 内容」这类
 * 容易出错的规则能在 JVM 单测里锁住，不必上设备。
 *
 * 关键规则：
 * - 「应用 N」里的 N 用 [dirtyCount]（与源相比真正变化的项），不是草稿条数 ——
 *   改了又改回来不该算未保存；
 * - 只读字段**照样列出来**（灰显、无 ✎）：编辑页是「这个文件有什么」的全貌视图，
 *   藏掉只读字段会让人以为文件里没有它；
 * - diff 预览不是另写一套比较，直接折叠草稿取 `EditOutcome`，预览与落盘同源。
 */
data class EditFieldRow(
    val key: TagKey,
    val label: String,
    val display: String,
    /** 原始字面量，编辑弹层预填用（展示格式回填会改值，见 [EditFieldInput]）。 */
    val raw: String,
    val editable: Boolean,
    val dirty: Boolean,
    /** 只读 / 有条件的原因，或目录里的备注。 */
    val note: String?,
) {

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return label.contains(q) ||
            key.full.contains(q, ignoreCase = true) ||
            display.contains(q, ignoreCase = true) ||
            (note?.contains(q) == true)
    }
}

/** 编辑页的一个分组区块。 */
data class EditSection(
    val group: FieldGroup?,
    val title: String,
    val rows: List<EditFieldRow>,
)

/** 一次性提示（Snackbar）。 */
data class EditMessage(val text: String, val isError: Boolean = false)

data class EditUiState(
    val uri: String? = null,
    val source: SourceInfo? = null,
    val metadata: MetadataSet? = null,
    val origins: Map<TagKey, List<String>> = emptyMap(),
    val draft: EditDraft = EditDraft.EMPTY,
    val isLoading: Boolean = false,
    val isApplying: Boolean = false,
    /** 正在导出副本（写入目标文件期间禁用重复触发）。 */
    val isExporting: Boolean = false,
    val error: PictError? = null,
    val errorDetail: String? = null,
    val query: String = "",
    val editing: TagKey? = null,
    val input: String = "",
    val inputError: String? = null,
    val showPreview: Boolean = false,
    val confirmDiscard: Boolean = false,
    val message: EditMessage? = null,
    val verifySummary: String? = null,
    /** 该格式能否原地写（HEIF 为 false，UI 提示「需重编码」）。 */
    val canWriteInPlace: Boolean = true,

    // ---------- 预设与随机填充（docs/06 §3.3、docs/07 T3.7/T3.9） ----------
    /** 可用的预设（内置来自 assets，见 `AssetPresetCatalog`）。 */
    val presets: List<Preset> = emptyList(),
    /** 预设加载时的问题（坏文件、未登记字段提醒），非空时在弹层里如实展示。 */
    val presetIssues: List<String> = emptyList(),
    val showPresets: Boolean = false,
    val showRandomFill: Boolean = false,
    val showAddField: Boolean = false,
    /** 套用预设是否覆盖已有值；false = 只填缺失（默认更安全）。 */
    val presetOverwrite: Boolean = false,
    val randomFillPresetId: String? = null,
    val randomFillKeys: Set<TagKey> = emptySet(),
    val randomFillSeed: Long = 0L,
    /** 「添加字段」弹层里的本地搜索词（目录里一百多个字段，不给过滤等于不可用）。 */
    val addFieldQuery: String = "",
) {

    val fileName: String? get() = source?.displayName

    val hasMetadata: Boolean get() = metadata?.isEmpty == false

    /** 与源相比真正变化的键。 */
    val dirtyKeys: Set<TagKey> get() = metadata?.let { draft.effectiveKeys(it) } ?: emptySet()

    val dirtyCount: Int get() = dirtyKeys.size

    val isDirty: Boolean get() = dirtyCount > 0

    val canApply: Boolean get() = isDirty && !isApplying && !isLoading && canWriteInPlace

    /**
     * 能不能导出副本。
     *
     * 刻意不看 [canWriteInPlace]：导出写的是**副本**，源文件一个字都不动，
     * 所以 HEIF 这类不能原地写的格式照样能导（只是那份副本不带改动，写入器拦下时会如实说）。
     */
    val canExport: Boolean get() = metadata != null && !isLoading && !isApplying && !isExporting

    /** 草稿折叠结果；预览与落盘共用同一份折叠。 */
    val planned: PictResult<EditOutcome>?
        get() = metadata?.let { EditPlanExecutor.execute(draft.toPlan(dryRun = true), it) }

    val proposed: MetadataSet? get() = planned?.getOrNull()?.target

    val planFailure: String? get() = planned?.failureOrNull()?.let { it.detail ?: it.code }

    val diffRows: List<EditDiffRow>
        get() = metadata?.let { source -> planned?.getOrNull()?.let { EditDiff.rows(source, it) } }.orEmpty()

    val sections: List<EditSection>
        get() {
            val set = metadata ?: return emptyList()
            val known = FieldCatalog.all
                .filter { set.entries.containsKey(it.key) || draft.contains(it.key) }
                .map { it.key }
            val unregistered = (set.entries.keys + draft.keys)
                .filter { FieldCatalog.spec(it) == null }
                .sortedWith(compareBy({ it.namespace }, { it.name }))

            return (known + unregistered)
                .groupBy { FieldCatalog.spec(it)?.group }
                .map { (group, keys) ->
                    EditSection(
                        group = group,
                        title = group?.label ?: UNREGISTERED_TITLE,
                        rows = keys.map { row(it, set) },
                    )
                }
                // 结构字段（只读）不该顶在第一位，按分组枚举顺序排，未登记键排最后
                .sortedBy { section ->
                    section.group?.let { FieldGroup.entries.indexOf(it) } ?: FieldGroup.entries.size
                }
        }

    /** 搜索过滤后的分组；未命中任何行的分组整块消失，不留空标题。 */
    val visibleSections: List<EditSection>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return sections
            return sections.mapNotNull { section ->
                val rows = section.rows.filter { it.matches(q) }
                rows.takeIf { it.isNotEmpty() }?.let { section.copy(rows = it) }
            }
        }

    /** 搜索时顺手给出「本文件还没有、可补进来」的字段（目录里可编辑的）。 */
    val additions: List<FieldSpec>
        get() {
            val set = metadata ?: return emptyList()
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return FieldCatalog.search(q)
                .filter { it.canEdit && set.entries[it.key] == null && !draft.contains(it.key) }
                .take(ADDITION_LIMIT)
        }

    /**
     * 「添加字段」列表：目录里**可写**、本文件还没有、草稿里也没有的字段。
     *
     * 与 [additions] 的区别：那个跟着搜索词走（顺手补一个），这个是无前提的全量浏览——
     * 「原图没有的元数据」得有个看得见的地方能挑，而不是让用户先猜到名字再搜。
     */
    val addableFields: List<FieldSpec>
        get() {
            val set = metadata ?: return emptyList()
            val keyword = addFieldQuery.trim()
            return FieldCatalog.all
                .filter { it.canEdit && set.entries[it.key] == null && !draft.contains(it.key) }
                .filter { keyword.isEmpty() || it.label.contains(keyword) || it.key.full.contains(keyword, ignoreCase = true) }
        }

    /** 当前选中的随机填充预设。 */
    val randomFillPreset: Preset? get() = presets.firstOrNull { it.id == randomFillPresetId }

    /** 随机填充可勾选的字段：预设里会浮动的那些（固定值照写，不参与勾选）。 */
    val randomFillCandidates: List<FieldSpec>
        get() = randomFillPreset?.let { preset ->
            preset.randomKeys
                .sortedWith(compareBy({ orderOf(it) }, { it.namespace }, { it.name }))
                .mapNotNull { FieldCatalog.spec(it) }
                .filter { it.canEdit }
        }.orEmpty()

    val randomFillReady: Boolean get() = randomFillPreset != null && randomFillKeys.isNotEmpty()

    /** 预设按类别分组，弹层分节展示（设备 / 位置 / 时间 / 混合）。 */
    val presetsByKind: List<Pair<PresetKind, List<Preset>>>
        get() = PresetKind.entries.mapNotNull { kind ->
            presets.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it }
        }

    val editingSpec: FieldSpec? get() = editing?.let { FieldCatalog.spec(it) }

    val editingLabel: String? get() = editing?.let { editingSpec?.label ?: it.full }

    val editingKind: InputKind get() = EditFieldInput.kindOf(editingSpec)

    val editingHint: String get() = EditFieldInput.hint(editingSpec)

    /** 弹层里「恢复原值」要恢复成什么：源文件的值（只读展示）。 */
    val editingOriginal: String?
        get() = editing?.let { key ->
            metadata?.get(key)?.let { TagValueFormatter.format(it, FieldCatalog.spec(key)) }
        }

    /** 该字段在当前草稿里是否已被改过（弹层据此决定要不要显示「恢复原值」）。 */
    val editingChanged: Boolean get() = editing?.let { draft.contains(it) } == true

    // ---------- 状态迁移 ----------

    fun withLoading(uri: String): EditUiState = copy(
        uri = uri,
        isLoading = true,
        isExporting = false,
        error = null,
        errorDetail = null,
        metadata = null,
        origins = emptyMap(),
        draft = EditDraft.EMPTY,
        query = "",
        editing = null,
        input = "",
        inputError = null,
        message = null,
        verifySummary = null,
    )

    fun withLoaded(
        source: SourceInfo,
        metadata: MetadataSet,
        origins: Map<TagKey, List<String>> = emptyMap(),
    ): EditUiState = copy(
        source = source,
        metadata = metadata,
        origins = origins,
        isLoading = false,
    )

    fun withError(error: PictError, detail: String? = null): EditUiState = copy(
        isLoading = false,
        isApplying = false,
        isExporting = false,
        error = error,
        errorDetail = detail,
    )

    fun withQuery(query: String): EditUiState = copy(query = query)

    // ---------- 预设与随机填充的状态迁移（docs/07 T3.7/T3.9） ----------

    fun withPresets(presets: List<Preset>, issues: List<String> = emptyList()): EditUiState =
        copy(presets = presets, presetIssues = issues)

    fun openPresets(): EditUiState = copy(showPresets = true)

    fun closePresets(): EditUiState = copy(showPresets = false)

    fun setPresetOverwrite(overwrite: Boolean): EditUiState = copy(presetOverwrite = overwrite)

    fun openAddField(): EditUiState = copy(showAddField = true)

    fun closeAddField(): EditUiState = copy(showAddField = false, addFieldQuery = "")

    fun withAddFieldQuery(query: String): EditUiState = copy(addFieldQuery = query)

    /** 打开随机填充弹层：默认选中第一个预设，并勾上它全部会浮动的字段。 */
    fun openRandomFill(preset: Preset?): EditUiState {
        val chosen = preset ?: presets.firstOrNull()
        return copy(
            showRandomFill = true,
            randomFillPresetId = chosen?.id,
            randomFillKeys = chosen?.randomKeys.orEmpty(),
            randomFillSeed = if (randomFillSeed == 0L) DEFAULT_RANDOM_SEED else randomFillSeed,
        )
    }

    fun closeRandomFill(): EditUiState = copy(showRandomFill = false)

    /** 换取值预设：勾选集合跟着换成新预设的字段，避免留下不属于它的键。 */
    fun withRandomFillPreset(preset: Preset): EditUiState = copy(
        randomFillPresetId = preset.id,
        randomFillKeys = preset.randomKeys,
    )

    fun toggleRandomFillKey(key: TagKey): EditUiState = copy(
        randomFillKeys = if (key in randomFillKeys) randomFillKeys - key else randomFillKeys + key,
    )

    fun withRandomFillSeed(seed: Long): EditUiState = copy(randomFillSeed = seed)

    /**
     * 把一次填充的结果并入草稿（套用预设与随机填充共用）。
     *
     * 只并入**真正变化**的键：与源文件相同的值不进草稿，否则「应用 N 项」的 N 会虚高，
     * 用户也会以为自己改了什么。跳过与保留都如实汇报——静默跳过比报错更坑。
     */
    fun withPresetFill(title: String, fill: PresetResolver.Fill): EditUiState {
        val values = fill.changedKeys.mapNotNull { key -> fill.target[key]?.let { key to it } }.toMap()
        val notes = buildList {
            if (fill.keptKeys.isNotEmpty()) add("保留 ${fill.keptKeys.size} 项已有值")
            if (fill.skipped.isNotEmpty()) {
                add("跳过 ${fill.skipped.size} 项（${fill.skipped.take(SKIP_REPORT_LIMIT).joinToString("；")}）")
            }
        }
        val detail = if (notes.isEmpty()) "" else "：${notes.joinToString("，")}"
        return copy(
            draft = draft.setAll(values),
            showPresets = false,
            showRandomFill = false,
            message = EditMessage(
                text = if (values.isEmpty()) {
                    "「$title」没有可填的字段$detail"
                } else {
                    "已套用「$title」：${values.size} 项进草稿$detail"
                },
                isError = values.isEmpty(),
            ),
        )
    }

    /** 打开单字段编辑弹层；预填「当前草稿值，没有则源值」的原始字面量。 */
    fun openEditor(key: TagKey): EditUiState {
        val current = draft.of(key)
        val text = when (current) {
            is DraftValue.Value -> EditFieldInput.prefill(current.value)
            DraftValue.Cleared -> ""
            null -> EditFieldInput.prefill(metadata?.get(key))
        }
        return copy(editing = key, input = text, inputError = null)
    }

    /** 从搜索结果里把一个目录字段补进这个文件。 */
    fun beginAdd(spec: FieldSpec): EditUiState = copy(editing = spec.key, input = "", inputError = null)

    /** 关掉弹层、丢掉没提交的输入（返回键 / 点空白都走这里）。 */
    fun closeEditor(): EditUiState = copy(editing = null, input = "", inputError = null)

    fun withInput(input: String): EditUiState = copy(input = input, inputError = null)

    /**
     * 确认弹层输入：合法则写进草稿并关闭，不合法则保持打开并给出原因。
     * 空输入 = 清除字段（沿用 [FieldInput] 的语义）。
     */
    fun commitEditor(): EditUiState {
        val key = editing ?: return this
        return when (val parsed = EditFieldInput.parse(input, FieldCatalog.spec(key))) {
            is FieldInput.Invalid -> copy(inputError = parsed.reason)
            FieldInput.Clear -> copy(
                draft = draft.clear(key),
                editing = null,
                input = "",
                inputError = null,
            )

            is FieldInput.Value -> copy(
                draft = draft.set(key, parsed.value),
                editing = null,
                input = "",
                inputError = null,
            )
        }
    }

    fun revertField(key: TagKey): EditUiState = copy(
        draft = draft.revert(key),
        editing = if (editing == key) null else editing,
        input = if (editing == key) "" else input,
        inputError = if (editing == key) null else inputError,
    )

    fun revertAll(): EditUiState = copy(
        draft = draft.revertAll(),
        showPreview = false,
        confirmDiscard = false,
    )

    fun requestDiscard(): EditUiState = copy(confirmDiscard = true)

    fun cancelDiscard(): EditUiState = copy(confirmDiscard = false)

    fun openPreview(): EditUiState = copy(showPreview = true)

    fun closePreview(): EditUiState = copy(showPreview = false)

    fun withApplying(): EditUiState = copy(isApplying = true, message = null)

    fun withApplied(verifySummary: String? = null): EditUiState = copy(
        isApplying = false,
        draft = EditDraft.EMPTY,
        showPreview = false,
        verifySummary = verifySummary,
    )

    /**
     * 导出开始 / 结束。
     *
     * 与「应用」的一处关键差别：导出结束**不清草稿**。导出的那份副本不是当前编辑对象，
     * 源文件还没保存，草稿得留着让用户接着决定「应用」还是「放弃」。
     */
    fun withExporting(): EditUiState = copy(isExporting = true, message = null)

    fun withExportFinished(): EditUiState = copy(isExporting = false)

    fun withMessage(text: String, isError: Boolean = false): EditUiState =
        copy(message = EditMessage(text, isError))

    fun consumeMessage(): EditUiState = copy(message = null)

    /** 一行字段：展示值、原始值、可编辑性与未保存态。 */
    private fun row(key: TagKey, set: MetadataSet): EditFieldRow {
        val spec = FieldCatalog.spec(key)
        val sourceValue = set[key]
        val draftValue = draft.of(key)
        val value = when (draftValue) {
            is DraftValue.Value -> draftValue.value
            DraftValue.Cleared -> null
            null -> sourceValue
        }
        val dirty = draftValue != null && key in dirtyKeys
        val display = when {
            value != null -> TagValueFormatter.format(value, spec)
            draftValue == DraftValue.Cleared -> CLEARED_DISPLAY
            else -> "—"
        }
        return EditFieldRow(
            key = key,
            label = spec?.label ?: key.full,
            display = display,
            raw = EditFieldInput.prefill(value),
            editable = spec?.canEdit == true,
            dirty = dirty,
            note = when {
                spec == null -> UNREGISTERED_NOTE
                !spec.canEdit -> spec.writability.label
                else -> spec.note
            },
        )
    }

    companion object {
        const val UNREGISTERED_TITLE = "其他字段"
        const val UNREGISTERED_NOTE = "未登记字段，只看不改"
        const val CLEARED_DISPLAY = "（清除）"
        const val ADDITION_LIMIT = 6

        /** 填充汇报里最多列几条跳过原因（再多就刷屏了，完整内容在日志里）。 */
        const val SKIP_REPORT_LIMIT = 3

        /** 默认种子：固定值，让「第一次随机填充」在同一张图上可复现（T3.6）。 */
        const val DEFAULT_RANDOM_SEED = AppSettings.DEFAULT_RANDOM_SEED
    }
}
