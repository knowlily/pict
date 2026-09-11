package com.pict.metatool.data.metadata.exif

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime

/**
 * 真机落盘验证（docs/07 T2.3）。
 *
 * JVM 单测跑不了这一步：`ExifInterface.setAttribute` 内部用 `android.util.Pair` 传值，
 * 而单测环境里的 Pair 是空实现，字段拿不到值，会直接 NPE。差异计算已经由
 * [ExifWritePlanTest] 在 JVM 上覆盖，这里只验证「喂给 ExifInterface 之后文件真的变了」。
 */
@RunWith(AndroidJUnit4::class)
class ExifMetadataStoreWriteInstrumentedTest {

    private val store = ExifMetadataStore()

    /** 每个用例都从 assets 复制一份干净样本，避免互相污染。 */
    private fun sampleFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // 测试 apk 的 cacheDir 可能尚未创建，用被测 app 的目录并显式 mkdirs
        val dir = instrumentation.targetContext.cacheDir
        dir.mkdirs()
        val file = File(dir, "exif-write-sample.jpg")
        instrumentation.context.assets.open("canon-40d.jpg").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun infoOf(file: File) =
        SourceInfo(file.name, "image/jpeg", file.length(), ImageFormatHint.JPEG)

    private fun readAll(file: File): Map<TagKey, TagValue> =
        store.readFrom(ExifInterface(file), infoOf(file), file.readBytes()).entries

    @Test
    fun 改值落盘后读回是新值() {
        val file = sampleFile()
        val before = readAll(file)
        assertTrue("样本应含 Make", before.containsKey(MAKE))

        val target = MetadataSet(infoOf(file), before + (MAKE to TagValue.Text("PictTool")))
        val result = store.writeTo(ExifInterface(file), target)

        assertTrue(MAKE in result.writtenKeys)
        assertTrue("不该有丢弃项：${result.droppedKeys}", result.droppedKeys.isEmpty())
        assertEquals(TagValue.Text("PictTool"), readAll(file)[MAKE])
    }

    @Test
    fun 目标里没有的字段落盘后被删掉() {
        val file = sampleFile()
        val before = readAll(file)
        assertTrue("样本应含 Make", before.containsKey(MAKE))

        store.writeTo(ExifInterface(file), MetadataSet(infoOf(file), before - MAKE))

        assertNull(readAll(file)[MAKE])
    }

    @Test
    fun 未被改动的字段保持原值() {
        val file = sampleFile()
        val before = readAll(file)
        val model = before[MODEL]
        assertTrue("样本应含 Model", model != null)

        store.writeTo(ExifInterface(file), MetadataSet(infoOf(file), before - MAKE))

        assertEquals(model, readAll(file)[MODEL])
    }

    @Test
    fun 写入的时间能被原样读回() {
        val file = sampleFile()
        val before = readAll(file)
        val expected = LocalDateTime.of(2026, 9, 9, 21, 30, 0)

        val target = MetadataSet(
            infoOf(file),
            before + (DATE_TIME to TagValue.Timestamp(expected)),
        )
        val result = store.writeTo(ExifInterface(file), target)

        assertTrue(DATE_TIME in result.writtenKeys)
        val after = readAll(file)[DATE_TIME]
        assertTrue("读回应是时间值，实际 $after", after is TagValue.Timestamp)
        assertEquals(expected, (after as TagValue.Timestamp).value)
    }

    @Test
    fun 二进制值不写回且不影响其他字段() {
        val file = sampleFile()
        val before = readAll(file)
        val model = before[MODEL]

        val target = MetadataSet(
            infoOf(file),
            before + (MAKER_NOTE to TagValue.Binary("0A1B", 2)),
        )
        val result = store.writeTo(ExifInterface(file), target)

        assertTrue(MAKER_NOTE in result.droppedKeys)
        assertFalse(MAKER_NOTE in result.writtenKeys)
        // 丢弃 = 不写，而不是写一个编造的值；其他字段照常保留
        assertEquals(model, readAll(file)[MODEL])
    }

    @Test
    fun 超限字段按策略丢弃后其余字段照常写入() {
        val file = sampleFile()
        val before = readAll(file)

        // 7 万字符的注释远超 APP1 段上限；它属于非关键字段，应该被预算挡下来
        val huge = TagValue.Text("x".repeat(70_000))
        val target = MetadataSet(
            infoOf(file),
            before + (MAKE to TagValue.Text("PictTool")) + (USER_COMMENT to huge),
        )
        val result = store.writeTo(ExifInterface(file), target)

        assertTrue("超限字段应被丢弃：${result.writtenKeys.size} 项写入", USER_COMMENT in result.droppedKeys)
        assertTrue("其余字段照常写入", MAKE in result.writtenKeys)
        assertEquals(TagValue.Text("PictTool"), readAll(file)[MAKE])
    }

    private companion object {
        val MAKE = TagKey.of("EXIF:Make")
        val MODEL = TagKey.of("EXIF:Model")
        val DATE_TIME = TagKey.of("EXIF:DateTime")
        val MAKER_NOTE = TagKey.of("EXIF:MakerNote")
        val USER_COMMENT = TagKey.of("EXIF:UserComment")
    }
}
