package com.pict.metatool.ui.batch

import com.pict.metatool.domain.batch.BatchDraft
import com.pict.metatool.domain.batch.BatchPreview
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.batch.ChangeKind
import com.pict.metatool.domain.batch.ItemPreview
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetSelection
import com.pict.metatool.domain.preset.UserPresetInput

/**
 * 批量页的两步（docs/06 §3.8 线框）：
 * [EDIT] 定计划 → [PREVIEW] 看这一批会发生什么。
 *
 * 刻意不做成两个路由：预览依赖草稿，草稿改一下就要重算，
 * 放一个页面里用状态切换，返回键天然回到「改一改」，也不会留下一个
 * 指向已失效计划的预览页。
 */
enum class BatchStep { EDIT, PREVIEW }

/** 预览列表的过滤（T5.5 的「过滤」）。 */
enum class PreviewFilter(val label: String) {
    ALL("全部"),
    CHANGED("有变化"),
    UNCHANGED("无变化"),
    BLOCKED("不支持"),
    ;

    fun matches(item: ItemPreview): Boolean = when (this) {
        ALL -> true
        CHANGED -> !item.isBlocked && item.changes.isNotEmpty()
        UNCHANGED -> item.isNoop
        BLOCKED -> item.isBlocked
    }
}

/** 预览统计区要显示的数（T5.5 的「统计」）。 */
data class PreviewStats(
    val files: Int,
    val changed: Int,
    val unchanged: Int,
    val blocked: Int,
    val changes: Int,
    val segments: Int,
    val added: Int,
    val modified: Int,
    val removed: Int,
    /** 改动最多的几个字段：字段名 + 命中张数。 */
    val topKeys: List<Pair<String, Int>>,
    /** 没能填 / 被拦下的原因：原因 + 项数。 */
    val skips: List<Pair<String, Int>>,
)

/**
 * 批量页状态（单向数据流：状态自 ViewModel 向下流，事件自界面向上抛）。
 *
 * [message] 存的是**已经成文的提示**而不是枚举：这里的提示都来自
 * `PictResult` 的 `detail`（例如「预设不存在」），UI 不必再翻译一遍。
 */
data class BatchUiState(
    val targets: List<BatchTarget> = emptyList(),
    val presets: List<Preset> = emptyList(),
    /** 用户自建预设（`files/presets/`）里读不动的文件问题；折成一行提示，不挡用别的预设。 */
    val userPresetIssues: List<String> = emptyList(),
    /** 「自己加一个预设」的表单（null = 没在编辑；与编辑页共用同一张 `UserPresetInput`）。 */
    val userPresetDraft: UserPresetInput? = null,
    val userPresetMessage: String? = null,
    val step: BatchStep = BatchStep.EDIT,
    val draft: BatchDraft = BatchDraft(),
    val filter: PreviewFilter = PreviewFilter.ALL,
    val preview: BatchPreview? = null,
    /** 展开看字段明细的那几项（按 URI）。 */
    val expanded: Set<String> = emptySet(),
    /** 预览进行中：已完成 → 总数；null 表示没在算。 */
    val progress: Pair<Int, Int>? = null,
    val message: String? = null,
    /** 已排队的任务 id（T5.3）：非 null = 这一批已经交给 WorkManager 了。 */
    val queuedJobId: String? = null,
    /** 排队那一刻的张数。排队之后 targets 可能被重建，文案要留住当时那个数。 */
    val queuedCount: Int = 0,
) {

    val hasTargets: Boolean get() = targets.isNotEmpty()

    /** 已选预设（分栏多选，按套用顺序排好：设备 → 位置 → 时间 → 混合）。 */
    val pickedPresets: List<Preset>
        get() = PresetSelection.orderedPresets(PresetSelection.of(draft.presetIds, presets), presets)

    /** 已选摘要，用在「用哪个预设」那一行与任务标签上。 */
    val pickedLabel: String
        get() = PresetSelection.label(PresetSelection.of(draft.presetIds, presets), presets)

    /** 自建预设的表单操作（与编辑页同一套语义）。 */
    fun openUserPresetEditor(input: UserPresetInput): BatchUiState =
        copy(userPresetDraft = input, userPresetMessage = null)

    fun closeUserPresetEditor(): BatchUiState = copy(userPresetDraft = null, userPresetMessage = null)

    fun withUserPresetMessage(text: String?): BatchUiState = copy(userPresetMessage = text)

    val isPreviewing: Boolean get() = progress != null

    val isQueued: Boolean get() = queuedJobId != null

    /**
     * 「开始执行」的门槛：有目标、有预览、还没排过队。
     *
     * 卡在「已经预览过」是刻意的（docs/06 §3.3）：计划与预览同源，
     * 有预览就意味着这份计划真算过一遍、用户也真看过将要发生什么。
     */
    val canExecute: Boolean get() = hasTargets && preview != null && !isPreviewing && !isQueued

    /** 预览按钮是否可用：计划填完了、没在算、也确实有目标。 */
    val canPreview: Boolean get() = hasTargets && draft.isReady && !isPreviewing

    val visibleItems: List<ItemPreview>
        get() = preview?.items.orEmpty().filter { filter.matches(it) }

    fun countOf(filter: PreviewFilter): Int = preview?.items.orEmpty().count { filter.matches(it) }

    val stats: PreviewStats?
        get() = preview?.let { preview ->
            val kinds = preview.changedItems
                .flatMap { it.changes }
                .groupingBy { it.kind }
                .eachCount()
            PreviewStats(
                files = preview.items.size,
                changed = preview.changedFiles,
                unchanged = preview.noopItems.size,
                blocked = preview.blockedFiles,
                changes = preview.totalChanges,
                segments = preview.segmentClearCount,
                added = kinds[ChangeKind.ADDED] ?: 0,
                modified = kinds[ChangeKind.MODIFIED] ?: 0,
                removed = kinds[ChangeKind.REMOVED] ?: 0,
                topKeys = preview.keyHistogram()
                    .take(TOP_KEYS)
                    .map { (key, count) -> (FieldCatalog.spec(key)?.label ?: key.full) to count },
                skips = preview.skipReasons().take(SKIP_REASONS),
            )
        }

    private companion object {
        const val TOP_KEYS = 3
        const val SKIP_REASONS = 3
    }
}
