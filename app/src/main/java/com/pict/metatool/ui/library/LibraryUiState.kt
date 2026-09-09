package com.pict.metatool.ui.library

import com.pict.metatool.domain.model.ImageItem

/**
 * 图库页状态（docs/02 §10 单向数据流：状态自 ViewModel 向下流，事件自界面向上抛）。
 *
 * 所有状态迁移都是**纯函数**：`withItems` / `toggled` / `withAllSelected` … 返回新实例，
 * 不碰 Android API，因此可以脱离 Robolectric 直接单测。
 */
data class LibraryUiState(
    /** 已导入的图片，保持导入顺序；同一 URI 只保留一次。 */
    val items: List<ImageItem> = emptyList(),
    /** 选中的 URI 集合（用字符串而不是 ImageItem，避免同一张图被重复导入后选中态错乱）。 */
    val selectedUris: Set<String> = emptySet(),
    /** 目录扫描进行中（此时 [items] 只显示已完成的部分）。 */
    val isScanning: Boolean = false,
    /** 扫描过程中已发现的数量，用于「正在扫描…已发现 N 张」。 */
    val scannedCount: Int = 0,
    /** 一次性提示，UI 弹完 Snackbar 后调用 `consumeMessage()` 清掉。 */
    val message: LibraryMessage? = null,
) {

    val totalCount: Int get() = items.size

    val selectedCount: Int get() = selectedUris.size

    val hasSelection: Boolean get() = selectedUris.isNotEmpty()

    val isAllSelected: Boolean get() = items.isNotEmpty() && selectedUris.size == items.size

    /** 选中的图片，顺序与 [items] 一致。 */
    val selectedItems: List<ImageItem> get() = items.filter { it.uri in selectedUris }

    /** 是否该显示空状态：既没有图，也没有正在扫描。 */
    val showEmptyState: Boolean get() = items.isEmpty() && !isScanning

    /**
     * 合并新导入的图片：按 [ImageItem.uri] 去重，重复导入同一张不会出现两条；
     * 新项追加在末尾（保持用户操作顺序）。
     */
    fun withItems(incoming: List<ImageItem>): LibraryUiState {
        if (incoming.isEmpty()) return this
        val seen = items.mapTo(mutableSetOf()) { it.uri }
        val merged = items + incoming.filter { seen.add(it.uri) }
        return if (merged.size == items.size) this else copy(items = merged)
    }

    /** 切换单张的选中态；不在列表里的 URI 忽略。 */
    fun toggled(uri: String): LibraryUiState {
        if (items.none { it.uri == uri }) return this
        val next = if (uri in selectedUris) selectedUris - uri else selectedUris + uri
        return copy(selectedUris = next)
    }

    /** 全选 / 取消全选（docs/06 §3.1 顶部「全选」）。 */
    fun withAllSelected(selected: Boolean): LibraryUiState =
        copy(selectedUris = if (selected) items.mapTo(mutableSetOf()) { it.uri } else emptySet())

    /** 清空选中态但保留图片（顶部「清除选择」）。 */
    fun withSelectionCleared(): LibraryUiState = copy(selectedUris = emptySet())

    /** 把选中的图片移出列表（只改内存状态，**不删文件**）。 */
    fun withoutSelected(): LibraryUiState {
        if (selectedUris.isEmpty()) return this
        return copy(
            items = items.filterNot { it.uri in selectedUris },
            selectedUris = emptySet(),
        )
    }

    /** 扫描状态与进度。 */
    fun withScanning(scanning: Boolean, found: Int = scannedCount): LibraryUiState =
        copy(isScanning = scanning, scannedCount = found)

    fun withMessage(message: LibraryMessage?): LibraryUiState = copy(message = message)
}

/**
 * 一次性提示。用类型而不是字符串，文案留给 UI 层从 `strings.xml` 取（docs/06 §7）。
 */
sealed interface LibraryMessage {

    /** 选完了但一张图都没有（用户取消、或选中的都不是图片）。 */
    data object ImportEmpty : LibraryMessage

    /** 目录授权/读取失败。 */
    data object ImportFailed : LibraryMessage

    /** 扫描达到单次上限，剩下的要再导一次。 */
    data class ScanTruncated(val limit: Int) : LibraryMessage
}
