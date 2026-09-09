package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ExifSegmentBudget] 的估算与丢弃顺序（docs/07 T2.5）。 */
class ExifSegmentBudgetTest {

    private val make = TagKey.of("EXIF:Make")
    private val userComment = TagKey.of("EXIF:UserComment")
    private val thumbnail = TagKey.of("EXIF:Thumbnail")
    private val xmpTitle = TagKey.of("XMP:dc:title")

    private fun metaSet(vararg entries: Pair<TagKey, TagValue>): MetadataSet =
        MetadataSet(SOURCE, entries.toMap())

    /** 造一个占 [bytes] 字节的二进制值，用来把段撑爆。 */
    private fun big(bytes: Int) = TagValue.Binary("", bytes)

    @Test
    fun `估算含固定段头开销`() {
        assertEquals(200, ExifSegmentBudget.estimate(emptyMap()))
    }

    @Test
    fun `小字段集不触发丢弃`() {
        val target = metaSet(
            make to TagValue.Text("PictTool"),
            userComment to TagValue.Text("注释"),
        )

        val fit = ExifSegmentBudget.fit(target)

        assertFalse(fit.hasDropped)
        assertFalse(fit.overBudget)
        assertEquals(target.entries, fit.kept.entries)
    }

    @Test
    fun `超限时先丢缩略图，XMP 与非关键字段保留`() {
        val target = metaSet(
            make to TagValue.Text("PictTool"),
            thumbnail to big(70_000),
            xmpTitle to TagValue.Text("标题"),
            userComment to TagValue.Text("注释"),
        )

        val fit = ExifSegmentBudget.fit(target)

        assertEquals(setOf(thumbnail), fit.dropped.keys)
        assertEquals(DropTier.THUMBNAIL, fit.dropped.getValue(thumbnail))
        assertTrue(fit.kept.entries.containsKey(xmpTitle))
        assertTrue(fit.kept.entries.containsKey(userComment))
        assertFalse(fit.overBudget)
    }

    @Test
    fun `丢完缩略图还超限才动 XMP`() {
        val target = metaSet(
            make to TagValue.Text("PictTool"),
            thumbnail to big(20_000),
            xmpTitle to TagValue.Text("x".repeat(50_000)),
            userComment to TagValue.Text("y".repeat(20_000)),
        )

        val fit = ExifSegmentBudget.fit(target)

        assertEquals(setOf(thumbnail, xmpTitle), fit.dropped.keys)
        assertEquals(DropTier.XMP, fit.dropped.getValue(xmpTitle))
        assertTrue("非关键字段此时还轮不到丢", fit.kept.entries.containsKey(userComment))
        assertFalse(fit.overBudget)
    }

    @Test
    fun `三级都不够时最后丢非关键字段`() {
        val target = metaSet(
            make to TagValue.Text("PictTool"),
            thumbnail to big(20_000),
            xmpTitle to TagValue.Text("x".repeat(30_000)),
            userComment to TagValue.Text("y".repeat(70_000)),
        )

        val fit = ExifSegmentBudget.fit(target)

        assertEquals(setOf(thumbnail, xmpTitle, userComment), fit.dropped.keys)
        assertEquals(DropTier.NON_CRITICAL, fit.dropped.getValue(userComment))
        assertTrue("关键字段留着", fit.kept.entries.containsKey(make))
        assertFalse(fit.overBudget)
    }

    @Test
    fun `关键字段自己就超限时不丢，只报超预算`() {
        val target = metaSet(make to big(200_000))

        val fit = ExifSegmentBudget.fit(target)

        assertTrue(fit.kept.entries.containsKey(make))
        assertFalse(fit.hasDropped)
        assertTrue(fit.overBudget)
    }

    @Test
    fun `中文按 UTF-8 计长不低估`() {
        val value = TagValue.Text("中".repeat(1000))

        // 一个汉字 3 字节，按字符数算会少三倍
        assertEquals(12 + 3001, ExifSegmentBudget.sizeOf(userComment, value))
    }

    @Test
    fun `可自定义上限`() {
        val target = metaSet(userComment to TagValue.Text("注释"))
        assertEquals(220, ExifSegmentBudget.estimate(target.entries))

        assertFalse(ExifSegmentBudget.fit(target, limit = 220).hasDropped)
        assertEquals(setOf(userComment), ExifSegmentBudget.fit(target, limit = 219).dropped.keys)
    }

    @Test
    fun `fit 不修改入参`() {
        val target = metaSet(
            make to TagValue.Text("PictTool"),
            thumbnail to big(70_000),
        )
        val snapshot = target.entries.toMap()

        ExifSegmentBudget.fit(target)

        assertEquals(snapshot, target.entries)
    }

    private companion object {
        val SOURCE = SourceInfo("sample.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG)
    }
}
