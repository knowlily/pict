package com.pict.metatool.domain.batch

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.ClearSegment
import com.pict.metatool.domain.preset.MetadataRandomizer

/**
 * 批处理的预览模型（docs/07 T5.5、docs/01 FR-32）。
 *
 * 为什么单独一套模型而不是复用 `EditOutcome`：`EditOutcome` 描述的是**一次折叠**的结果，
 * 而批量预览要回答的是「这一批文件各会发生什么、有哪些被跳过、跳过是为什么」——
 * 逐项状态、字段命中统计、跳过原因归类都是它没有的。
 *
 * 设计约束（ADR-01）：本文件属 domain 层，**不依赖任何 Android API**，
 * 也不持有文件句柄或字节流——目标只用 URI 字符串标识（`android.net.Uri` 是 Android 类）。
 */

/** 批量作用对象：一个 URI + 它的来源快照。 */
data class BatchTarget(
    val uri: String,
    val info: SourceInfo,
) {

    val displayName: String get() = info.displayName

    val format: ImageFormatHint get() = info.format

    companion object {
        /** 只有 URI 与文件名时的最小构造（来源信息未知，读的时候才知道）。 */
        fun of(uri: String, displayName: String, format: ImageFormatHint = ImageFormatHint.UNKNOWN): BatchTarget =
            BatchTarget(uri, SourceInfo(displayName, null, null, format))
    }
}

/** 字段变化类型（预览表格的「类型」列）。 */
enum class ChangeKind(val label: String) {
    ADDED("新增"),
    MODIFIED("修改"),
    REMOVED("清除"),
}

/** 单个字段的变化：预览表格的一行。 */
data class FieldChange(
    val key: TagKey,
    val before: TagValue?,
    val after: TagValue?,
) {

    /** 由前后值推导，避免调用方各写一份、口径不一致。 */
    val kind: ChangeKind
        get() = when {
            before == null -> ChangeKind.ADDED
            after == null -> ChangeKind.REMOVED
            else -> ChangeKind.MODIFIED
        }
}

/**
 * 单个目标的预览结果。
 *
 * 三态刻意分开，而不是只留一个「错误」字段：
 * - [blocked] 非空 = 这项不会被执行（读不了 / 通道不支持原地写 / 计划非法），
 *   界面按原因过滤；[readable] = false 表示连源元数据都没读到。
 * - [changes] 空且未 blocked = 计划对它是空操作（例如「只填缺失」而字段都已有值）。
 */
data class ItemPreview(
    val target: BatchTarget,
    val changes: List<FieldChange> = emptyList(),
    val keptKeys: Set<TagKey> = emptySet(),
    val skipped: List<MetadataRandomizer.Skip> = emptyList(),
    val segmentClears: Set<ClearSegment> = emptySet(),
    val blocked: PictError? = null,
    val blockedDetail: String? = null,
    val readable: Boolean = true,
) {

    val isBlocked: Boolean get() = blocked != null

    val changeCount: Int get() = changes.size

    /** 既没被拦、也没变化（按规则跳过的空操作）。 */
    val isNoop: Boolean get() = !isBlocked && changes.isEmpty()
}

/**
 * 整批预览。
 *
 * [items] 与请求的目标顺序一一对应——界面表格、进度、报告都按这个顺序展示，
 * 不在引擎里重排，免得「第 3 个文件」在两处指的不是同一个。
 */
data class BatchPreview(
    val items: List<ItemPreview> = emptyList(),
) {

    val changedItems: List<ItemPreview> get() = items.filter { !it.isBlocked && it.changes.isNotEmpty() }

    val blockedItems: List<ItemPreview> get() = items.filter { it.isBlocked }

    /** 执行了但什么都没变的项（例如「只填缺失」撞上已有值）。 */
    val noopItems: List<ItemPreview> get() = items.filter { it.isNoop }

    val totalChanges: Int get() = changedItems.sumOf { it.changeCount }

    val changedFiles: Int get() = changedItems.size

    val blockedFiles: Int get() = blockedItems.size

    val hasChanges: Boolean get() = changedItems.isNotEmpty()

    /**
     * 段级清除意图的条数。**不是字段**，所以不进 [totalChanges]：
     * 混进去会让「共 N 项改动」与表格行数对不上。
     */
    val segmentClearCount: Int get() = items.sumOf { it.segmentClears.size }

    /** 字段命中排行：预览页统计区「改得最多的字段」。 */
    fun keyHistogram(): List<Pair<TagKey, Int>> =
        items.filter { !it.isBlocked }
            .flatMap { item -> item.changes.map { it.key } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<TagKey, Int>> { it.value }.thenBy { it.key.full })
            .map { it.key to it.value }

    /** 跳过原因归类：键是错误码或跳过理由，值是项数（界面用「共 N 项」）。 */
    fun skipReasons(): List<Pair<String, Int>> {
        val blocked = blockedItems.map { it.blocked?.code ?: PictError.UNKNOWN.code }
            .groupingBy { it }
            .eachCount()
        // 取每项「没能填的第一个字段」的理由，避免一个文件里的多条 Skip 把分母撑爆
        val skipped = items.filter { !it.isBlocked && it.skipped.isNotEmpty() }
            .map { item -> item.skipped.first() }
            .groupingBy { it.reason }
            .eachCount()
        return (blocked.entries + skipped.entries)
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }
}
