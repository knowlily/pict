package com.pict.metatool.ui.detail

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.metadata.MetadataReader
import com.pict.metatool.data.source.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页 ViewModel（docs/07 T1.10）。
 *
 * 职责边界：只做编排。取文件信息（[ImageSource.from]）与读字段（[MetadataReader.read]）
 * 都是阻塞调用，统一切到 `Dispatchers.IO`；状态迁移全在 [DetailUiState] 的纯函数里。
 *
 * 导航只带 uri，不带整个 [com.pict.metatool.domain.model.ImageItem]：
 * 详情页要展示的文件名、大小、MIME 都能从 uri 现取，避免把状态塞进路由参数。
 */
class DetailViewModel(
    private val reader: MetadataReader,
    private val resolver: ContentResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** 载入一张图；同一张已读成功的不重复读。 */
    fun load(uri: String) {
        if (_state.value.uri == uri && _state.value.hasMetadata) return
        loadJob?.cancel()
        _state.value = _state.value.withLoading(uri)
        loadJob = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { ImageSource.from(resolver, Uri.parse(uri)) }
                val read = withContext(Dispatchers.IO) { reader.read(resolver, source) }
                when (read) {
                    is PictResult.Success -> _state.update {
                        it.withLoaded(source.info, read.value.set, read.value.origins)
                    }

                    is PictResult.Failure -> _state.update {
                        it.withError(read.error, read.failure.detail)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update { it.withError(PictError.UNKNOWN, t.message) }
            }
        }
    }

    /** 失败后重试当前 uri。 */
    fun retry() {
        val uri = _state.value.uri ?: return
        _state.value = _state.value.copy(metadata = null)
        load(uri)
    }

    fun selectTab(tab: DetailTab) {
        _state.update { it.selectTab(tab) }
    }

    /** 复制成功后的提示（剪贴板已由界面写入）。 */
    fun onCopied(label: String) {
        _state.update { it.withMessage(DetailMessage.Copied(label)) }
    }

    fun consumeMessage() {
        _state.update { it.withMessage(null) }
    }

    companion object {

        /** 无 DI 框架时的手工装配，与图库页一致（用 applicationContext 的 resolver）。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DetailViewModel(MetadataReader(), context.applicationContext.contentResolver)
            }
        }
    }
}
