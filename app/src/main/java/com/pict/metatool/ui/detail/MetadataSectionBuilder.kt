package com.pict.metatool.ui.detail

import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue

/** 详情页的一行字段。[rawValue] 供复制/编辑回填，[canEdit] 决定是否画铅笔图标。 */
data class MetadataRow(
    val key: TagKey,
    val label: String,
    val value: String,
    val rawValue: String,
    val canEdit: Boolean,
    val origin: String? = null,
)

/** 一个分组：组名 + 行列表。 */
data class MetadataSection(val title: String, val rows: List<MetadataRow>)

/**
 * 把 [MetadataSet] 摊成某页的分组行（纯函数，可 JVM 单测）。
 *
 * - 已知字段走 [FieldCatalog]：顺序 = 附录 A 顺序，标签取中文名，可写性决定铅笔图标；
 * - 空值字段不显示（读到的键就渲染，没读到就没有这行）；
 * - **不在字段目录里的键**收进「其他」分组，按 [tabFor] 归到对应页，
 *   保证读取器多读到的标签在界面上不会凭空消失（它们标为只读——写入层没有对应规格）。
 */
object MetadataSectionBuilder {

    const val OTHER_TITLE = "其他"

    fun build(
        set: MetadataSet,
        tab: DetailTab,
        origins: Map<TagKey, List<String>> = emptyMap(),
    ): List<MetadataSection> {
        val sections = tab.groups.mapNotNull { group ->
            val rows = FieldCatalog.group(group).mapNotNull { spec ->
                set[spec.key]?.let { value ->
                    metadataRow(spec.key, spec.label, value, spec.canEdit, origins)
                }
            }
            if (rows.isEmpty()) null else MetadataSection(group.label, rows)
        }

        val unknownRows = set.entries.keys
            .filter { FieldCatalog.spec(it) == null && tabFor(it) == tab }
            .sortedBy { it.full }
            .mapNotNull { key ->
                set[key]?.let { value ->
                    metadataRow(key, key.full, value, canEdit = false, origins = origins)
                }
            }

        return if (unknownRows.isEmpty()) sections else sections + MetadataSection(OTHER_TITLE, unknownRows)
    }

    /** 目录外的键按命名空间归页：`GPS:*` → GPS 页，`XMP:*` → XMP 页，其余（EXIF/IPTC/未知）→ EXIF 页。 */
    fun tabFor(key: TagKey): DetailTab = when (key.namespace.uppercase()) {
        "GPS" -> DetailTab.GPS
        "XMP" -> DetailTab.XMP
        else -> DetailTab.EXIF
    }
}

/**
 * 由「键 + 值」造一行：展示值按字段目录的规格格式化，目录外字段退化为通用格式。
 *
 * 分组页与搜索页（T1.11）共用同一套构造，免得两处对展示值 / 复制值的处理跑偏。
 */
internal fun metadataRow(
    key: TagKey,
    label: String,
    value: TagValue,
    canEdit: Boolean,
    origins: Map<TagKey, List<String>> = emptyMap(),
): MetadataRow = MetadataRow(
    key = key,
    label = label,
    value = TagValueFormatter.format(value, FieldCatalog.spec(key)),
    rawValue = TagValueFormatter.raw(value),
    canEdit = canEdit,
    origin = origins[key]?.lastOrNull(),
)
