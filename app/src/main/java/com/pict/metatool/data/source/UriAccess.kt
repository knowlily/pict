package com.pict.metatool.data.source

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * URI 可写性与持久授权的探测（docs/02 §2 `data/source/UriAccess.kt`、docs/05 §5.1）。
 *
 * 原则：不猜路径、不申请宽权限，一切以 [ContentResolver] 查询结果为准。
 * minSdk = 26，因此 docs/05 §5.1 里 `API < 24` 的兜底分支不需要。
 */
object UriAccess {

    /**
     * 目标是否可写：查 SAF 的 `COLUMN_FLAGS`，看 `FLAG_SUPPORTS_WRITE`。
     *
     * 查询失败（云盘 URI 不支持、授权已撤销）一律返回 false —— 调用方据此降级为另存，
     * 而不是硬写一把再抛 `SecurityException`（docs/05 §6）。
     */
    fun isWritable(resolver: ContentResolver, uri: Uri): Boolean {
        val flags = readFlags(resolver, uri) ?: return false
        return canWrite(flags)
    }

    /** 是否支持删除（覆盖流程里清理临时文档时用）。 */
    fun isDeletable(resolver: ContentResolver, uri: Uri): Boolean {
        val flags = readFlags(resolver, uri) ?: return false
        return (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
    }

    /** 纯位判断，便于单测。 */
    fun canWrite(flags: Int): Boolean =
        (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0

    /** 是否是 SAF 文档 URI（而非 MediaStore/云盘 URI）。 */
    fun isDocumentUri(uri: Uri): Boolean = runCatching { DocumentsContract.getDocumentId(uri) }.isSuccess

    /** 是否是目录树 URI（`ACTION_OPEN_DOCUMENT_TREE` 的返回值）。 */
    fun isTreeUri(uri: Uri): Boolean = DocumentsContract.isTreeUri(uri)

    /** 读 `COLUMN_FLAGS`；取不到返回 null。 */
    private fun readFlags(resolver: ContentResolver, uri: Uri): Int? = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
        }
    }.getOrNull()

    // ---- 持久授权（docs/05 §3.2）----

    /** 申请持久读写授权。失败返回 false（部分 Provider 只给读权限）。 */
    fun takePersistablePermission(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }.isSuccess

    /** 授权是否仍然有效（用户撤销、App 重装后会失效）。 */
    fun hasPersistedPermission(
        resolver: ContentResolver,
        uri: Uri,
        requireWrite: Boolean = false,
    ): Boolean = resolver.persistedUriPermissions.any { p ->
        p.uri == uri && p.isReadPermission && (!requireWrite || p.isWritePermission)
    }

    /** 当前仍有效的持久授权目录（用于「最近目录」列表的失效清理）。 */
    fun persistedUris(resolver: ContentResolver, requireWrite: Boolean = false): List<Uri> =
        resolver.persistedUriPermissions
            .filter { p -> p.isReadPermission && (!requireWrite || p.isWritePermission) }
            .map { it.uri }

    fun releasePersistedPermission(resolver: ContentResolver, uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
