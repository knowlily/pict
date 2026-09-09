package com.pict.metatool.ui.detail

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 概览页取值（docs/07 T1.10）。 */
class OverviewBuilderTest {

    private fun set(vararg entries: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = SourceInfo("IMG_0001.jpg", "image/jpeg", 2_048_000L, ImageFormatHint.JPEG),
        entries = entries.associate { (key, value) -> TagKey.of(key) to value },
    )

    private fun labels(rows: List<OverviewRow>): List<String> = rows.map { it.label }

    @Test
    fun `五项齐全时按文档顺序输出`() {
        val rows = OverviewBuilder.build(
            set(
                "EXIF:Make" to TagValue.Text("Apple"),
                "EXIF:Model" to TagValue.Text("iPhone 16 Pro"),
                "EXIF:LensModel" to TagValue.Text("iPhone 16 Pro back camera"),
                "EXIF:DateTimeOriginal" to TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 17, 38, 0)),
                "GPS:GPSLatitude" to
                    TagValue.RationalList(listOf(Rational(31, 1), Rational(13, 1), Rational(4944, 100))),
                "GPS:GPSLatitudeRef" to TagValue.Text("N"),
                "GPS:GPSLongitude" to
                    TagValue.RationalList(listOf(Rational(121, 1), Rational(28, 1), Rational(2532, 100))),
                "GPS:GPSLongitudeRef" to TagValue.Text("E"),
                "EXIF:ImageWidth" to TagValue.IntValue(4032),
                "EXIF:ImageLength" to TagValue.IntValue(3024),
            )
        )

        assertEquals(listOf("相机", "镜头", "拍摄时间", "位置", "尺寸"), labels(rows))
        assertEquals("Apple iPhone 16 Pro", rows[0].value)
        assertEquals("iPhone 16 Pro back camera", rows[1].value)
        assertEquals("2026-09-09 17:38:00", rows[2].value)
        assertEquals("31.2304°N 121.4737°E", rows[3].value)
        assertEquals("4032 × 3024", rows[4].value)
    }

    @Test
    fun `机型已包含厂商前缀时不重复`() {
        val rows = OverviewBuilder.build(
            set(
                "EXIF:Make" to TagValue.Text("Apple"),
                "EXIF:Model" to TagValue.Text("Apple iPhone 16 Pro"),
            )
        )
        assertEquals("Apple iPhone 16 Pro", rows.single().value)
    }

    @Test
    fun `只有厂商没有机型时也显示相机行`() {
        val rows = OverviewBuilder.build(set("EXIF:Make" to TagValue.Text("NIKON CORPORATION")))
        assertEquals("NIKON CORPORATION", rows.single().value)
    }

    @Test
    fun `镜头型号优先于镜头规格`() {
        val rows = OverviewBuilder.build(
            set(
                "EXIF:LensModel" to TagValue.Text("RF 50mm F1.8 STM"),
                "EXIF:LensSpecification" to TagValue.RationalList(
                    listOf(Rational(50, 1), Rational(18, 10), Rational(0, 1), Rational(0, 1))
                ),
            )
        )
        assertEquals("RF 50mm F1.8 STM", rows.single().value)
    }

    @Test
    fun `没有镜头型号时用镜头规格拼焦距与光圈`() {
        val rows = OverviewBuilder.build(
            set(
                "EXIF:LensSpecification" to TagValue.RationalList(
                    listOf(Rational(6765, 1000), Rational(178, 100), Rational(0, 1), Rational(0, 1))
                ),
            )
        )
        assertEquals("6.765 mm f/1.78", rows.single().value)
    }

    @Test
    fun `没有镜头规格时退回焦距与光圈字段`() {
        val rows = OverviewBuilder.build(
            set(
                "EXIF:FocalLength" to TagValue.RationalValue(Rational(425, 100)),
                "EXIF:FNumber" to TagValue.RationalValue(Rational(18, 10)),
            )
        )
        assertEquals("4.25 mm f/1.8", rows.single().value)
    }

    @Test
    fun `拍摄时间按 Original 到 Digitized 到 DateTime 回退`() {
        val digitized = OverviewBuilder.build(
            set("EXIF:DateTimeDigitized" to TagValue.Timestamp(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
        )
        assertEquals("拍摄时间", digitized.single().label)
        assertEquals("2026-01-02 03:04:05", digitized.single().value)

        val modified = OverviewBuilder.build(
            set("EXIF:DateTime" to TagValue.Timestamp(LocalDateTime.of(2026, 5, 6, 7, 8, 9)))
        )
        assertEquals("2026-05-06 07:08:09", modified.single().value)
    }

    @Test
    fun `EXIF 缺失时用 XMP 兜底`() {
        val rows = OverviewBuilder.build(
            set(
                "XMP:tiff:Make" to TagValue.Text("FUJIFILM"),
                "XMP:tiff:Model" to TagValue.Text("X-T5"),
                "XMP:exif:DateTimeOriginal" to TagValue.Timestamp(LocalDateTime.of(2026, 3, 4, 5, 6, 7)),
            )
        )
        assertEquals(listOf("相机", "拍摄时间"), labels(rows))
        assertEquals("FUJIFILM X-T5", rows[0].value)
    }

    @Test
    fun `位置用 XMP 十进制度兜底`() {
        val rows = OverviewBuilder.build(
            set(
                "XMP:exif:GPSLatitude" to TagValue.DecimalValue(31.2304),
                "XMP:exif:GPSLongitude" to TagValue.DecimalValue(121.4737),
            )
        )
        assertEquals("31.2304°N 121.4737°E", rows.single().value)
    }

    @Test
    fun `只有纬度时不出位置行`() {
        val rows = OverviewBuilder.build(
            set(
                "GPS:GPSLatitude" to TagValue.RationalList(listOf(Rational(31, 1))),
                "GPS:GPSLatitudeRef" to TagValue.Text("N"),
            )
        )
        assertTrue("缺经度不应显示位置", labels(rows).none { it == "位置" })
    }

    @Test
    fun `南半球西经按参考值取负号`() {
        val rows = OverviewBuilder.build(
            set(
                "GPS:GPSLatitude" to TagValue.RationalList(listOf(Rational(33, 1), Rational(52, 1), Rational(768, 100))),
                "GPS:GPSLatitudeRef" to TagValue.Text("S"),
                "GPS:GPSLongitude" to TagValue.RationalList(listOf(Rational(151, 1), Rational(12, 1), Rational(0, 1))),
                "GPS:GPSLongitudeRef" to TagValue.Text("W"),
            )
        )
        assertEquals("33.8688°S 151.2000°W", rows.single().value)
    }

    @Test
    fun `尺寸字段缺失时用有效宽高兜底`() {
        val rows = OverviewBuilder.build(
            set(
                "EXIF:PixelXDimension" to TagValue.IntValue(1920),
                "EXIF:PixelYDimension" to TagValue.IntValue(1080),
            )
        )
        assertEquals("1920 × 1080", rows.single().value)
    }

    @Test
    fun `空白文本与空数组不算有值`() {
        val rows = OverviewBuilder.build(
            set(
                "EXIF:Make" to TagValue.Text("   "),
                "EXIF:Model" to TagValue.Text(""),
                "EXIF:DateTimeOriginal" to TagValue.Text(""),
                "EXIF:ImageWidth" to TagValue.IntList(emptyList()),
            )
        )
        assertTrue("没有可显示的值时应返回空列表", rows.isEmpty())
    }

    @Test
    fun `空元数据返回空列表`() {
        assertTrue(OverviewBuilder.build(MetadataSet.empty()).isEmpty())
    }
}
