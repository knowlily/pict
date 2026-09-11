package com.pict.metatool.data.source

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.ImageItem

/**
 * SAF 文件访问层（docs/07 T1.8、docs/05）。
 *
 * 覆盖四件事：
 * 1. **导入意图**：单/多选 `ACTION_OPEN_DOCUMENT`、目录 `ACTION_OPEN_DOCUMENT_TREE`（§3.1/3.2）；
 * 2. **URI → [ImageItem]**：查询名称/MIME/大小/修改时间/可写位；
 * 3. **目录遍历**：`buildChildDocumentsUriUsingTree` + 查询，深度 ≤ [MAX_DEPTH]，
 *    跳过隐藏目录与 `Android/`、`LOST.DIR`，单次上限 [MAX_ITEMS]（§4）；
 * 4. **纯函数规则**：目录跳过、图片判定、排序 —— 全部是 JVM 可测的静态逻辑。
 *
 * 说明：遍历**同步**执行且不自己切线程，调用方（UseCase/ViewModel）负责放到
 * `Dispatchers.IO` 上；取消通过 [scanTree] 的 `isCancelled` 回调检查，每处理一个目录检查一次。
 */
class SafSource(private val resolver: ContentResolver) {

    // ---------------- 1. 导入意图 ----------------

    /** 单张/多张选择；返回的 URI 带临时读权限，不做持久化（docs/05 §3.1）。 */
    fun openDocumentsIntent(multiple: Boolean = true): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES)
    }

    /** 目录选择；调用方在回调里用 [takePersistablePermission] 持久化授权。 */
    fun openTreeIntent(initialUri: Uri? = null): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }

    fun takePersistablePermission(treeUri: Uri): Boolean =
        UriAccess.takePersistablePermission(resolver, treeUri)

    fun hasPersistedPermission(treeUri: Uri, requireWrite: Boolean = false): Boolean =
        UriAccess.hasPersistedPermission(resolver, treeUri, requireWrite)

    /**
     * 树 URI 的目录显示名（「最近目录」记账用，FR-03）。
     *
     * 先问 Provider：`COLUMN_DISPLAY_NAME` 才是用户在系统选择器里看到的那串字。
     * 问不到（Provider 不认这个 URI、或授权已经失效）就返回 null，让调用方用
     * [TreeDocumentName.fromUri] 兜底——不在这里编一个假名字，也不抛异常：
     * 记一个名字不该比扫描目录更容易失败。
     */
    fun treeDisplayName(treeUri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        resolver
            .query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null }
    }.getOrNull()

    // ---------------- 2. URI → ImageItem ----------------

    /**
     * 查询单个 URI 的属性。取不到关键列不抛错：名称退化为路径末段，其余留空。
     * URI 完全不可读时返回 null（docs/05 §6「源文件处理中被删除」）。
     */
    fun itemOf(uri: Uri, origin: ImageItem.Origin): ImageItem? {
        var name: String? = null
        var size: Long? = null
        var lastModified: Long? = null
        var flags = 0
        var documentId: String? = null
        val ok = runCatching {
            resolver.query(uri, QUERY_PROJECTION, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                c.columnOf(OpenableColumns.DISPLAY_NAME)?.let { name = c.getString(it) }
                c.columnOf(OpenableColumns.SIZE)?.let { size = c.getLong(it) }
                c.columnOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let { lastModified = c.getLong(it) }
                c.columnOf(DocumentsContract.Document.COLUMN_FLAGS)?.let { flags = c.getInt(it) }
                c.columnOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)?.let { documentId = c.getString(it) }
            }
        }.isSuccess
        if (!ok) return null

        return buildItem(
            uri = uri.toString(),
            displayName = name ?: uri.lastPathSegment?.substringAfterLast('/'),
            mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
            sizeBytes = size,
            lastModified = lastModified,
            flags = flags,
            origin = origin,
            documentId = documentId,
        )
    }

    /** 从选择器结果取全部 URI（多选走 `clipData`，单选走 `data`）。 */
    fun itemsFromPickResult(data: Intent?): List<ImageItem> {
        if (data == null) return emptyList()
        val uris = buildList {
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
            }
            if (isEmpty()) data.data?.let(::add)
        }
        return itemsFromUris(uris, ImageItem.Origin.FILE_PICKER)
    }

    /** 相册选择器（Photo Picker）返回的 URI 列表。 */
    fun itemsFromUris(uris: List<Uri>, origin: ImageItem.Origin): List<ImageItem> =
        uris.mapNotNull { itemOf(it, origin) }

    // ---------------- 3. 目录遍历 ----------------

    /**
     * 遍历目录树，收集图片。
     *
     * @param onProgress 每发现一批就回调累计数量（UI 显示「已发现 N 张」）；
     * @param isCancelled 每进入一个目录检查一次，返回 true 则停止并返回已收集结果。
     */
    fun scanTree(
        treeUri: Uri,
        sort: SortOrder = SortOrder.MODIFIED_DESC,
        onProgress: ((Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): ScanResult {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return ScanResult(emptyList(), truncated = false, visitedDirectories = 0)
        val items = mutableListOf<ImageItem>()
        var visited = 0
        var truncated = false

        // 显式栈，避免深层递归爆栈；元素是 (documentId, depth)。
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(rootId to 0)

        while (stack.isNotEmpty()) {
            if (isCancelled()) break
            val (docId, depth) = stack.removeLast()
            visited++
            val children = childrenOf(treeUri, docId) ?: continue

            for (child in children) {
                if (child.isDirectory) {
                    if (depth + 1 < MAX_DEPTH && !shouldSkipDirectory(child.name)) {
                        stack.addLast(child.documentId to (depth + 1))
                    }
                    continue
                }
                if (!isImageCandidate(child.name, child.mimeType)) continue
                if (items.size >= MAX_ITEMS) {
                    truncated = true
                    break
                }
                items += buildItem(
                    uri = child.uri,
                    displayName = child.name,
                    mimeType = child.mimeType,
                    sizeBytes = child.size,
                    lastModified = child.lastModified,
                    flags = child.flags,
                    origin = ImageItem.Origin.FOLDER_SCAN,
                    documentId = child.documentId,
                )
            }
            if (truncated) break
            onProgress?.invoke(items.size)
        }

        return ScanResult(sortItems(items, sort), truncated, visited)
    }

    /** 查一个目录的直接子项；查询失败返回 null（权限失效等），由调用方跳过。 */
    private fun childrenOf(treeUri: Uri, documentId: String): List<Child>? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        resolver.query(childrenUri, QUERY_PROJECTION, null, null, null)?.use { c ->
            val out = mutableListOf<Child>()
            while (c.moveToNext()) {
                val idIdx = c.columnOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                val id = c.getString(idIdx)
                val mime = c.columnOf(DocumentsContract.Document.COLUMN_MIME_TYPE)?.let(c::getString)
                out += Child(
                    documentId = id,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                    name = c.columnOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)?.let(c::getString) ?: "",
                    mimeType = mime,
                    size = c.columnOf(DocumentsContract.Document.COLUMN_SIZE)?.let(c::getLong),
                    lastModified = c.columnOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let(c::getLong),
                    flags = c.columnOf(DocumentsContract.Document.COLUMN_FLAGS)?.let(c::getInt) ?: 0,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
            out
        }
    }.getOrNull()

    /** 遍历中间态：一个直接子项。 */
    private data class Child(
        val documentId: String,
        val uri: String,
        val name: String,
        val mimeType: String?,
        val size: Long?,
        val lastModified: Long?,
        val flags: Int,
        val isDirectory: Boolean,
    )

    companion object {

        /** 递归深度上限（docs/05 §4）。 */
        const val MAX_DEPTH: Int = 5

        /** 单次导入上限，超出置 [ScanResult.truncated] 由 UI 提示分批（docs/05 §4）。 */
        const val MAX_ITEMS: Int = 500

        /** 明确不进入的目录名（大小写不敏感）。`Android/data`、`Android/obb` 都在 `Android/` 下。 */
        private val SKIP_DIRECTORY_NAMES = setOf("android", "lost.dir", "nomedia", "thumbnails")

        private val MIME_TYPES = arrayOf(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif",
            "image/bmp", "image/tiff", "image/avif",
        )

        private val QUERY_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
        )

        /**
         * 该目录名是否跳过：隐藏目录（`.` 开头，覆盖 `.thumbnails`）、
         * `Android/`、`LOST.DIR` 等系统目录（docs/05 §4）。
         */
        fun shouldSkipDirectory(name: String): Boolean {
            val n = name.trim()
            if (n.isEmpty()) return true
            if (n.startsWith(".")) return true
            return n.lowercase() in SKIP_DIRECTORY_NAMES
        }

        /**
         * 是否收作图片：MIME 以 `image/` 开头直接收；否则看扩展名是否在
         * [ImageFormatHint] 已知集合内（docs/05 §4 的扩展名表）。
         * 目录的 MIME 是 `vnd.android.document/directory`，一律不收。
         */
        fun isImageCandidate(displayName: String?, mimeType: String?): Boolean {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) return false
            if (mimeType?.startsWith("image/") == true) return true
            val ext = displayName?.substringAfterLast('.', "") ?: return false
            return ImageFormatHint.fromExtension(ext) != ImageFormatHint.UNKNOWN
        }

        /**
         * 从查询列构造 [ImageItem]（纯函数，单测直接喂参数）。
         * 名称/扩展名都取不到时标 UNKNOWN，但仍然返回 —— 后续读取失败由读取链报告。
         */
        fun buildItem(
            uri: String,
            displayName: String?,
            mimeType: String?,
            sizeBytes: Long?,
            lastModified: Long?,
            flags: Int,
            origin: ImageItem.Origin,
            documentId: String? = null,
        ): ImageItem {
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: uri.substringAfterLast('/').ifBlank { "(未命名)" }
            val ext = name.substringAfterLast('.', "")
            val format = ImageFormatHint.fromMimeType(mimeType)
                .takeIf { it != ImageFormatHint.UNKNOWN }
                ?: ImageFormatHint.fromExtension(ext)
            return ImageItem(
                uri = uri,
                displayName = name,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                format = format,
                writable = UriAccess.canWrite(flags),
                origin = origin,
                documentId = documentId,
            )
        }

        /** 排序（docs/05 §4：默认修改时间倒序；时间缺失的排最后）。 */
        fun sortItems(items: List<ImageItem>, order: SortOrder): List<ImageItem> = when (order) {
            SortOrder.MODIFIED_DESC -> items.sortedWith(
                compareByDescending<ImageItem> { it.lastModified ?: Long.MIN_VALUE }
                    .thenBy { it.displayName.lowercase() }
            )

            SortOrder.NAME_ASC -> items.sortedBy { it.displayName.lowercase() }

            SortOrder.SIZE_DESC -> items.sortedWith(
                compareByDescending<ImageItem> { it.sizeBytes ?: -1L }
                    .thenBy { it.displayName.lowercase() }
            )
        }
    }
}

/** 遍历结果。 */
data class ScanResult(
    val items: List<ImageItem>,
    /** 是否因达到 [SafSource.MAX_ITEMS] 而截断。 */
    val truncated: Boolean,
    /** 实际进入的目录数（含根），用于日志/诊断。 */
    val visitedDirectories: Int,
)

/** 扫描结果排序方式。 */
enum class SortOrder(val label: String) {
    MODIFIED_DESC("修改时间（新→旧）"),
    NAME_ASC("名称"),
    SIZE_DESC("大小（大→小）"),
}

/** 按列名取下标；列不存在返回 null。 */
private fun android.database.Cursor.columnOf(name: String): Int? =
    getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }
