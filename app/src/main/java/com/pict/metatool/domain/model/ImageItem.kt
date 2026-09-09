package com.pict.metatool.domain.model

/**
 * 一张待处理图片（docs/07 T1.8：URI → ImageItem）。
 *
 * 属于 domain 层，**不依赖任何 Android API**：URI 只以字符串保存，
 * 需要 `android.net.Uri` 时由 data 层用 `Uri.parse(item.uri)` 还原。
 * 这样用例层（LoadMetadataUseCase 等）与 JVM 单测都不必碰 Android 桩。
 *
 * [writable] 是**导入时的快照**：SAF 授权可能随时被撤销，真正写入前仍要
 * 用 [com.pict.metatool.data.source.UriAccess.isWritable] 重新探测（docs/05 §5.1）。
 */
data class ImageItem(
    /** `content://...` 全串。 */
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    /** 修改时间，epoch millis；取不到为 null（排序时排最后）。 */
    val lastModified: Long?,
    val format: ImageFormatHint,
    /** 导入时是否可写（SAF `FLAG_SUPPORTS_WRITE`）。 */
    val writable: Boolean,
    val origin: Origin,
    /** SAF documentId，目录扫描时用于定位；非 SAF 来源为 null。 */
    val documentId: String? = null,
) {

    /** 小写扩展名，不含点；无扩展名时为空串。 */
    val extension: String get() = displayName.substringAfterLast('.', "").lowercase()

    /**
     * 是否按图片对待：MIME 以 `image/` 开头，或扩展名落在 [ImageFormatHint] 已知集合内。
     * 不要求 [format] 非 UNKNOWN——RAW 之外的冷门扩展名仍可能是图片。
     */
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true || format != ImageFormatHint.UNKNOWN

    /** 供元数据读取链使用（[SourceInfo] 不含修改时间）。 */
    fun toSourceInfo(): SourceInfo = SourceInfo(
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        format = format,
    )

    /** 导入途径。报告导出（FR-31）按此区分「选文件」与「扫目录」。 */
    enum class Origin(val label: String) {
        FILE_PICKER("文件选择"),
        PHOTO_PICKER("相册选择"),
        FOLDER_SCAN("目录扫描"),
    }
}
