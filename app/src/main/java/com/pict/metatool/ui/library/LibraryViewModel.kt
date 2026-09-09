package com.pict.metatool.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.data.source.SafSource
import com.pict.metatool.domain.model.ImageItem
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
 */
class LibraryViewModel(private val safSource: SafSource) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val scanCancelled = AtomicBoolean(false)
    private var scanJob: Job? = null

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

    /** 目录选择返回的树 URI：先持久化授权，再扫描。 */
    fun importFolder(treeUri: Uri) {
        val granted = safSource.takePersistablePermission(treeUri)
        if (!granted) {
            _state.update { it.withMessage(LibraryMessage.ImportFailed) }
            return
        }
        startScan(treeUri)
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
         * 用 `applicationContext` 的 ContentResolver，避免持有 Activity。
         */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(SafSource(context.applicationContext.contentResolver))
            }
        }
    }
}
