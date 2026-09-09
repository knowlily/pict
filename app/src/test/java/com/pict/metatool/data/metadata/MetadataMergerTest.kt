package com.pict.metatool.data.metadata

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

/** 多来源合并与差集（docs/07 T1.3）。 */
class MetadataMergerTest {

    private fun set(vararg pairs: Pair<String, TagValue>, name: String = "a.jpg"): MetadataSet =
        MetadataSet(
            source = SourceInfo(name, "image/jpeg", 1024, ImageFormatHint.JPEG),
            entries = pairs.associate { TagKey.of(it.first) to it.second },
        )

    private val make = "EXIF:Make"

    @Test
    fun `后传入的来源覆盖先传入的`() {
        val exif = set(make to TagValue.Text("Apple"))
        val extractor = set(make to TagValue.Text("Apple Inc."))
        val merged = MetadataMerger.merge(listOf(exif, extractor))
        assertEquals("Apple Inc.", (merged[make] as TagValue.Text).value)
    }

    @Test
    fun `合并保留双方独有字段`() {
        val a = set(make to TagValue.Text("Apple"))
        val b = set("EXIF:Model" to TagValue.Text("iPhone 16 Pro"))
        val merged = MetadataMerger.merge(listOf(a, b))
        assertEquals(2, merged.size)
        assertEquals("iPhone 16 Pro", (merged["EXIF:Model"] as TagValue.Text).value)
    }

    @Test
    fun `空列表回退到 base 或空集`() {
        val base = set(make to TagValue.Text("Apple"))
        assertEquals(base.size, MetadataMerger.merge(emptyList(), base).size)
        assertTrue(MetadataMerger.merge(emptyList()).isEmpty)
    }

    @Test
    fun `差集区分仅左 仅右 与变更`() {
        val a = set(
            make to TagValue.Text("Apple"),
            "EXIF:Model" to TagValue.Text("iPhone 15"),
            "EXIF:Orientation" to TagValue.IntValue(1),
        )
        val b = set(
            make to TagValue.Text("Apple"),
            "EXIF:Model" to TagValue.Text("iPhone 16 Pro"),
            "EXIF:FNumber" to TagValue.RationalValue(Rational(178, 100)),
        )
        val diff = MetadataMerger.diff(a, b)
        assertEquals(setOf(TagKey.of("EXIF:Orientation")), diff.onlyInFirst)
        assertEquals(setOf(TagKey.of("EXIF:FNumber")), diff.onlyInSecond)
        assertEquals(setOf(TagKey.of("EXIF:Model")), diff.changed)
        assertEquals(3, diff.total)
        assertFalse(diff.isEmpty)
        assertTrue(diff.summary().contains("3"))
    }

    @Test
    fun `无差异时差集为空`() {
        val a = set(make to TagValue.Text("Apple"))
        val diff = MetadataMerger.diff(a, a)
        assertTrue(diff.isEmpty)
        assertEquals("无差异", diff.summary())
    }

    @Test
    fun `MetadataSet 自带增删改查`() {
        val base = MetadataSet.empty(ImageFormatHint.JPEG)
        assertTrue(base.isEmpty)
        val one = base.with(TagKey.of(make), TagValue.Text("Apple"))
        assertEquals(1, one.size)
        assertEquals("Apple", (one[make] as TagValue.Text).value)
        assertEquals("Apple", (one["EXIF:Make"] as TagValue.Text).value)
        assertEquals(1, one.inGroup(com.pict.metatool.domain.model.FieldGroup.CAMERA).size)
        assertTrue(one.without(TagKey.of(make)).isEmpty)
    }

    @Test
    fun `变更键与共同键统计正确`() {
        val a = set(
            make to TagValue.Text("Apple"),
            "EXIF:Model" to TagValue.Text("iPhone 15"),
        )
        val b = set(
            make to TagValue.Text("Apple"),
            "EXIF:Model" to TagValue.Text("iPhone 16 Pro"),
        )
        assertEquals(setOf(TagKey.of("EXIF:Model")), a.changedKeys(b))
        assertEquals(setOf(TagKey.of(make)), a.commonKeys(b))
    }

    @Test
    fun `未知键名取值不抛异常`() {
        val a = set(make to TagValue.Text("Apple"))
        assertEquals(null, a.valueOrNull("非法键"))
        assertEquals(null, a.valueOrNull("EXIF:不存在"))
    }
}
