package com.pict.metatool.data.metadata.imaging

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagValue
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * T1.6 覆盖度验证：用 [tools/make-tiff-sample.py] 生成的样张（1×1 TIFF）逐字段断言。
 *
 * 样张刻意带三样东西：
 * 1. Windows XP\* 标签（0x9c9b–0x9c9f）——ExifInterface 1.4.2 没有这些常量，只能靠 commons-imaging 补；
 * 2. IFD1 缩略图目录，且 `Compression`/`XResolution` 与 IFD0 取**不同值**——用来证明按 (tag, 目录) 匹配，
 *    而不是按 tag 盲取（否则会把缩略图的 300 DPI / JPEG 压缩读到主图字段上）；
 * 3. GPS 目录（度分秒有理数数组 + Ref 字符串），验证 RationalList 与标签类型归一化。
 */
class CommonsImagingStoreTest {

    private val store = CommonsImagingStore()

    private val info = SourceInfo(
        displayName = SAMPLE,
        mimeType = "image/tiff",
        sizeBytes = 1175L,
        format = ImageFormatHint.TIFF,
    )

    private fun readSample(): MetadataSet {
        val stream = requireNotNull(javaClass.getResourceAsStream("/samples/$SAMPLE")) {
            "缺少测试样张 /samples/$SAMPLE（跑 `python tools/make-tiff-sample.py` 生成）"
        }
        val metadata = stream.use { Imaging.getMetadata(it, SAMPLE) }
        assertTrue("样张应被解析为 TIFF 结构，实际 ${metadata?.javaClass?.name}", metadata is TiffImageMetadata)
        return store.readFrom(metadata as TiffImageMetadata, info)
    }

    @Test
    fun `supports 只认 TIFF 与 RAW 系列`() {
        assertTrue(store.supports(SourceInfo("a.tif", "image/tiff", 1, ImageFormatHint.TIFF)))
        assertTrue(store.supports(SourceInfo("a.nef", null, 1, ImageFormatHint.RAW)))
        assertFalse(store.supports(SourceInfo("a.jpg", "image/jpeg", 1, ImageFormatHint.JPEG)))
        assertFalse(store.supports(SourceInfo("a.png", "image/png", 1, ImageFormatHint.PNG)))
    }

    @Test
    fun `读取 IFD0 文本与数值字段`() {
        val set = readSample()
        assertEquals(TagValue.Text("PictTest"), set["EXIF:Make"])
        assertEquals(TagValue.Text("Pict TIFF Sample"), set["EXIF:Model"])
        assertEquals(TagValue.Text("Pict Sample Generator"), set["EXIF:Software"])
        assertEquals(TagValue.IntValue(1L), set["EXIF:ImageWidth"])
        assertEquals(TagValue.IntValue(1L), set["EXIF:ImageLength"])
        assertEquals(TagValue.IntValue(1L), set["EXIF:Orientation"])
        assertEquals(TagValue.IntValue(2L), set["EXIF:ResolutionUnit"])
        assertEquals(TagValue.RationalValue(Rational(72, 1)), set["EXIF:XResolution"])
        assertEquals(TagValue.RationalValue(Rational(72, 1)), set["EXIF:YResolution"])
        // BitsPerSample 在目录里是 INT_LIST，单值需归一化成列表
        assertEquals(TagValue.IntList(listOf(8L)), set["EXIF:BitsPerSample"])
    }

    @Test
    fun `DATETIME 字段解析成 Timestamp 而不是裸文本`() {
        val set = readSample()
        val expected = TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 10, 15, 30))
        assertEquals(expected, set["EXIF:DateTime"])
        assertEquals(expected, set["EXIF:DateTimeOriginal"])
        assertEquals(expected, set["EXIF:DateTimeDigitized"])
    }

    @Test
    fun `读取 Windows XP 标签——本 store 独有的覆盖度`() {
        val set = readSample()
        // 样张里的 XP* 是 UTF-16LE，commons-imaging 已解码成 String，按目录声明（BYTES）落成 Text
        assertEquals(TagValue.Text("示例标题 Pict"), set["EXIF:XPTitle"])
        assertEquals(TagValue.Text("示例备注"), set["EXIF:XPComment"])
        assertEquals(TagValue.Text("示例作者"), set["EXIF:XPAuthor"])
        assertEquals(TagValue.Text("测试;元数据"), set["EXIF:XPKeywords"])
        assertEquals(TagValue.Text("示例主题"), set["EXIF:XPSubject"])
        // Microsoft Rating（0x4746），ExifInterface 同样没有
        assertEquals(TagValue.IntValue(4L), set["EXIF:Rating"])
    }

    @Test
    fun `读取 Exif 子目录的曝光参数`() {
        val set = readSample()
        assertEquals(TagValue.RationalValue(Rational(1, 250)), set["EXIF:ExposureTime"])
        assertEquals(TagValue.RationalValue(Rational(28, 10)), set["EXIF:FNumber"])
        assertEquals(TagValue.IntList(listOf(400L)), set["EXIF:ISOSpeedRatings"])
        assertEquals(TagValue.IntValue(3L), set["EXIF:ExposureProgram"])
        assertEquals(TagValue.IntValue(5L), set["EXIF:MeteringMode"])
        assertEquals(TagValue.IntValue(16L), set["EXIF:Flash"])
        assertEquals(TagValue.RationalValue(Rational(50, 1)), set["EXIF:FocalLength"])
        assertEquals(TagValue.RationalValue(Rational(0, 1)), set["EXIF:ExposureBiasValue"])
        assertEquals(TagValue.RationalValue(Rational(7965784, 1000000)), set["EXIF:ShutterSpeedValue"])
        assertEquals(TagValue.IntValue(0L), set["EXIF:WhiteBalance"])
        assertEquals(TagValue.IntValue(0L), set["EXIF:ExposureMode"])
        assertEquals(TagValue.IntValue(0L), set["EXIF:SceneCaptureType"])
        // 库没给常量、由手工表按标准标签号补的三个字段
        assertEquals(TagValue.IntValue(1L), set["EXIF:ColorSpace"])
        assertEquals(TagValue.IntValue(1L), set["EXIF:PixelXDimension"])
        assertEquals(TagValue.IntValue(1L), set["EXIF:PixelYDimension"])
        // 可打印 ASCII 的 byte[] 解成文本，与 ExifInterface 的读法一致
        assertEquals(TagValue.Text("0232"), set["EXIF:ExifVersion"])
    }

    @Test
    fun `IFD0 与 IFD1 同号标签不串位`() {
        val set = readSample()
        // 样张 IFD1 里 Compression=6、XResolution=300，若按 tag 盲取会读错
        assertEquals(TagValue.IntValue(1L), set["EXIF:Compression"])
        assertEquals(TagValue.RationalValue(Rational(72, 1)), set["EXIF:XResolution"])
    }

    @Test
    fun `读取 GPS 目录的度分秒与参考方向`() {
        val set = readSample()
        assertEquals(
            TagValue.RationalList(listOf(Rational(31, 1), Rational(14, 1), Rational(1200, 100))),
            set["GPS:GPSLatitude"],
        )
        assertEquals(
            TagValue.RationalList(listOf(Rational(121, 1), Rational(28, 1), Rational(1200, 100))),
            set["GPS:GPSLongitude"],
        )
        assertEquals(TagValue.Text("N"), set["GPS:GPSLatitudeRef"])
        assertEquals(TagValue.Text("E"), set["GPS:GPSLongitudeRef"])
        assertEquals(TagValue.IntValue(0L), set["GPS:GPSAltitudeRef"])
        assertEquals(TagValue.RationalValue(Rational(12, 1)), set["GPS:GPSAltitude"])
        assertEquals(
            TagValue.RationalList(listOf(Rational(2, 1), Rational(15, 1), Rational(30, 1))),
            set["GPS:GPSTimeStamp"],
        )
        assertEquals(TagValue.Text("2026:09:09"), set["GPS:GPSDateStamp"])
        assertEquals(TagValue.Text("WGS-84"), set["GPS:GPSMapDatum"])
        assertEquals(TagValue.RationalValue(Rational(3, 1)), set["GPS:GPSDOP"])
    }

    @Test
    fun `读取目录常量为 null 的字段——按规范补目录后仍能命中`() {
        val set = readSample()
        // 这 5 个常量在 1.0.0-alpha6 里 directoryType == null，
        // 若 DIR_OVERRIDE 缺失或目录填错，lookup 会直接落空 → 断言失败。
        assertEquals(TagValue.RationalValue(Rational(15, 1)), set["EXIF:FlashEnergy"])
        assertEquals(TagValue.IntValue(3L), set["EXIF:FocalPlaneResolutionUnit"])
        assertEquals(TagValue.RationalValue(Rational(200, 1)), set["EXIF:ExposureIndex"])
        assertEquals(TagValue.IntValue(2L), set["EXIF:SensingMethod"])
        assertEquals(TagValue.Binary("0102", 2), set["EXIF:DeviceSettingDescription"])
    }

    @Test
    fun `样张覆盖到的键都落在字段目录里`() {
        val set = readSample()
        assertTrue("应读出足够多的字段，实际 ${set.size}", set.size >= 40)
        val unknown = set.entries.keys.filter { com.pict.metatool.domain.model.FieldCatalog.spec(it) == null }
        assertEquals("读出的键必须在 FieldCatalog 里，未登记：$unknown", emptyList<Any>(), unknown)
        assertNotNull(set["EXIF:XPTitle"])
    }

    private companion object {
        const val SAMPLE = "pict-tiff-xp.tif"
    }
}
