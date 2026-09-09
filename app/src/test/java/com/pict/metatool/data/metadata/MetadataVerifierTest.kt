package com.pict.metatool.data.metadata

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MetadataVerifier] 的比对语义（docs/07 T2.4）。 */
class MetadataVerifierTest {

    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")
    private val fNumber = TagKey.of("EXIF:FNumber")
    private val gpsLat = TagKey.of("GPS:GPSLatitude")

    private fun metaSet(vararg entries: Pair<TagKey, TagValue>): MetadataSet =
        MetadataSet(SOURCE, entries.toMap())

    @Test
    fun `字段全部落地时判定无损`() {
        val before = metaSet(make to TagValue.Text("Canon"))
        val target = metaSet(make to TagValue.Text("PictTool"), model to TagValue.Text("X100"))
        val after = metaSet(make to TagValue.Text("PictTool"), model to TagValue.Text("X100"))

        val report = MetadataVerifier.compare(before, target, after)

        assertEquals(setOf(make, model), report.matched)
        assertTrue(report.mismatched.isEmpty())
        assertTrue(report.missing.isEmpty())
        assertTrue(report.notRemoved.isEmpty())
        assertTrue(report.unexpected.isEmpty())
        assertNull("没采指纹时不该假装校验过", report.pixelsIdentical)
        assertTrue(report.isLossless)
    }

    @Test
    fun `值被改写时记录期望与实际`() {
        val target = metaSet(make to TagValue.Text("PictTool"))
        val after = metaSet(make to TagValue.Text("Canon"))

        val report = MetadataVerifier.compare(target, target, after)

        val mismatch = report.mismatched.getValue(make)
        assertEquals("PictTool", mismatch.expected)
        assertEquals("Canon", mismatch.actual)
        assertFalse(report.isLossless)
    }

    @Test
    fun `目标字段没写进去算缺失`() {
        val target = metaSet(make to TagValue.Text("PictTool"))
        val after = metaSet()

        val report = MetadataVerifier.compare(target, target, after)

        assertEquals(setOf(make), report.missing)
        assertTrue(report.matched.isEmpty())
        assertFalse(report.isLossless)
    }

    @Test
    fun `该删除的字段还在算未删除`() {
        val before = metaSet(make to TagValue.Text("Canon"), gpsLat to TagValue.RationalValue(Rational(31, 1)))
        val target = metaSet(make to TagValue.Text("Canon"))
        val after = metaSet(make to TagValue.Text("Canon"), gpsLat to TagValue.RationalValue(Rational(31, 1)))

        val report = MetadataVerifier.compare(before, target, after)

        assertEquals(setOf(gpsLat), report.notRemoved)
        assertFalse(report.isLossless)
    }

    @Test
    fun `凭空出现的字段算多出`() {
        val before = metaSet(make to TagValue.Text("Canon"))
        val target = metaSet(make to TagValue.Text("Canon"))
        val after = metaSet(make to TagValue.Text("Canon"), model to TagValue.Text("X100"))

        val report = MetadataVerifier.compare(before, target, after)

        assertEquals(setOf(model), report.unexpected)
        assertFalse(report.isLossless)
    }

    @Test
    fun `分数与小数数值相同不算改写`() {
        val target = metaSet(fNumber to TagValue.RationalValue(Rational(28, 10)))
        val after = metaSet(fNumber to TagValue.DecimalValue(2.8))

        val report = MetadataVerifier.compare(target, target, after)

        assertEquals(setOf(fNumber), report.matched)
        assertTrue(report.isLossless)
    }

    @Test
    fun `像素指纹不同判定有损`() {
        val target = metaSet(make to TagValue.Text("PictTool"))
        val after = metaSet(make to TagValue.Text("PictTool"))

        val report = MetadataVerifier.compare(target, target, after, "abc", "def")

        assertEquals(false, report.pixelsIdentical)
        assertFalse(report.isLossless)
    }

    @Test
    fun `像素指纹相同且字段全中时判定无损`() {
        val target = metaSet(make to TagValue.Text("PictTool"))
        val after = metaSet(make to TagValue.Text("PictTool"))

        val report = MetadataVerifier.compare(target, target, after, "abc", "abc")

        assertEquals(true, report.pixelsIdentical)
        assertTrue(report.isLossless)
    }

    @Test
    fun `摘要把各类差异都列出来`() {
        val before = metaSet(gpsLat to TagValue.RationalValue(Rational(31, 1)))
        val target = metaSet(make to TagValue.Text("PictTool"))
        val after = metaSet(
            make to TagValue.Text("Canon"),
            gpsLat to TagValue.RationalValue(Rational(31, 1)),
            model to TagValue.Text("X100"),
        )

        val summary = MetadataVerifier.compare(before, target, after, "abc", "def").summary()

        assertTrue(summary, summary.contains("值不符 1 项"))
        assertTrue(summary, summary.contains("未删除 1 项"))
        assertTrue(summary, summary.contains("多出 1 项"))
        assertTrue(summary, summary.contains("像素已变"))
    }

    private companion object {
        val SOURCE = SourceInfo("sample.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG)
    }
}
