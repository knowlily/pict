package com.pict.metatool.data.metadata.exif

import androidx.exifinterface.media.ExifInterface
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「字段集只反映文件真有的标签」（docs/09 R-19）。
 *
 * ExifInterface 的 `addDefaultValuesForCompatibility()` 会在文件里没有 LightSource 时补一个 `0`，
 * 还会给缺失的 Orientation 补 `0`。读侧照单全收的话，编辑页会凭空显示「光源 0」，
 * 保存时又把这些源文件没有的标签写回去。
 *
 * 判据是**字节层的 IFD 目录**（[IfdTagIndex]），不是「值是不是等于默认值」——
 * gps-dscn0010.jpg 里真的是 `LightSource = 0`（Unknown），它必须照常读出来；
 * 也不能靠 `getAttributeRange` 的负偏移（本工具写过的文件里真标签照样是 -1，见第七轮实测）。
 */
class ExifReadHonestyTest {

    private val store = ExifMetadataStore()

    private val lightSource = TagKey.of("EXIF:LightSource")
    private val orientation = TagKey.of("EXIF:Orientation")

    private fun sample(name: String, format: ImageFormatHint, mime: String): Pair<File, SourceInfo> {
        val file = File("src/test/resources/samples/$name")
        assertTrue("样本缺失：${file.absolutePath}", file.exists())
        return file to SourceInfo(name, mime, file.length(), format)
    }

    /** 生产路径怎么读，这里就怎么读：字节一并交给读取层。 */
    private fun read(name: String, format: ImageFormatHint = ImageFormatHint.JPEG, mime: String = "image/jpeg") =
        sample(name, format, mime).let { (file, info) ->
            store.readFrom(ExifInterface(file), info, file.readBytes()).entries
        }

    @Test
    fun `源文件没有 LightSource 时字段集里也不该出现`() {
        listOf(
            Triple("canon-40d.jpg", ImageFormatHint.JPEG, "image/jpeg"),
            Triple("nikon-d70.jpg", ImageFormatHint.JPEG, "image/jpeg"),
            Triple("pict-tiff-xp.tif", ImageFormatHint.TIFF, "image/tiff"),
        ).forEach { (name, format, mime) ->
            val entries = read(name, format, mime)
            assertTrue("$name 应该读到别的字段，否则这条断言没意义", entries.isNotEmpty())
            assertFalse(
                "$name 源文件没有 LightSource，读侧不该凭空补出「光源 0」（R-19）",
                entries.containsKey(lightSource),
            )
        }
    }

    @Test
    fun `没有 EXIF 块的文件 任何 EXIF 字段都不该冒出来`() {
        val entries = read("jpeg-no-exif.jpg")
        assertFalse("jpeg-no-exif.jpg 连 APP1 都没有，不该长出 LightSource", entries.containsKey(lightSource))
        assertFalse("也不该长出 Orientation（库会补 0）", entries.containsKey(orientation))
    }

    @Test
    fun `PNG 的 eXIf 里没有的标签不该冒出来 有的必须留住`() {
        val entries = read("png-exif.png", ImageFormatHint.PNG, "image/png")
        assertFalse("eXIf 里没有 Orientation，库补出来的 0 要挡住", entries.containsKey(orientation))
        assertEquals(
            "eXIf 里真写了 LightSource = 23（D50），不能连真标签一起丢",
            TagValue.IntValue(23),
            entries[lightSource],
        )
    }

    @Test
    fun `源文件真的写了 LightSource 时要照常读出来`() {
        assertEquals(
            "gps-dscn0010.jpg 里 LightSource = 0（Unknown）是真值，不能被当成「补出来的默认值」丢掉",
            TagValue.IntValue(0),
            read("gps-dscn0010.jpg")[lightSource],
        )
        assertEquals(
            "webp-exif.webp 里 LightSource = 23（D50）是真值",
            TagValue.IntValue(23),
            read("webp-exif.webp", ImageFormatHint.WEBP, "image/webp")[lightSource],
        )
    }

    /**
     * 不给字节 = 证不了，此时**一律保留**：宁可多显示一个字段，也不把文件里真有的元数据藏起来。
     * 这条钉住的是「保守方向」——将来谁把「证不了」改成「丢掉」，这里会立刻红。
     */
    @Test
    fun `没给字节时证不了 照单全收`() {
        val (file, info) = sample("canon-40d.jpg", ImageFormatHint.JPEG, "image/jpeg")
        val entries = store.readFrom(ExifInterface(file), info).entries
        assertTrue(
            "证不了的时候应该保留（哪怕因此暂时漏出幽灵字段），而不是猜着丢",
            entries.containsKey(lightSource),
        )
    }
}
