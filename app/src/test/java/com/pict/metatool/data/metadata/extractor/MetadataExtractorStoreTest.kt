package com.pict.metatool.data.metadata.extractor

import com.drew.imaging.ImageMetadataReader
import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * IPTC / 文件结构读取验证（docs/07 T1.5）。
 *
 * 样张 `iptc-canon.jpg` 由 `tools/make-iptc-sample.py` 生成：拿 Canon_40D.jpg 作底，
 * 按 IPTC IIM 规范手工拼一个 APP13/8BIM 0x0404 块（含 UTF-8 声明 1:90）。
 * 公开样本集里没有中英混合的 IPTC 样张，自己造一个能精确控制每个字段的期望值。
 *
 * metadata-extractor 是纯 Java 实现，JVM 单测直接读文件即可。
 */
class MetadataExtractorStoreTest {

    private val store = MetadataExtractorStore()

    private fun load(
        name: String,
        mime: String = "image/jpeg",
        format: ImageFormatHint = ImageFormatHint.JPEG,
    ): Map<TagKey, TagValue> {
        val file = File("src/test/resources/samples/$name")
        assertTrue("样本缺失：${file.absolutePath}", file.exists())
        val info = SourceInfo(name, mime, file.length(), format)
        return store.readFrom(ImageMetadataReader.readMetadata(file), info).entries
    }

    private fun dump(name: String, entries: Map<TagKey, TagValue>) {
        val out = File("build/metadata-dump-${name.substringBeforeLast(".")}.txt")
        out.parentFile?.mkdirs()
        out.writeText(
            entries.entries.joinToString("\n") { (k, v) ->
                val spec = FieldCatalog.spec(k)
                "${k.full}\t${spec?.label ?: "-"}\t${TagValueFormatter.format(v, spec)}"
            },
        )
    }

    private fun text(entries: Map<TagKey, TagValue>, key: String): String? =
        (entries[TagKey.of(key)] as? TagValue.Text)?.value

    @Test
    fun `IPTC 文本字段按数据集编号读入`() {
        val entries = load("iptc-canon.jpg")
        dump("iptc-canon.jpg", entries)
        assertEquals("Pict 测试标题", text(entries, "IPTC:2:5"))
        assertEquals("测试作者", text(entries, "IPTC:2:80"))
        assertEquals("南京", text(entries, "IPTC:2:90"))
        assertEquals("中国", text(entries, "IPTC:2:101"))
        assertEquals("© 2026 测试版权", text(entries, "IPTC:2:116"))
        assertEquals("一段中文说明，用于验证 IPTC 读取。", text(entries, "IPTC:2:120"))
    }

    @Test
    fun `重复出现的关键词合并成一个字段`() {
        val entries = load("iptc-canon.jpg")
        val keywords = text(entries, "IPTC:2:25")
        assertTrue("应读到关键词，实际 $keywords", keywords != null)
        assertTrue("应含中文关键词：$keywords", keywords!!.contains("关键词甲"))
        assertTrue("应含英文关键词：$keywords", keywords.contains("keyword-b"))
    }

    @Test
    fun `IPTC 日期与时间按目录类型解析`() {
        val entries = load("iptc-canon.jpg")
        assertEquals(
            TagValue.DateValue(LocalDate.of(2026, 9, 9)),
            entries[TagKey.of("IPTC:2:55")],
        )
        assertEquals(
            TagValue.TimeValue(LocalTime.of(10, 15, 30)),
            entries[TagKey.of("IPTC:2:60")],
        )
    }

    @Test
    fun `目录里没有定义的键一律丢弃`() {
        val entries = load("iptc-canon.jpg")
        // 1:90 字符集声明是解析用的元信息，不在 FieldCatalog 里，不该出现在结果中
        val unknown = entries.keys.filter { FieldCatalog.spec(it) == null }
        assertTrue("出现了目录外的键：$unknown", unknown.isEmpty())
    }

    @Test
    fun `容器结构字段补上宽高与位深`() {
        val entries = load("iptc-canon.jpg")
        // 底图 100x68、8bit、3 通道（file(1) 与 JPEG SOF 一致）
        assertEquals(TagValue.IntValue(100), entries[TagKey.of("EXIF:ImageWidth")])
        assertEquals(TagValue.IntValue(68), entries[TagKey.of("EXIF:ImageLength")])
        assertEquals(TagValue.IntList(listOf(8L)), entries[TagKey.of("EXIF:BitsPerSample")])
        assertEquals(TagValue.IntValue(3), entries[TagKey.of("EXIF:SamplesPerPixel")])
    }

    @Test
    fun `WebP 与 HEIF 的宽高也从容器读取`() {
        // ExifInterface 在这两种容器上把缺失的尺寸读成 "0"，尺寸只能由容器读取器提供
        val webp = load("webp-tiny.webp", "image/webp", ImageFormatHint.WEBP)
        assertEquals(TagValue.IntValue(8), webp[TagKey.of("EXIF:ImageWidth")])
        assertEquals(TagValue.IntValue(6), webp[TagKey.of("EXIF:ImageLength")])

        val heic = load("heic-tiny.heic", "image/heic", ImageFormatHint.HEIF)
        assertEquals(TagValue.IntValue(128), heic[TagKey.of("EXIF:ImageWidth")])
        assertEquals(TagValue.IntValue(96), heic[TagKey.of("EXIF:ImageLength")])
    }

    @Test
    fun `supports 覆盖 TIFF 一脉与常见位图，不认未知格式`() {
        assertTrue(store.supports(SourceInfo("a.jpg", "image/jpeg", 1, ImageFormatHint.JPEG)))
        assertTrue(store.supports(SourceInfo("a.png", "image/png", 1, ImageFormatHint.PNG)))
        assertTrue(store.supports(SourceInfo("a.webp", "image/webp", 1, ImageFormatHint.WEBP)))
        assertTrue(store.supports(SourceInfo("a.tif", "image/tiff", 1, ImageFormatHint.TIFF)))
        assertTrue(store.supports(SourceInfo("a.nef", "image/x-nikon-nef", 1, ImageFormatHint.RAW)))
        assertFalse(store.supports(SourceInfo("x.xxx", "application/octet-stream", 0, ImageFormatHint.UNKNOWN)))
        assertEquals("extractor", store.id)
    }
}
