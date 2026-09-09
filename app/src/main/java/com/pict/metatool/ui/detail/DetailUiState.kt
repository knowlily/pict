package com.pict.metatool.ui.detail

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey

/**
 * 详情页状态（docs/02 §10 单向数据流）。
 *
 * 所有迁移都是纯函数，界面只读 [DetailUiState] —— 因此本文件与 [DetailTab]、
 * [OverviewBuilder]、[MetadataSectionBuilder] 一起可以脱离 Android 框架做 JVM 单测。
 */
data class DetailUiState(
    /** 当前图片的 URI 字符串；重新加载同一张图时用于去重。 */
    val uri: String? = null,
    /** 文件本身的信息（文件名 / MIME / 大小），由 `ImageSource.from` 取得。 */
    val source: SourceInfo? = null,
    /** 读取到的全部字段；null 表示还没读完（区别于「读到了但是空的」）。 */
    val metadata: MetadataSet? = null,
    /** 字段来源标注（低 → 高优先级），末位为最终生效的读取器。 */
    val origins: Map<TagKey, List<String>> = emptyMap(),
    val isLoading: Boolean = false,
    /** 失败类型，界面据此取本地化文案。 */
    val error: PictError? = null,
    /** 失败细节（底层异常摘要），仅在诊断时展示。 */
    val errorDetail: String? = null,
    val selectedTab: DetailTab = DetailTab.OVERVIEW,
    /** 一次性提示（复制坐标成功等），消费后由界面清空。 */
    val message: DetailMessage? = null,
    /** 搜索模式是否打开（顶栏换成输入框，内容区显示命中结果）。 */
    val searchActive: Boolean = false,
    /** 搜索框里的原文。 */
    val searchQuery: String = "",
) {

    val title: String get() = source?.displayName.orEmpty()

    /** 概览页的行；没读完或没字段时为空。 */
    val overviewRows: List<OverviewRow> get() = metadata?.let(OverviewBuilder::build).orEmpty()

    val hasMetadata: Boolean get() = metadata != null && !metadata.isEmpty

    /** 读完了、没报错，但一个可显示字段都没有 —— 界面显示空状态。 */
    val showEmptyState: Boolean
        get() = !isLoading && error == null && metadata != null && metadata.isEmpty

    /** 当前 Tab 的分组内容；概览页返回空（概览用 [overviewRows]）。 */
    val sections: List<MetadataSection>
        get() = metadata?.let { MetadataSectionBuilder.build(it, selectedTab, origins) }.orEmpty()

    /** 搜索命中的字段（跨全部 Tab）；没开搜索或查询串为空时为空。 */
    val searchHits: List<SearchHit>
        get() = if (!searchActive) {
            emptyList()
        } else {
            metadata?.let { MetadataSearch.query(it, searchQuery, origins) }.orEmpty()
        }

    /** 打开搜索：每次都从空查询开始，免得残留上一次的关键词。 */
    fun openSearch(): DetailUiState = copy(searchActive = true, searchQuery = "")

    fun closeSearch(): DetailUiState = copy(searchActive = false, searchQuery = "")

    fun withQuery(query: String): DetailUiState = copy(searchQuery = query)

    /** 进入加载态；顺带把上一次的 Tab 与错误清干净，避免换图后停在旧页。 */
    fun withLoading(uri: String): DetailUiState = DetailUiState(uri = uri, isLoading = true)

    fun withLoaded(
        source: SourceInfo,
        metadata: MetadataSet,
        origins: Map<TagKey, List<String>> = emptyMap(),
    ): DetailUiState = copy(
        source = source,
        metadata = metadata,
        origins = origins,
        isLoading = false,
        error = null,
        errorDetail = null,
    )

    fun withError(error: PictError, detail: String? = null): DetailUiState = copy(
        isLoading = false,
        error = error,
        errorDetail = detail,
    )

    fun selectTab(tab: DetailTab): DetailUiState = copy(selectedTab = tab)

    fun withMessage(message: DetailMessage?): DetailUiState = copy(message = message)
}

/** 详情页的一次性提示。 */
sealed interface DetailMessage {
    /** 复制到剪贴板成功；[label] 是复制内容的名字（如「坐标」）。 */
    data class Copied(val label: String) : DetailMessage
}
