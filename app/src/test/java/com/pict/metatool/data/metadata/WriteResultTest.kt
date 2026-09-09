package com.pict.metatool.data.metadata

import com.pict.metatool.domain.model.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/07 T2.1：写入结果统计。 */
class WriteResultTest {

    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")
    private val thumbnail = TagKey.of("EXIF:Thumbnail")

    @Test
    fun `默认没有丢弃项`() {
        val result = WriteResult(setOf(make, model))

        assertFalse(result.hasDropped)
        assertEquals("写入 2 项", result.summary())
    }

    @Test
    fun `有丢弃项时摘要给出两个数量`() {
        val result = WriteResult(setOf(make), setOf(thumbnail))

        assertTrue(result.hasDropped)
        assertEquals("写入 1 项，丢弃 1 项", result.summary())
    }

    @Test
    fun `空写入也是合法结果`() {
        val result = WriteResult(emptySet())

        assertFalse(result.hasDropped)
        assertEquals("写入 0 项", result.summary())
    }
}
