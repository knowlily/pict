package com.pict.metatool.data.source

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import java.io.InputStream

/**
 * 一个待处理的图片来源（docs/07 T1.8 的输入侧）。
 *
 * [uri] 来自 SAF/MediaStore，V1 全程不申请存储宽权限（docs/05），因此这里**只持有
 * 已授予读权限的 URI**，不做路径假设——`content://` 之外一律视为不可读。
 */
data class ImageSource(
    val uri: Uri,
    val info: SourceInfo,
) {

    val displayName: String get() = info.displayName

    val format: ImageFormatHint get() = info.format

    val isContentUri: Boolean get() = uri.scheme == ContentResolver.SCHEME_CONTENT

    fun openStream(resolver: ContentResolver): InputStream? = resolver.openInputStream(uri)

    companion object {

        /** 从 URI 构造；文件名/MIME/大小能取到就填，取不到不抛错。 */
        fun from(resolver: ContentResolver, uri: Uri, fallbackName: String? = null): ImageSource {
            var name = fallbackName ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
            var size: Long? = null
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
            }
            val mime = resolver.getType(uri)
            val ext = name.substringAfterLast('.', "")
            return ImageSource(
                uri = uri,
                info = SourceInfo(
                    displayName = name.ifBlank { "(未命名)" },
                    mimeType = mime,
                    sizeBytes = size,
                    format = ImageFormatHint.fromMimeType(mime)
                        .takeIf { it != ImageFormatHint.UNKNOWN }
                        ?: ImageFormatHint.fromExtension(ext),
                ),
            )
        }
    }
}
