package com.pict.metatool.ui.detail

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情页状态迁移（docs/07 T1.10）。 */
class DetailUiStateTest {

    private val source = SourceInfo("IMG_0001.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG)

    private fun metadataOf(vararg entries: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = source,
        entries = entries.associate { (key, value) -> TagKey.of(key) to value },
    )

    @Test
    fun `初始状态为空且不加载`() {
        val state = DetailUiState()
        assertNull(state.uri)
        assertNull(state.metadata)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(DetailTab.OVERVIEW, state.selectedTab)
        assertTrue(state.overviewRows.isEmpty())
        assertTrue(state.sections.isEmpty())
        assertFalse(state.showEmptyState)
    }

    @Test
    fun `withLoading 记录 uri 并清掉上一次的错误`() {
        val state = DetailUiState().withError(PictError.IO_READ).withLoading("content://media/1")
        assertEquals("content://media/1", state.uri)
        assertTrue(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `withLoaded 填入来源与元数据并结束加载`() {
        val metadata = metadataOf("EXIF:Make" to TagValue.Text("Apple"))
        val state = DetailUiState()
            .withLoading("content://media/1")
            .withLoaded(source, metadata, mapOf(TagKey.of("EXIF:Make") to listOf("exif")))

        assertFalse(state.isLoading)
        assertEquals("IMG_0001.jpg", state.title)
        assertTrue(state.hasMetadata)
        assertEquals(1, state.overviewRows.size)
        assertEquals(listOf("exif"), state.origins.getValue(TagKey.of("EXIF:Make")))
    }

    @Test
    fun `withError 结束加载并保留 uri 以便重试`() {
        val state = DetailUiState()
            .withLoading("content://media/1")
            .withError(PictError.META_PARSE, detail = "EOF")

        assertFalse(state.isLoading)
        assertEquals("content://media/1", state.uri)
        assertEquals(PictError.META_PARSE, state.error)
        assertEquals("EOF", state.errorDetail)
    }

    @Test
    fun `selectTab 只改当前页`() {
        val state = DetailUiState()
            .withLoaded(source, metadataOf("EXIF:Make" to TagValue.Text("Apple")))
            .selectTab(DetailTab.GPS)

        assertEquals(DetailTab.GPS, state.selectedTab)
        assertEquals("IMG_0001.jpg", state.title)
    }

    @Test
    fun `sections 跟随选中页变化`() {
        val metadata = metadataOf(
            "EXIF:Make" to TagValue.Text("Apple"),
            "GPS:GPSLatitude" to TagValue.RationalList(listOf(Rational(31, 1))),
        )

        val exif = DetailUiState().withLoaded(source, metadata).selectTab(DetailTab.EXIF)
        val gps = exif.selectTab(DetailTab.GPS)

        assertEquals(listOf("相机"), exif.sections.map { it.title })
        assertEquals(listOf("位置"), gps.sections.map { it.title })
    }

    @Test
    fun `读完但没有可读字段时显示空状态`() {
        val state = DetailUiState()
            .withLoading("content://media/1")
            .withLoaded(source, MetadataSet.empty())

        assertFalse(state.hasMetadata)
        assertTrue(state.showEmptyState)
    }

    @Test
    fun `加载中与出错时不显示空状态`() {
        assertFalse(DetailUiState().withLoading("content://media/1").showEmptyState)
        assertFalse(DetailUiState().withError(PictError.META_PARSE).showEmptyState)
    }

    @Test
    fun `提示消息可设置与清除`() {
        val shown = DetailUiState().withMessage(DetailMessage.Copied("坐标"))
        assertEquals(DetailMessage.Copied("坐标"), shown.message)
        assertNull(shown.withMessage(null).message)
    }
}
