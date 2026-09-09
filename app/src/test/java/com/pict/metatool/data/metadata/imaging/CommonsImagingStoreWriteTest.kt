package com.pict.metatool.data.metadata.imaging

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

/**
 * T2.6：commons-imaging 的写入通道。
 *
 * 两条路都要求「像素一个字节都不动」——TIFF 靠 `TiffImageWriterLossless` 搬运原始 strip，
 * JPEG 靠 `ExifRewriter` 只替换 APP1 段。所以每个用例除了断言字段值，还要断言图像数据
 * 逐字节相等：只比对元数据的话，把像素重新编码一遍也照样「通过」。
 */
class CommonsImagingStoreWriteTest {

    private val store = CommonsImagingStore()

    private val tiffInfo = SourceInfo(TIFF_SAMPLE, "image/tiff", 1175L, ImageFormatHint.TIFF)
    private val jpegInfo = SourceInfo(JPEG_SAMPLE, "image/jpeg", 7958L, ImageFormatHint.JPEG)

    // ---- 样本装载与读回 ----------------------------------------------------

    private fun sample(name: String): ByteArray {
        val stream = requireNotNull(javaClass.getResourceAsStream("/samples/$name")) {
            "缺少测试样张 /samples/$name"
        }
        return stream.use { it.readBytes() }
    }

    private fun readTiff(bytes: ByteArray): MetadataSet {
        val metadata = Imaging.getMetadata(bytes) as TiffImageMetadata
        return store.readFrom(metadata, tiffInfo)
    }

    /** 用实现自己的读路径回读——两边归一化口径一致，比对才有意义。 */
    private fun readJpeg(bytes: ByteArray): MetadataSet {
        val metadata = requireNotNull(store.exifMetadataOf(bytes)) { "样张应带 EXIF" }
        return store.readFrom(metadata, jpegInfo)
    }

    /** TIFF 的原始 strip 字节，用来证明像素没被重新编码。 */
    private fun tiffStrip(bytes: ByteArray): ByteArray {
        val metadata = Imaging.getMetadata(bytes) as TiffImageMetadata
        val offset = requireNotNull(metadata.findField(TiffTagConstants.TIFF_TAG_STRIP_OFFSETS)).intValue
        val count = requireNotNull(metadata.findField(TiffTagConstants.TIFF_TAG_STRIP_BYTE_COUNTS)).intValue
        return bytes.copyOfRange(offset, offset + count)
    }

    /** 从 SOS 标记到文件末尾：压缩后的图像数据，无损重写时应当逐字节不变。 */
    private fun jpegScanData(bytes: ByteArray): ByteArray {
        var pos = 2
        while (pos + 4 <= bytes.size) {
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == MARKER_SOS) return bytes.copyOfRange(pos, bytes.size)
            if (marker == MARKER_SOI || marker == MARKER_TEM || marker in MARKER_RST_FIRST..MARKER_RST_LAST) {
                pos += 2
                continue
            }
            val length = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 2 + length
        }
        throw AssertionError("样本里没有找到 SOS 段")
    }

    // ---- canWrite ---------------------------------------------------------

    @Test
    fun `canWrite 认 TIFF 与 JPEG，不认 PNG 和 WebP`() {
        assertTrue(store.canWrite(tiffInfo))
        assertTrue(store.canWrite(jpegInfo))
        assertTrue(!store.canWrite(SourceInfo("a.png", "image/png", 1, ImageFormatHint.PNG)))
        assertTrue(!store.canWrite(SourceInfo("a.webp", "image/webp", 1, ImageFormatHint.WEBP)))
        // RAW 容器同为 TIFF，但厂商私有 IFD 未验证，刻意不声明可写
        assertTrue(!store.canWrite(SourceInfo("a.nef", null, 1, ImageFormatHint.RAW)))
    }

    // ---- TIFF -------------------------------------------------------------

    @Test
    fun `TIFF 改字段后读回是新值，其余字段逐键不变`() {
        val original = sample(TIFF_SAMPLE)
        val before = readTiff(original)
        val key = TagKey.of("EXIF:Make")
        val target = before.with(key, TagValue.Text("PictRenamed"))

        val out = ByteArrayOutputStream()
        val result = store.rewriteTiff(original, target, out)

        assertEquals(setOf(key), result.writtenKeys)
        assertTrue("不该有字段被丢：${result.droppedKeys}", result.droppedKeys.isEmpty())

        val after = readTiff(out.toByteArray())
        assertEquals(TagValue.Text("PictRenamed"), after[key])
        assertOtherKeysUntouched(before, after, key)
    }

    @Test
    fun `TIFF 删字段后读回为空，其余字段不变`() {
        val original = sample(TIFF_SAMPLE)
        val before = readTiff(original)
        val key = TagKey.of("EXIF:Software")

        val out = ByteArrayOutputStream()
        val result = store.rewriteTiff(original, before.without(key), out)

        assertEquals(setOf(key), result.writtenKeys)
        val after = readTiff(out.toByteArray())
        assertNull("删掉的字段不该还在", after[key])
        assertOtherKeysUntouched(before, after, key)
    }

    @Test
    fun `TIFF 重写不动图像数据`() {
        val original = sample(TIFF_SAMPLE)
        val target = readTiff(original).with(TagKey.of("EXIF:Model"), TagValue.Text("Pixel Safe"))

        val out = ByteArrayOutputStream()
        store.rewriteTiff(original, target, out)

        assertArrayEquals("strip 字节必须原样搬运", tiffStrip(original), tiffStrip(out.toByteArray()))
    }

    @Test
    fun `TIFF 时间字段按 EXIF 字面量写回`() {
        val original = sample(TIFF_SAMPLE)
        val key = TagKey.of("EXIF:DateTime")
        val stamp = LocalDateTime.of(2030, 1, 2, 3, 4, 5)

        val out = ByteArrayOutputStream()
        store.rewriteTiff(original, readTiff(original).with(key, TagValue.Timestamp(stamp)), out)

        assertEquals(TagValue.Timestamp(stamp), readTiff(out.toByteArray())[key])
    }

    // ---- JPEG -------------------------------------------------------------

    @Test
    fun `JPEG 改字段后读回是新值，SOS 之后的扫描数据逐字节不变`() {
        val original = sample(JPEG_SAMPLE)
        val before = readJpeg(original)
        val key = TagKey.of("EXIF:Make")
        val target = before.with(key, TagValue.Text("PictRenamed"))

        val out = ByteArrayOutputStream()
        val result = store.rewriteJpeg(original, target, out)
        val rewritten = out.toByteArray()

        assertEquals(setOf(key), result.writtenKeys)
        assertEquals(TagValue.Text("PictRenamed"), readJpeg(rewritten)[key])
        assertArrayEquals(
            "无损重写只该换 APP1 段，扫描数据必须原样",
            jpegScanData(original),
            jpegScanData(rewritten),
        )
        assertOtherKeysUntouched(before, readJpeg(rewritten), key)
    }

    @Test
    fun `JPEG 删字段后读回为空`() {
        val original = sample(JPEG_SAMPLE)
        val before = readJpeg(original)
        val key = TagKey.of("EXIF:Model")

        val out = ByteArrayOutputStream()
        store.rewriteJpeg(original, before.without(key), out)

        assertNull(readJpeg(out.toByteArray())[key])
    }

    // ---- 丢弃 -------------------------------------------------------------

    @Test
    fun `映射表里没有的键进 dropped，文件照常写出`() {
        val original = sample(TIFF_SAMPLE)
        val before = readTiff(original)
        val ghost = TagKey.of("EXIF:NotARealTag")
        val key = TagKey.of("EXIF:Make")

        val target = before.with(ghost, TagValue.Text("无处安放")).with(key, TagValue.Text("Still Writes"))
        val out = ByteArrayOutputStream()
        val result = store.rewriteTiff(original, target, out)

        assertEquals(setOf(ghost), result.droppedKeys)
        assertEquals(setOf(key), result.writtenKeys)
        val after = readTiff(out.toByteArray())
        assertEquals(TagValue.Text("Still Writes"), after[key])
        assertNull(after[ghost])
    }

    // ---- 工具 -------------------------------------------------------------

    /** 除 [changed] 外的每个键，值都必须一模一样。 */
    private fun assertOtherKeysUntouched(before: MetadataSet, after: MetadataSet, changed: TagKey) {
        (before.entries.keys - changed).forEach { key ->
            assertEquals("字段 $key 不该被这次重写改动", before[key], after[key])
        }
    }

    private companion object {
        const val TIFF_SAMPLE = "pict-tiff-xp.tif"
        const val JPEG_SAMPLE = "canon-40d.jpg"

        const val MARKER_SOI = 0xD8
        const val MARKER_SOS = 0xDA
        const val MARKER_TEM = 0x01
        const val MARKER_RST_FIRST = 0xD0
        const val MARKER_RST_LAST = 0xD7
    }
}
