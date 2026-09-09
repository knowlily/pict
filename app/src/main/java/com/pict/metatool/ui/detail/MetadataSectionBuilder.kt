package com.pict.metatool.ui.detail

import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey

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
                    MetadataRow(
                        key = spec.key,
                        label = spec.label,
                        value = TagValueFormatter.format(value, spec),
                        rawValue = TagValueFormatter.raw(value),
                        canEdit = spec.canEdit,
                        origin = origins[spec.key]?.lastOrNull(),
                    )
                }
            }
            if (rows.isEmpty()) null else MetadataSection(group.label, rows)
        }

        val unknownRows = set.entries.keys
            .filter { FieldCatalog.spec(it) == null && tabFor(it) == tab }
            .sortedBy { it.full }
            .mapNotNull { key ->
                set[key]?.let { value ->
                    MetadataRow(
                        key = key,
                        label = key.full,
                        value = TagValueFormatter.format(value, null),
                        rawValue = TagValueFormatter.raw(value),
                        canEdit = false,
                        origin = origins[key]?.lastOrNull(),
                    )
                }
            }

        return if (unknownRows.isEmpty()) {
            sections
        } else {
            sections + MetadataSection(OTHER_TITLE, unknownRows)
        }
    }

    /** 目录外的键按命名空间归页：`GPS:*` → GPS 页，`XMP:*` → XMP 页，其余（EXIF/IPTC/未知）→ EXIF 页。 */
    fun tabFor(key: TagKey): DetailTab = when (key.namespace.uppercase()) {
        "GPS" -> DetailTab.GPS
        "XMP" -> DetailTab.XMP
        else -> DetailTab.EXIF
    }
}
