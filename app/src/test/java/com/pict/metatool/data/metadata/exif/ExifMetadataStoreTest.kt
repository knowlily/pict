package com.pict.metatool.data.metadata.exif

import androidx.exifinterface.media.ExifInterface
import com.pict.metatool.domain.format.GpsCoordinate
import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真实 JPEG 样本的读取验证（docs/07 T1.4）。
 *
 * 样本取自 ianare/exif-samples（CC0），放在 test/resources/samples：
 * - canon-40d.jpg：Canon EOS 40D，EXIF IFD0 + Exif IFD 完整
 * - gps-dscn0010.jpg：Nikon COOLPIX P6000，带 GPS 坐标
 *
 * ExifInterface 是纯 Java 实现，JVM 单测里可以直接跑（android.util.Log 走 default values）。
 */
class ExifMetadataStoreTest {

    private val store = ExifMetadataStore()

    private fun load(
        name: String,
        mime: String = "image/jpeg",
        format: ImageFormatHint = ImageFormatHint.JPEG,
    ): Map<TagKey, TagValue> {
        val file = File("src/test/resources/samples/$name")
        assertTrue("样本缺失：${file.absolutePath}", file.exists())
        val info = SourceInfo(name, mime, file.length(), format)
        return store.readFrom(ExifInterface(file), info).entries
    }

    private fun dump(name: String, entries: Map<TagKey, TagValue>) {
        val out = File("build/metadata-dump-${name.removeSuffix(".jpg")}.txt")
        out.parentFile?.mkdirs()
        out.writeText(
            entries.entries.joinToString("\n") { (k, v) ->
                val spec = FieldCatalog.spec(k)
                "${k.full}\t${spec?.label ?: "-"}\t${TagValueFormatter.format(v, spec)}"
            },
        )
    }

    @Test
    fun `Canon 40D 样本读到基础字段`() {
        val entries = load("canon-40d.jpg")
        dump("canon-40d.jpg", entries)
        assertEquals(TagValue.Text("Canon"), entries[TagKey.of("EXIF:Make")])
        assertEquals(TagValue.Text("Canon EOS 40D"), entries[TagKey.of("EXIF:Model")])
        assertEquals(TagValue.IntValue(1), entries[TagKey.of("EXIF:Orientation")])
        // 拍摄时间解析为 LocalDateTime
        val shot = entries[TagKey.of("EXIF:DateTimeOriginal")]
        assertTrue("拍摄时间应为 Timestamp，实际 $shot", shot is TagValue.Timestamp)
        assertEquals(2008, (shot as TagValue.Timestamp).value.year)
        // 光圈（该样本没有 FNumber，只有 APEX 的 ApertureValue）
        val aperture = entries[TagKey.of("EXIF:ApertureValue")]
        assertTrue("应读到光圈 APEX 值，实际 $aperture", aperture is TagValue.RationalValue)
        assertEquals(5.625, (aperture as TagValue.RationalValue).value.asDouble, 1e-6)
    }

    @Test
    fun `Canon 40D 样本的 ISO 与焦距按目录类型解析`() {
        val entries = load("canon-40d.jpg")
        assertEquals(TagValue.IntList(listOf(100L)), entries[TagKey.of("EXIF:ISOSpeedRatings")])
        val focal = entries[TagKey.of("EXIF:FocalLength")]
        assertTrue("焦距应为有理数，实际 $focal", focal is TagValue.RationalValue)
        assertEquals(135.0, (focal as TagValue.RationalValue).value.asDouble, 1e-6)
    }

    @Test
    fun `Nikon 样本的 GPS 坐标读成度分秒并换算十进制度`() {
        val entries = load("gps-dscn0010.jpg")
        dump("gps-dscn0010.jpg", entries)
        val lat = entries[TagKey.of("GPS:GPSLatitude")]
        assertTrue("应读到纬度，实际 $lat", lat is TagValue.RationalList)
        val latDms = (lat as TagValue.RationalList).values
        assertEquals(3, latDms.size)
        assertEquals(43.0, latDms[0].asDouble, 1e-6)
        assertEquals(28.0, latDms[1].asDouble, 1e-6)
        assertEquals(2.814, latDms[2].asDouble, 1e-6)
        assertEquals(TagValue.Text("N"), entries[TagKey.of("GPS:GPSLatitudeRef")])

        // 十进制度由 DMS + Ref 现算（UI 显示与地图链接走这条路径），不额外造键
        val latitude = GpsCoordinate.applyRef(GpsCoordinate.dmsToDecimal(latDms), "N")
        assertEquals(43.4674483, latitude, 1e-6)

        val lon = entries[TagKey.of("GPS:GPSLongitude")]
        assertTrue("应读到经度，实际 $lon", lon is TagValue.RationalList)
        val longitude = GpsCoordinate.applyRef(
            GpsCoordinate.dmsToDecimal((lon as TagValue.RationalList).values),
            "E",
        )
        assertEquals(11.8851267, longitude, 1e-6)
    }

    @Test
    fun `Nikon D70 样本的 XMP 包能解析出命名空间属性`() {
        val entries = load("nikon-d70.jpg")
        dump("nikon-d70.jpg", entries)
        // XMP 原文由 TAG_XMP 拿到后交给 XmpParser（com.adobe.internal.xmp，xmpcore 6.1.11）
        assertEquals(TagValue.Text("image/jpeg"), entries[TagKey.of("XMP:dc:format")])
        val docId = entries[TagKey.of("XMP:xmpMM:DocumentID")]
        // 目录里 xmpMM:DocumentID 的值类型是 URI，所以解析成 UriValue
        assertTrue("应读到 xmpMM:DocumentID，实际 $docId", docId is TagValue.UriValue)
        assertTrue((docId as TagValue.UriValue).value.startsWith("uuid:"))
    }

    @Test
    fun `PNG 与 WebP 缺失的尺寸不被写成 0`() {
        // ExifInterface 对这两种容器把缺失的 IFD0 尺寸读成 "0"；
        // 原样写入会覆盖容器读取器的真实宽高，界面上就成了 0 × 0
        val png = load("png-tiny.png", "image/png", ImageFormatHint.PNG)
        assertNull(png[TagKey.of("EXIF:ImageWidth")])
        assertNull(png[TagKey.of("EXIF:ImageLength")])
        assertEquals(TagValue.Text("Pict"), png[TagKey.of("EXIF:Make")])

        val webp = load("webp-tiny.webp", "image/webp", ImageFormatHint.WEBP)
        assertNull(webp[TagKey.of("EXIF:ImageWidth")])
        assertNull(webp[TagKey.of("EXIF:ImageLength")])
    }

    @Test
    fun `supports 只认支持的格式`() {
        assertTrue(store.supports(SourceInfo("a.jpg", "image/jpeg", 1, ImageFormatHint.JPEG)))
        assertTrue(store.supports(SourceInfo("a.png", "image/png", 1, ImageFormatHint.PNG)))
        // BMP / RAW 不在 ExifInterface 支持范围，交给别的读取器
        assertFalse(store.supports(SourceInfo("x.bmp", "image/bmp", 0, ImageFormatHint.BMP)))
        assertFalse(store.supports(SourceInfo("x.nef", "image/x-nikon-nef", 0, ImageFormatHint.RAW)))
        assertEquals("exif", store.id)
    }
}
