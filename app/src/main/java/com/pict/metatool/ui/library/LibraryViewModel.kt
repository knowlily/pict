package com.pict.metatool.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.data.settings.SettingsProvider
import com.pict.metatool.data.settings.SettingsStore
import com.pict.metatool.data.source.SafSource
import com.pict.metatool.data.source.TreeDocumentName
import com.pict.metatool.domain.model.ImageItem
import com.pict.metatool.domain.settings.RecentFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图库页 ViewModel（docs/07 T1.9）。
 *
 * 职责边界：**只做编排**。URI 查询与目录遍历都在 [SafSource] 里，
 * 这里负责切线程（`Dispatchers.IO`）、把结果并进 [LibraryUiState]、管扫描取消。
 *
 * 线程约定：`SafSource` 是同步阻塞的，所有调用都包在 [withContext] 里；
 * 扫描取消用 [AtomicBoolean] 而不是 `Job.cancel()`——遍历循环里查的是这个标志，
 * 协程取消只能保证循环退出后不再更新状态，打断不了正在跑的 ContentResolver 查询。
 *
 * 「最近目录」（FR-03）也在这里记账：目录名由 [SafSource.treeDisplayName] 问 Provider，
 * 问不到用 [TreeDocumentName.fromUri] 从 URI 里抠；两处都拿不出名字时**不记**
 * ——记一条点不开的记录，不如没有。
 *
 * @param now 取当前时刻，测试里换成固定时钟（排序用得上，别让它读真实时间）。
 */
class LibraryViewModel(
    private val safSource: SafSource,
    private val settingsStore: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val scanCancelled = AtomicBoolean(false)
    private var scanJob: Job? = null

    init {
        // 设置里存的最近目录是唯一事实来源：本页只管显示与「用过就刷新时间」，
        // 这样设置页将来要清空重建时，这里跟着变，不用两边各存一份。
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.withRecentFolders(settings.recentFolders) }
            }
        }
    }

    // ---------------- 导入 ----------------

    /** 文件选择 / 相册选择返回的 URI。 */
    fun importUris(uris: List<Uri>, origin: ImageItem.Origin) {
        if (uris.isEmpty()) {
            _state.update { it.withMessage(LibraryMessage.ImportEmpty) }
            return
        }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { safSource.itemsFromUris(uris, origin) }
            _state.update { current ->
                if (items.isEmpty()) {
                    current.withMessage(LibraryMessage.ImportEmpty)
                } else {
                    current.withItems(items)
                }
            }
        }
    }

    /** 目录选择返回的树 URI：先持久化授权，再记账、再扫描。 */
    fun importFolder(treeUri: Uri) {
        val granted = safSource.takePersistablePermission(treeUri)
        if (!granted) {
            _state.update { it.withMessage(LibraryMessage.ImportFailed) }
            return
        }
        rememberFolder(treeUri)
        startScan(treeUri)
    }

    /**
     * 打开一个最近目录（FR-03：「杀进程重启后仍可直接访问该目录」）。
     *
     * 授权是持久化的，但**持久不等于一直有效**：卸载重装、系统清理、用户在系统设置里
     * 撤掉这个目录，都会让 `persistedUriPermissions` 少一条（docs/05）。所以每次用之前
     * 都验一次，失效就提示重新选一次——记录本身留着，重新授权之后它又能用了。
     */
    fun openRecentFolder(folder: RecentFolder) {
        val treeUri = runCatching { Uri.parse(folder.uri) }.getOrNull()
        if (treeUri == null || !safSource.hasPersistedPermission(treeUri)) {
            _state.update { it.withMessage(LibraryMessage.FolderPermissionLost) }
            return
        }
        // 打开成功说明这条记录是活的：把它顶到最前面（时间刷新，不是新加一条）
        settingsStore.update { it.withRecentFolder(folder.uri, folder.name, now()) }
        startScan(treeUri)
    }

    /** 把走通的目录记进设置；名字两次都取不到就跳过（宁可不记，也不记一条没名字的）。 */
    private fun rememberFolder(treeUri: Uri) {
        val rawUri = treeUri.toString()
        val name = safSource.treeDisplayName(treeUri) ?: TreeDocumentName.fromUri(rawUri) ?: return
        settingsStore.update { it.withRecentFolder(rawUri, name, now()) }
    }

    private fun startScan(treeUri: Uri) {
        scanJob?.cancel()
        scanCancelled.set(false)
        _state.update { it.withScanning(scanning = true, found = 0) }

        scanJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                safSource.scanTree(
                    treeUri = treeUri,
                    onProgress = { found ->
                        _state.update { it.withScanning(scanning = true, found = found) }
                    },
                    isCancelled = { scanCancelled.get() },
                )
            }
            _state.update { current ->
                var next = current
                    .withScanning(scanning = false, found = result.items.size)
                    .withItems(result.items)
                when {
                    result.truncated -> next = next.withMessage(LibraryMessage.ScanTruncated(SafSource.MAX_ITEMS))
                    result.items.isEmpty() -> next = next.withMessage(LibraryMessage.ImportEmpty)
                }
                next
            }
        }
    }

    /** 扫描中用户点了「取消」：下次进入目录时退出循环，已发现的保留。 */
    fun cancelScan() {
        scanCancelled.set(true)
    }

    // ---------------- 选中 ----------------

    fun toggleSelection(uri: String) {
        _state.update { it.toggled(uri) }
    }

    fun toggleSelectAll() {
        _state.update { it.withAllSelected(selected = !it.isAllSelected) }
    }

    fun clearSelection() {
        _state.update { it.withSelectionCleared() }
    }

    /** 把选中的移出列表（不动文件，docs/06 §3.1）。 */
    fun removeSelected() {
        _state.update { it.withoutSelected() }
    }

    fun consumeMessage() {
        _state.update { it.withMessage(null) }
    }

    companion object {

        /**
         * 无 DI 框架时的手工装配（Hilt 已在版本目录里但尚未接线，docs/07 T0.x）。
         * 用 `applicationContext` 的 ContentResolver，避免持有 Activity；
         * 设置走 [SettingsProvider] 的单例，设置页改完这里立刻能看到。
         */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    safSource = SafSource(context.applicationContext.contentResolver),
                    settingsStore = SettingsProvider.of(context.applicationContext),
                )
            }
        }
    }
}
