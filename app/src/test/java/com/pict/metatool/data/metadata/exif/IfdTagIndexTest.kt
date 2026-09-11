package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.ImageFormatHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 字节层 IFD 目录 —— R-19 判据的来源（docs/09）。
 *
 * 断言全部对着 exiftool 的实测结果写（tag 号取自 `exiftool -D` 的十进制值）：
 * canon-40d.jpg 没有 LightSource(0x9208)、gps-dscn0010.jpg 有；PNG/WebP 样本里的 EXIF 是 exiftool 写进去的；
 * pict-tiff-xp.tif 是整档 IFD 结构。以后改解析逻辑，这些断言就是护栏。
 */
class IfdTagIndexTest {

    private val samples = File("src/test/resources/samples")

    private fun bytes(name: String): ByteArray {
        val file = File(samples, name)
        assertTrue("样本缺失：${file.absolutePath}", file.exists())
        return file.readBytes()
    }

    private fun index(format: ImageFormatHint, name: String): IfdTagIndex {
        val index = IfdTagIndex.of(format, bytes(name))
        assertNotNull("$name 的 IFD 目录应该能读出来", index)
        return index!!
    }

    @Test
    fun `JPEG 的 IFD0 与 ExifIFD 都要走到`() {
        val index = index(ImageFormatHint.JPEG, "canon-40d.jpg")
        assertTrue("0x010F Make 在 IFD0", index.contains(0x010F))
        assertTrue("0x0112 Orientation 在 IFD0", index.contains(0x0112))
        assertTrue("0x829A ExposureTime 在 ExifIFD——找得到说明子目录指针被跟进去了", index.contains(0x829A))
        assertFalse("0x9208 LightSource 源文件里没有（exiftool 也看不到）", index.contains(0x9208))
        assertFalse("0x0100 ImageWidth 不在 IFD0（JPEG 的宽高在 SOF 段里，不是 IFD 标签）", index.contains(0x0100))
    }

    @Test
    fun `GPS 子目录也要走到`() {
        val index = index(ImageFormatHint.JPEG, "gps-dscn0010.jpg")
        assertTrue("0x0002 GPSLatitude 在 GPS 目录里", index.contains(0x0002))
        assertTrue("0x9208 LightSource 这份文件真的写了 0（Unknown）", index.contains(0x9208))
    }

    @Test
    fun `PNG 的 eXIf 块`() {
        val index = index(ImageFormatHint.PNG, "png-exif.png")
        assertTrue("0x010F Make", index.contains(0x010F))
        assertTrue("0x9208 LightSource（exiftool 写进去的真标签）", index.contains(0x9208))
        assertFalse("0x0112 Orientation 文件里没有——库会补一个 0 出来", index.contains(0x0112))
    }

    @Test
    fun `WebP 的 EXIF 块`() {
        val index = index(ImageFormatHint.WEBP, "webp-exif.webp")
        assertTrue("0x010F Make", index.contains(0x010F))
        assertTrue("0x9208 LightSource", index.contains(0x9208))
        assertFalse("0x0112 Orientation 文件里没有", index.contains(0x0112))
    }

    @Test
    fun `TIFF 整档就是 IFD 结构`() {
        val index = index(ImageFormatHint.TIFF, "pict-tiff-xp.tif")
        assertTrue("0x0100 ImageWidth 在 IFD0", index.contains(0x0100))
        assertTrue("0x0112 Orientation 在 IFD0", index.contains(0x0112))
        assertFalse("0x9208 LightSource 这份 TIFF 里没有", index.contains(0x9208))
    }

    @Test
    fun `一个名字对应多个 tag 号时 命中任意一个就算文件里有`() {
        val index = index(ImageFormatHint.PNG, "png-exif.png")
        assertTrue("ColorSpace 在库的表里挂着 55 和 40961 两个号", index.contains(40961))
        assertFalse(
            "文件里真有 ColorSpace(40961)，同一个名字的另一个号（55）不出现也不能算没有",
            index.provesAbsence("ColorSpace"),
        )
    }

    @Test
    fun `没有元数据块的文件给空目录 但容器头能推的尺寸不算没有`() {
        val index = index(ImageFormatHint.JPEG, "jpeg-no-exif.jpg")
        assertTrue("一个 EXIF 块都没有 → 任何 EXIF 标签都被证伪", index.provesAbsence("LightSource"))
        assertFalse("JPEG 的像素尺寸来自 SOF 段，不能说「文件里没有」", index.provesAbsence("ImageWidth"))
    }

    @Test
    fun `读不通的字节一律退回证不了`() {
        assertNull("太短", IfdTagIndex.of(ImageFormatHint.JPEG, byteArrayOf(1, 2, 3)))
        assertNull("不是 PNG 签名", IfdTagIndex.of(ImageFormatHint.PNG, "not a png at all".toByteArray()))
        assertNull("RIFF 头不全", IfdTagIndex.of(ImageFormatHint.WEBP, "RIFF".toByteArray()))
        assertNull("HEIF 暂不解析 → 证不了", IfdTagIndex.of(ImageFormatHint.HEIF, bytes("heic-tiny.heic")))
        assertNull(
            "截断的 TIFF：IFD0 条目读出界",
            IfdTagIndex.of(ImageFormatHint.TIFF, bytes("pict-tiff-xp.tif").copyOf(24)),
        )
        assertNull(
            "字节序标记坏了",
            IfdTagIndex.of(ImageFormatHint.TIFF, bytes("pict-tiff-xp.tif").also { it[0] = 'X'.code.toByte() }),
        )
    }
}
