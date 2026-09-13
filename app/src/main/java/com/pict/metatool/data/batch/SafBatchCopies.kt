package com.pict.metatool.data.batch

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.job.treeUriOf
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.naming.CopyNaming
import com.pict.metatool.domain.naming.ExportNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 建出来的副本：地址 + 落地的名字（报告里要写「另存成什么」）。 */
data class CopyTarget(val uri: Uri, val name: String)

/**
 * 批量另存：**在源文件旁边新建一个副本**，而不是覆写源文件。
 *
 * 编辑页的「导出」有 SAF 的「新建文档」对话框兜着——用户自己挑目录、自己看名字。
 * 批量没有对话框（几百个文件不可能让用户点几百次），于是这里要自己把编辑页里
 * 那个对话框**隐含做掉的三件事**补上：
 *
 * 1. **放到哪**：源文件所在的那一层目录。从源 URI 反推它的 tree 与父 documentId，
 *    再拿父目录的文档 URI 去建。反推不出来（单个文件的只读授权、非 DocumentsProvider
 *    的 URI）就**如实报错**，不悄悄换到别的目录去——用户找不到自己的副本比报错更糟。
 * 2. **叫什么**：[ExportNaming.suggest] 出的名字，撞名由 [CopyNaming.unique] 往后排号，
 *    与编辑页导出的命名同一套规则。
 * 3. **留痕**：返回的名字会进逐项报告，用户能照着报告去把副本找回来。
 *
 * 权限上只做一件事：建文件。**不写源文件**，所以源是只读授权也照样能另存
 * （这正是「批量只读来源」在旧版本里被拦停的那个场景）。
 *
 * 与备份的关系：另存模式**不留备份**——原图从头到尾没被打开过写通道，
 * 没有「改坏了要回去」这回事，留一份只是白占空间。
 */
class SafBatchCopies(
    private val resolver: ContentResolver,
    private val suffix: String = ExportNaming.SUFFIX,
) {

    /**
     * 给 [item] 在它自己那一层目录里建一个空副本，返回新文档地址。
     *
     * 建空文档、不在这里搬字节：搬运由 `ImageCopy` 负责（它是编辑页导出用的同一个函数），
     * 这一层只管「名字与位置」这一件事。
     */
    suspend fun createFor(item: JobItem): PictResult<CopyTarget> = withContext(Dispatchers.IO) {
        val origin = runCatching { Uri.parse(item.uri) }.getOrNull()
            ?: return@withContext failureOf(PictError.FIELD_INVALID, "源地址读不出来：${item.uri}")

        val tree = treeUriOf(origin)
            ?: return@withContext failureOf(
                PictError.STORAGE_READONLY,
                "这个来源不在文件夹授权里，放不下副本：${item.source.displayName}",
            )

        val parentId = parentDocumentIdOf(origin)
            ?: return@withContext failureOf(
                PictError.STORAGE_READONLY,
                "认不出这张图在哪一层目录，放不下副本：${item.source.displayName}",
            )

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val desired = ExportNaming.suggest(item.source.displayName, suffix)
        // 排不开号就别建：建了会被 Provider 悄悄改名，报告里的名字与盘上的对不上
        val name = CopyNaming.unique(desired, childNamesOf(tree, parentId))
            ?: return@withContext failureOf(
                PictError.IO_WRITE,
                "「$desired」这个名字的号在这层目录里排到 99 都没排开，换个输出目录试试",
            )
        val mime = item.source.mimeType?.takeIf { it.isNotBlank() } ?: FALLBACK_MIME

        val created = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mime, name)
        }.getOrNull()
            ?: return@withContext failureOf(
                PictError.STORAGE_READONLY,
                "建不出副本（这一层目录不让写）：$name",
            )

        successOf(CopyTarget(uri = created, name = name))
    }

    /**
     * 撤掉一个**自己刚建出来、还没写完整**的副本（失败回滚）。
     *
     * 只在自己人手上用：这个 URI 是本类刚 `createFor` 出来的。写坏了的半截文件如果留着，
     * 在相册里看着就是一张「能打开但内容不对」的照片——比报错更容易骗到人。
     *
     * @return 真删掉了给 true；删不掉（Provider 不给删、已经被系统回收）给 false——
     *   调用方据此决定报告里说「已撤掉」还是「残留了一份，请手动删」。
     */
    suspend fun discard(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    /**
     * 这层目录里现在已经有哪些名字（小写）。
     *
     * 只查**直接子项**：副本跟源同层，只可能跟同层的东西撞。查不到就当空集合，
     * 撞名的后果由 [DocumentsContract.createDocument] 兜底（Provider 通常自己会补编号）。
     */
    private fun childNamesOf(tree: Uri, parentId: String): Set<String> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { add(it.lowercase()) }
                }
            }
        } ?: emptySet()
    }.getOrDefault(emptySet())

    companion object {

        private const val FALLBACK_MIME = "application/octet-stream"

        /**
         * 源文档所在的**父目录** documentId，反推不出来给 null。
         *
         * tree 授权的 documentId 形如 `primary:Download/pict-samples/a.jpg`，
         * 砍掉最后一段就是父目录。只有一段（`primary:Download` 这种直接把整层当文档的奇怪 Provider）
         * 或压根不是 tree 文档时，返回 null —— 宁可报错也不往根上写。
         */
        fun parentDocumentIdOf(documentUri: Uri): String? = runCatching {
            DocumentsContract.getDocumentId(documentUri)
                .substringBeforeLast('/', missingDelimiterValue = "")
                .ifBlank { null }
        }.getOrNull()
    }
}
