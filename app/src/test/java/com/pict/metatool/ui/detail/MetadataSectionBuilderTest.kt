package com.pict.metatool.ui.detail

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情页分组渲染（docs/07 T1.10）。 */
class MetadataSectionBuilderTest {

    private fun set(vararg entries: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = SourceInfo("IMG_0001.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG),
        entries = entries.associate { (key, value) -> TagKey.of(key) to value },
    )

    @Test
    fun `EXIF 页按分组顺序给出，行序跟随字段目录`() {
        val metadata = set(
            "EXIF:Model" to TagValue.Text("iPhone 16 Pro"),
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:ImageDescription" to TagValue.Text("测试图"),
        )

        val sections = MetadataSectionBuilder.build(metadata, DetailTab.EXIF)

        assertEquals(listOf("基础", "相机"), sections.map { it.title })
        // 目录里 Make 在 Model 之前，传入顺序相反也应还原成目录顺序
        assertEquals(listOf("EXIF:Make", "EXIF:Model"), sections[1].rows.map { it.key.full })
        assertEquals("制造商", sections[1].rows[0].label)
    }

    @Test
    fun `没读到的字段不产生行，空分组不出现`() {
        val metadata = set("EXIF:Make" to TagValue.Text("Apple"))

        val sections = MetadataSectionBuilder.build(metadata, DetailTab.EXIF)

        assertEquals(1, sections.size)
        assertEquals(listOf("制造商"), sections[0].rows.map { it.label })
    }

    @Test
    fun `可写性映射到 canEdit 与铅笔图标依据`() {
        val metadata = set(
            "EXIF:Make" to TagValue.Text("Apple"),
            "IPTC:2:5" to TagValue.Text("对象名称"),
        )

        val rows = MetadataSectionBuilder.build(metadata, DetailTab.EXIF).flatMap { it.rows }
        val byKey = rows.associateBy { it.key.full }

        assertTrue("制造商可写", byKey.getValue("EXIF:Make").canEdit)
        assertFalse("IPTC 字段只读", byKey.getValue("IPTC:2:5").canEdit)
    }

    @Test
    fun `文件页只列结构字段`() {
        val metadata = set(
            "EXIF:ImageWidth" to TagValue.IntValue(4032),
            "EXIF:ImageLength" to TagValue.IntValue(3024),
            "EXIF:Make" to TagValue.Text("Apple"),
        )

        val sections = MetadataSectionBuilder.build(metadata, DetailTab.FILE)

        assertEquals(listOf("文件结构"), sections.map { it.title })
        assertEquals(listOf("EXIF:ImageWidth", "EXIF:ImageLength"), sections[0].rows.map { it.key.full })
        assertFalse("结构字段只读", sections[0].rows.any { it.canEdit })
    }

    @Test
    fun `GPS 页只列位置分组`() {
        val metadata = set(
            "GPS:GPSLatitude" to TagValue.RationalList(listOf(Rational(31, 1))),
            "EXIF:Make" to TagValue.Text("Apple"),
        )

        val sections = MetadataSectionBuilder.build(metadata, DetailTab.GPS)

        assertEquals(listOf("位置"), sections.map { it.title })
        assertEquals("纬度", sections[0].rows.single().label)
    }

    @Test
    fun `目录外的键进其他分组`() {
        val metadata = set(
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:SomeNewTag" to TagValue.Text("未收录"),
            "EXIF:AnotherTag" to TagValue.IntValue(7),
        )

        val sections = MetadataSectionBuilder.build(metadata, DetailTab.EXIF)
        val other = sections.single { it.title == MetadataSectionBuilder.OTHER_TITLE }

        assertEquals(listOf("EXIF:AnotherTag", "EXIF:SomeNewTag"), other.rows.map { it.key.full })
        assertTrue("目录外字段按只读处理", other.rows.none { it.canEdit })
    }

    @Test
    fun `目录外的键按命名空间归页`() {
        assertEquals(DetailTab.GPS, MetadataSectionBuilder.tabFor(TagKey.of("GPS:SomeNewTag")))
        assertEquals(DetailTab.XMP, MetadataSectionBuilder.tabFor(TagKey.of("XMP:photoshop:SomeNewTag")))
        assertEquals(DetailTab.EXIF, MetadataSectionBuilder.tabFor(TagKey.of("EXIF:SomeNewTag")))
        assertEquals(DetailTab.EXIF, MetadataSectionBuilder.tabFor(TagKey.of("IPTC:Unknown")))
    }

    @Test
    fun `其他分组只出现在对应页`() {
        val metadata = set("GPS:SomeNewTag" to TagValue.RationalValue(Rational(90, 1)))

        val gpsSections = MetadataSectionBuilder.build(metadata, DetailTab.GPS)
        val exifSections = MetadataSectionBuilder.build(metadata, DetailTab.EXIF)

        assertEquals(MetadataSectionBuilder.OTHER_TITLE, gpsSections.single().title)
        assertTrue("EXIF 页不该出现 GPS 的目录外字段", exifSections.isEmpty())
    }

    @Test
    fun `来源标注取最后一个读取器`() {
        val metadata = set("EXIF:Make" to TagValue.Text("Apple"))
        val key = TagKey.of("EXIF:Make")

        val rows = MetadataSectionBuilder.build(
            set = metadata,
            tab = DetailTab.EXIF,
            origins = mapOf(key to listOf("extractor", "exif")),
        ).flatMap { it.rows }

        assertEquals("exif", rows.single().origin)
    }

    @Test
    fun `行里同时给出展示值、复制值与标签`() {
        val metadata = set("EXIF:Make" to TagValue.Text("Apple"))

        val row = MetadataSectionBuilder.build(metadata, DetailTab.EXIF).flatMap { it.rows }.single()

        assertEquals("制造商", row.label)
        assertEquals("Apple", row.value)
        assertEquals("Apple", row.rawValue)
    }

    @Test
    fun `空元数据不产生分组`() {
        assertTrue(MetadataSectionBuilder.build(MetadataSet.empty(), DetailTab.EXIF).isEmpty())
    }

    @Test
    fun `概览页不按分组渲染`() {
        val metadata = set("EXIF:Make" to TagValue.Text("Apple"))
        assertTrue(MetadataSectionBuilder.build(metadata, DetailTab.OVERVIEW).isEmpty())
    }

    @Test
    fun `每个分组标题都来自字段目录`() {
        val metadata = set(
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:DateTimeOriginal" to TagValue.Timestamp(java.time.LocalDateTime.of(2026, 9, 9, 17, 38)),
            "GPS:GPSLatitude" to TagValue.RationalList(listOf(Rational(31, 1))),
            "XMP:dc:title" to TagValue.Text("标题"),
        )

        val titles = DetailTab.entries
            .filter { it != DetailTab.OVERVIEW }
            .flatMap { tab -> MetadataSectionBuilder.build(metadata, tab).map { it.title } }
            .toSet()

        val catalogLabels = FieldGroup.entries.map { it.label }.toSet()
        val unknown = titles - catalogLabels - MetadataSectionBuilder.OTHER_TITLE
        assertTrue("分组标题必须是目录里的组名：$unknown", unknown.isEmpty())
    }

    @Test
    fun `字段目录顺序被尊重`() {
        val metadata = set(
            "EXIF:Artist" to TagValue.Text("作者"),
            "EXIF:ImageDescription" to TagValue.Text("描述"),
        )
        val rows = MetadataSectionBuilder.build(metadata, DetailTab.EXIF).flatMap { it.rows }

        val expected = FieldCatalog.group(FieldGroup.BASIC)
            .filter { it.key.full == "EXIF:ImageDescription" || it.key.full == "EXIF:Artist" }
            .map { it.key.full }
        assertEquals(expected, rows.map { it.key.full })
    }
}
