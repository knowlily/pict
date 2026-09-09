package com.pict.metatool.ui.detail

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey

/** 一条搜索命中：字段行 + 它所属的分组名。 */
data class SearchHit(val section: String, val row: MetadataRow)

/**
 * 详情页的元数据搜索（docs/07 T1.11：字段名 / 值模糊匹配；纯函数，可 JVM 单测）。
 *
 * 匹配范围：
 * - 字段名：中文标签、标签全名（如 `EXIF:Make`）、标签短名（如 `Make`）；
 * - 值：展示值与原始值都参与，所以搜「1.78」能命中光圈（展示为 `f/1.78`）。
 *
 * 结果顺序 = 字段目录顺序（附录 A）；目录外的键排在最后、按标签名升序，归入「其他」。
 * 查询串为空白时返回空列表 —— 搜索框空着不该把整张图倒出来。
 */
object MetadataSearch {

    fun query(
        set: MetadataSet,
        query: String,
        origins: Map<TagKey, List<String>> = emptyMap(),
    ): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val known = FieldCatalog.all.mapNotNull { spec ->
            val value = set[spec.key] ?: return@mapNotNull null
            val row = metadataRow(spec.key, spec.label, value, spec.canEdit, origins)
            if (row.matches(q)) SearchHit(spec.group.label, row) else null
        }

        val unknown = set.entries.keys
            .filter { FieldCatalog.spec(it) == null }
            .sortedBy { it.full }
            .mapNotNull { key ->
                val value = set[key] ?: return@mapNotNull null
                val row = metadataRow(key, key.full, value, canEdit = false, origins = origins)
                if (row.matches(q)) SearchHit(MetadataSectionBuilder.OTHER_TITLE, row) else null
            }

        return known + unknown
    }

    /** 字段名与值都不区分大小写做子串匹配。 */
    private fun MetadataRow.matches(query: String): Boolean {
        val lower = query.lowercase()
        return label.lowercase().contains(lower) ||
            key.full.lowercase().contains(lower) ||
            key.name.lowercase().contains(lower) ||
            value.lowercase().contains(lower) ||
            rawValue.lowercase().contains(lower)
    }
}
