package com.pict.metatool.ui.detail

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情页元数据搜索（docs/07 T1.11）。 */
class MetadataSearchTest {

    private fun set(vararg entries: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = SourceInfo("IMG_0001.jpg", "image/jpeg", 2_048_000L, ImageFormatHint.JPEG),
        entries = entries.associate { (key, value) -> TagKey.of(key) to value },
    )

    private val sample = set(
        "EXIF:Make" to TagValue.Text("Apple"),
        "EXIF:Model" to TagValue.Text("iPhone 16 Pro"),
        "EXIF:FNumber" to TagValue.RationalValue(Rational(178, 100)),
        "GPS:GPSLatitude" to TagValue.RationalList(listOf(Rational(31, 1))),
        "EXIF:SomeUnknownTag" to TagValue.Text("神秘值"),
    )

    @Test
    fun `空白查询返回空列表`() {
        assertTrue(MetadataSearch.query(sample, "").isEmpty())
        assertTrue(MetadataSearch.query(sample, "   ").isEmpty())
    }

    @Test
    fun `按中文标签匹配`() {
        val hits = MetadataSearch.query(sample, "制造商")

        assertEquals(listOf("EXIF:Make"), hits.map { it.row.key.full })
    }

    @Test
    fun `按标签名匹配且不区分大小写`() {
        val hits = MetadataSearch.query(sample, "model")

        assertEquals(listOf("EXIF:Model"), hits.map { it.row.key.full })
    }

    @Test
    fun `按值匹配且不区分大小写`() {
        val hits = MetadataSearch.query(sample, "APPLE")

        assertEquals(listOf("EXIF:Make"), hits.map { it.row.key.full })
    }

    @Test
    fun `展示值与原始值都参与匹配`() {
        val hits = MetadataSearch.query(sample, "1.78")

        assertEquals(listOf("EXIF:FNumber"), hits.map { it.row.key.full })
    }

    @Test
    fun `目录外的键也能搜到并归入其他`() {
        val hits = MetadataSearch.query(sample, "神秘")

        assertEquals(1, hits.size)
        assertEquals("其他", hits[0].section)
        assertEquals("EXIF:SomeUnknownTag", hits[0].row.key.full)
    }

    @Test
    fun `目录内命中按字段目录顺序排列`() {
        val hits = MetadataSearch.query(sample, "e")
        val keys = hits.map { it.row.key.full }

        assertTrue("Make 应排在 Model 之前：$keys", keys.indexOf("EXIF:Make") < keys.indexOf("EXIF:Model"))
        val order = FieldCatalog.all.map { it.key.full }
        assertTrue("命中项都应来自字段目录", keys.filter { it != "EXIF:SomeUnknownTag" }.all { it in order })
    }

    @Test
    fun `没命中时返回空列表`() {
        assertTrue(MetadataSearch.query(sample, "完全不存在的词").isEmpty())
    }

    @Test
    fun `命中行的展示值来自格式化器`() {
        val hit = MetadataSearch.query(sample, "光圈").single()

        assertEquals("EXIF:FNumber", hit.row.key.full)
        assertEquals("1.78", hit.row.rawValue)
        assertTrue("可编辑字段保留铅笔标记", hit.row.canEdit)
    }
}
