package com.pict.metatool.ui.detail

import com.pict.metatool.domain.model.FieldGroup

/**
 * 详情页 Tab（docs/06 §3.2：概览 / EXIF / GPS / XMP / 文件）。
 *
 * [groups] 是该页展示的字段分组，顺序即页内顺序；概览页不按分组渲染（见 [OverviewBuilder]）。
 *
 * 与文档的差异：docs/06 没有独立 IPTC 页，[EXIF] 顺带收纳 [FieldGroup.IPTC]，
 * 否则 IPTC 字段在界面上无处可见。
 */
enum class DetailTab(val label: String, val groups: List<FieldGroup>) {
    OVERVIEW("概览", emptyList()),
    EXIF(
        "EXIF",
        listOf(
            FieldGroup.BASIC,
            FieldGroup.CAMERA,
            FieldGroup.EXPOSURE,
            FieldGroup.TIME,
            FieldGroup.IPTC,
        ),
    ),
    GPS("GPS", listOf(FieldGroup.LOCATION)),
    XMP("XMP", listOf(FieldGroup.XMP)),
    FILE("文件", listOf(FieldGroup.FILE)),
    ;
}
