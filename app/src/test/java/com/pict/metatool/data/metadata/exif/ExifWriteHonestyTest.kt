package com.pict.metatool.data.metadata.exif

import androidx.exifinterface.media.ExifInterface
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R-19 写侧：读侧诚实之后，读出来的字段集里不再有幽灵标签，[ExifMetadataStore.writeTo] 就会把
 * 库自己补出来的属性当成「目标里没有的键」清掉（`setAttribute(tag, null)`），`saveAttributes()`
 * 不再把源文件没有的 LightSource 写回去。
 *
 * 验证不看库怎么报，只看**字节层**：写完再用 [IfdTagIndex] 检查 IFD 目录里有没有 0x9208。
 */
class ExifWriteHonestyTest {

    private val store = ExifMetadataStore()

    private val lightSource = TagKey.of("EXIF:LightSource")
    private val software = TagKey.of("EXIF:Software")

    private fun workCopy(name: String): Pair<File, SourceInfo> {
        val source = File("src/test/resources/samples/$name")
        assertTrue("样本缺失：${source.absolutePath}", source.exists())
        val work = File("build/r19-write/$name").apply { parentFile?.mkdirs() }
        work.writeBytes(source.readBytes())
        return work to SourceInfo(name, "image/jpeg", work.length(), ImageFormatHint.JPEG)
    }

    @Test
    fun `用诚实读出的字段集回写 不会凭空多出 LightSource`() {
        val (work, info) = workCopy("canon-40d.jpg")

        val target = store.readFrom(ExifInterface(work), info, work.readBytes())
        assertFalse("目标集里本来就不该有 LightSource（读侧诚实，R-19）", target.entries.containsKey(lightSource))

        // 顺手改一个真字段：既是用户真实会做的编辑，也是「这次确实写进去了」的阳性对照
        val edited = MetadataSet(
            info,
            LinkedHashMap(target.entries).apply { put(software, TagValue.Text("pict-r19-write-test")) },
        )
        store.writeTo(ExifInterface(work), edited)

        assertEquals(
            "阳性对照：Software 没改成，说明这次写入根本没生效，下面的断言会变成空转",
            "pict-r19-write-test",
            ExifInterface(work).getAttribute(ExifInterface.TAG_SOFTWARE),
        )

        val after = IfdTagIndex.of(ImageFormatHint.JPEG, work.readBytes())
        assertNotNull("回写后的文件应该还能认出 IFD 目录", after)
        assertFalse(
            "保存后文件里多出了源文件没有的 LightSource——写侧泄漏没堵住（exiftool 也能看到）",
            after!!.contains(0x9208),
        )
    }
}
