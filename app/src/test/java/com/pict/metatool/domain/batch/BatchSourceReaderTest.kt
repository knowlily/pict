package com.pict.metatool.domain.batch

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/01 FR-32：dry-run 预览**不得改动任何文件、不得创建临时文件**。
 *
 * 这条约束不靠实现方自觉：预览能拿到的只有 [BatchSourceReader]，而它**没有写方法**，
 * 于是「预览顺手把文件改了」在类型层面就写不出来。本文件用反射把这个契约钉住——
 * 谁往接口上加写能力，这里先红，改动必须先在 ADR 里说清楚。
 */
class BatchSourceReaderTest {

    @Test
    fun `预览读取器只有读与纯判断两个方法`() {
        val methods = BatchSourceReader::class.java.declaredMethods.map { it.name }.toSet()

        assertEquals(
            "往预览读取器上加方法等于放开 FR-32 的口子（只允许 read + 纯判断 canWriteTo）",
            setOf("read", "canWriteTo"),
            methods,
        )
    }

    @Test
    fun `读是挂起函数，不在调用线程上同步读文件`() {
        val read = BatchSourceReader::class.java.declaredMethods.first { it.name == "read" }

        assertTrue(
            "read 应编译成挂起函数（末位 Continuation 参数）",
            read.parameterTypes.any { it.simpleName == "Continuation" },
        )
    }

    @Test
    fun `内存读取器默认认为预置的源都能原地写`() {
        val target = BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.JPEG)
        val reader = InMemorySourceReader(mapOf(target.uri to jpegSource()))

        assertTrue(reader.canWriteTo(target))
    }

    @Test
    fun `内存读取器读不到时返回 IO-OPEN 而不是抛异常`() = runBlocking {
        val target = BatchTarget.of("content://pict/missing.jpg", "missing.jpg")
        val reader = InMemorySourceReader(emptyMap())

        val failure = reader.read(target).failureOrNull()

        assertEquals(PictError.IO_OPEN, failure?.error)
    }

    @Test
    fun `内存读取器用名单把格式挡在门外`() {
        val target = BatchTarget.of("content://pict/b.heic", "b.heic", ImageFormatHint.HEIF)
        val reader = InMemorySourceReader(mapOf(target.uri to jpegSource()), writable = emptySet())

        assertFalse(reader.canWriteTo(target))
    }

    @Test
    fun `内存读取器记住每个源被读了几次`() = runBlocking {
        val target = BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.JPEG)
        val reader = InMemorySourceReader(mapOf(target.uri to jpegSource()))

        reader.read(target)
        reader.read(target)

        assertEquals(mapOf(target.uri to 2), reader.readCounts)
    }

    private fun jpegSource(): MetadataSet = MetadataSet(
        source = SourceInfo("a.jpg", "image/jpeg", 1_024L, ImageFormatHint.JPEG),
        entries = mapOf(TagKey.of("EXIF:Make") to TagValue.Text("Apple")),
    )
}
