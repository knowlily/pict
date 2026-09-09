package com.pict.metatool.ui.library

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图库状态迁移（docs/02 §10 单向数据流）。
 * 这些函数不碰 Android API，所以不需要 Robolectric：直接喂状态、断言新状态。
 */
class LibraryUiStateTest {

    private fun item(
        name: String,
        uri: String = "content://media/$name",
        size: Long? = 1024L,
        modified: Long? = 1_700_000_000_000L,
        writable: Boolean = true,
    ) = ImageItem(
        uri = uri,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        lastModified = modified,
        format = ImageFormatHint.JPEG,
        writable = writable,
        origin = ImageItem.Origin.FILE_PICKER,
    )

    @Test
    fun `初始状态是空的且显示空状态`() {
        val state = LibraryUiState()
        assertEquals(0, state.totalCount)
        assertFalse(state.hasSelection)
        assertTrue(state.showEmptyState)
        assertNull(state.message)
    }

    @Test
    fun `导入按 URI 去重并保持顺序`() {
        val a = item("a.jpg")
        val b = item("b.jpg")
        val state = LibraryUiState()
            .withItems(listOf(a, b))
            .withItems(listOf(a, item("c.jpg")))  // a 重复

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), state.items.map { it.displayName })
    }

    @Test
    fun `重复导入同一批返回同一实例`() {
        val a = item("a.jpg")
        val base = LibraryUiState().withItems(listOf(a))
        assertSame(base, base.withItems(listOf(a)))
        assertSame(base, base.withItems(emptyList()))
    }

    @Test
    fun `切换选中态`() {
        val state = LibraryUiState().withItems(listOf(item("a.jpg"), item("b.jpg")))
        val selected = state.toggled("content://media/a.jpg")
        assertEquals(setOf("content://media/a.jpg"), selected.selectedUris)

        val unselected = selected.toggled("content://media/a.jpg")
        assertFalse(unselected.hasSelection)
    }

    @Test
    fun `切换不在列表里的 URI 不产生变化`() {
        val state = LibraryUiState().withItems(listOf(item("a.jpg")))
        assertSame(state, state.toggled("content://media/ghost.jpg"))
    }

    @Test
    fun `全选与取消全选`() {
        val state = LibraryUiState().withItems(listOf(item("a.jpg"), item("b.jpg")))
        val all = state.withAllSelected(true)
        assertTrue(all.isAllSelected)
        assertEquals(2, all.selectedCount)
        assertFalse(all.withAllSelected(false).hasSelection)
    }

    @Test
    fun `空列表不算全选`() {
        assertFalse(LibraryUiState().withAllSelected(true).isAllSelected)
    }

    @Test
    fun `清除选择保留图片`() {
        val state = LibraryUiState()
            .withItems(listOf(item("a.jpg")))
            .toggled("content://media/a.jpg")
            .withSelectionCleared()

        assertFalse(state.hasSelection)
        assertEquals(1, state.totalCount)
    }

    @Test
    fun `移出选中的图片会同时清空选中态`() {
        val state = LibraryUiState()
            .withItems(listOf(item("a.jpg"), item("b.jpg"), item("c.jpg")))
            .withAllSelected(true)
            .toggled("content://media/b.jpg")   // b 取消选中
            .withoutSelected()                  // 移出 a、c

        assertEquals(listOf("b.jpg"), state.items.map { it.displayName })
        assertFalse(state.hasSelection)
    }

    @Test
    fun `选中项按列表顺序返回`() {
        val state = LibraryUiState()
            .withItems(listOf(item("a.jpg"), item("b.jpg"), item("c.jpg")))
            .toggled("content://media/c.jpg")
            .toggled("content://media/a.jpg")

        assertEquals(listOf("a.jpg", "c.jpg"), state.selectedItems.map { it.displayName })
    }

    @Test
    fun `扫描中不显示空状态并带进度`() {
        val state = LibraryUiState().withScanning(scanning = true, found = 12)
        assertTrue(state.isScanning)
        assertEquals(12, state.scannedCount)
        assertFalse(state.showEmptyState)

        val done = state.withScanning(scanning = false, found = 30)
        assertFalse(done.isScanning)
        assertTrue(done.showEmptyState)   // 扫描完了但一张没收到 → 空状态
    }

    @Test
    fun `提示是一次性的`() {
        val state = LibraryUiState().withMessage(LibraryMessage.ImportEmpty)
        assertEquals(LibraryMessage.ImportEmpty, state.message)
        assertNull(state.withMessage(null).message)
    }

    @Test
    fun `截断提示带上限数量`() {
        val message = LibraryMessage.ScanTruncated(500)
        assertEquals(500, message.limit)
    }
}
