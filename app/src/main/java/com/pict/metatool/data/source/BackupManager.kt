package com.pict.metatool.data.source

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.getOrElse
import com.pict.metatool.core.result.successOf
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * 覆盖前备份与回滚（docs/07 T2.11、docs/05 §5.2 步骤 2、§7）。
 *
 * 目录约定（docs/05 §7）：
 * ```
 * <用户选择的输出目录>/
 * └─ Pict/backup/<yyyyMMdd_HHmmss>/<原名>
 * ```
 * 时间戳目录按 **UTC** 生成：设备改时区后目录顺序不会乱，也不会因本地时间回拨而
 * 覆盖掉同一时刻的备份。约定写死在 [folderName] / [parseFolderName]，两边必须同源。
 *
 * 与 [SafSource] 的分工：`SafSource` 的遍历面向「导入图片」——跳隐藏目录、只收图片、
 * 上限 500 张；备份要的是「精确定位 `Pict/backup/<时间戳>` 三级目录」，语义不同，
 * 所以这里自带一份最小 SAF 查询（列投影一致），不共用那套筛选规则。
 *
 * 撤销范围（docs/01 FR-34）：只支持**最近一次**，且超过 [RETENTION_DAYS] 天或备份被
 * 清理后不可用 —— 判定逻辑全部在 [availability]（纯函数，单测覆盖）；「删除本次新建的
 * 文件」那半边走任务报告的文件清单，不在本类。
 */
class BackupManager(private val resolver: ContentResolver) {

    // ---------------- 1. 写备份 ----------------

    /**
     * 把 [origin] 的字节复制到 `Pict/backup/<时间戳>/`。
     *
     * @param at 备份时刻，默认 [nowUtc]；同一秒内的二次备份会落进同一目录，靠
     *   [uniqueName] 改名而不是报错 —— 批量覆盖时同批同名文件很常见。
     * @return 本次备份的目录与副本信息（[BackupEntry.files] 只有这一份）
     */
    fun backup(
        origin: Uri,
        outputTreeUri: Uri,
        at: LocalDateTime = nowUtc(),
        sourceName: String? = null,
        sourceMime: String? = null,
    ): PictResult<BackupEntry> {
        val rootId = treeRootId(outputTreeUri)
            ?: return failureOf(PictError.STORAGE_READONLY, "目录授权不可用：$outputTreeUri")

        val pictDir = ensureDirectory(outputTreeUri, rootId, ROOT_DIR)
            .getOrElse { return PictResult.Failure(it) }
        val backupDir = ensureDirectory(outputTreeUri, pictDir.documentId, BACKUP_DIR)
            .getOrElse { return PictResult.Failure(it) }

        val folder = folderName(at)
        val stampDir = ensureDirectory(outputTreeUri, backupDir.documentId, folder)
            .getOrElse { return PictResult.Failure(it) }

        val taken = (childrenOf(outputTreeUri, stampDir.documentId) ?: emptyList()).map { it.name }
        val name = uniqueName(sourceName ?: origin.lastPathSegment?.substringAfterLast('/').orEmpty(), taken)

        val target = runCatching {
            DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(outputTreeUri, stampDir.documentId),
                sourceMime?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME,
                name,
            )
        }.getOrNull() ?: return failureOf(PictError.IO_WRITE, "创建备份副本失败：$folder/$name")

        val copied = copyInto(origin, target).getOrElse { return PictResult.Failure(it) }
        return successOf(
            BackupEntry(
                folderName = folder,
                createdAt = at,
                documentId = stampDir.documentId,
                files = listOf(BackupFile(uri = target.toString(), name = name, sizeBytes = copied)),
            ),
        )
    }

    // ---------------- 2. 读备份 ----------------

    /**
     * 列出 `Pict/backup/` 下的全部备份，按时间戳倒序。
     *
     * 目录名解析不出时间戳的一律跳过：那是用户或别的 App 放进来的目录，不是我们的备份。
     * `Pict/` 或 `backup/` 不存在时返回空列表而不是失败 —— 「还没备份过」不是错误。
     */
    fun listBackups(outputTreeUri: Uri): PictResult<List<BackupEntry>> {
        val rootId = treeRootId(outputTreeUri)
            ?: return failureOf(PictError.STORAGE_READONLY, "目录授权不可用：$outputTreeUri")

        val pictDir = findDirectory(outputTreeUri, rootId, ROOT_DIR) ?: return successOf(emptyList())
        val backupDir = findDirectory(outputTreeUri, pictDir.documentId, BACKUP_DIR)
            ?: return successOf(emptyList())

        val children = childrenOf(outputTreeUri, backupDir.documentId)
            ?: return failureOf(PictError.IO_READ, "无法列出 ${ROOT_DIR}/${BACKUP_DIR}")

        val entries = children.filter { it.isDirectory }.mapNotNull { dir ->
            val createdAt = parseFolderName(dir.name) ?: return@mapNotNull null
            val files = childrenOf(outputTreeUri, dir.documentId).orEmpty()
                .filterNot { it.isDirectory }
                .map { BackupFile(uri = it.uri, name = it.name, sizeBytes = it.size) }
            BackupEntry(folderName = dir.name, createdAt = createdAt, documentId = dir.documentId, files = files)
        }
        return successOf(entries.sortedByDescending { it.createdAt })
    }

    /**
     * FR-34 的入口：给出当前可撤销的那一份备份。
     *
     * - 一份都没有 → [PictError.BACKUP_MISSING]（用户从没开过备份，或已手动清理）
     * - 只有过期的 → [PictError.BACKUP_EXPIRED]
     * - 最新的没过期、但它更早的那些过期 → 仍然可用（撤销只认最近一次）
     */
    fun lastRestorable(
        outputTreeUri: Uri,
        now: LocalDateTime = nowUtc(),
        retentionDays: Long = RETENTION_DAYS,
    ): PictResult<BackupEntry> =
        listBackups(outputTreeUri)
            .getOrElse { return PictResult.Failure(it) }
            .let { availability(it, now, retentionDays) }

    // ---------------- 3. 恢复与清理 ----------------

    /** 把一份备份副本写回 [targetUri]（覆盖原文件场景下的回滚）。 */
    fun restore(backupFile: BackupFile, targetUri: Uri): PictResult<Long> =
        copyInto(Uri.parse(backupFile.uri), targetUri)

    /**
     * 清掉过了保留期的备份目录（docs/05 §7：保留 7 天）。
     *
     * 单个目录删除失败（授权失效、被占用）不中断、也不报错，只统计成功数 ——
     * 清理是后台动作，失败留到下次；但**不删除**解析不出时间戳的目录。
     */
    fun purgeExpired(
        outputTreeUri: Uri,
        now: LocalDateTime = nowUtc(),
        retentionDays: Long = RETENTION_DAYS,
    ): PictResult<Int> {
        val all = listBackups(outputTreeUri).getOrElse { return PictResult.Failure(it) }
        var removed = 0
        for (entry in expiredEntries(all, now, retentionDays)) {
            if (entry.documentId.isBlank()) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(outputTreeUri, entry.documentId)
            if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) removed++
        }
        return successOf(removed)
    }

    // ---------------- 4. SAF 细节 ----------------

    private fun treeRootId(treeUri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

    /** 查一个目录的直接子项；查询失败（授权失效等）返回 null，由调用方决定是空还是失败。 */
    private fun childrenOf(treeUri: Uri, documentId: String): List<Document>? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            val out = mutableListOf<Document>()
            while (c.moveToNext()) {
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                if (idIdx < 0 || c.isNull(idIdx)) continue
                val id = c.getString(idIdx)
                val mime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    .takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                out += Document(
                    documentId = id,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                    name = nameIdx.takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString).orEmpty(),
                    size = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        .takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong),
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
            out
        }
    }.getOrNull()

    private fun findDirectory(treeUri: Uri, parentDocumentId: String, name: String): Document? =
        childrenOf(treeUri, parentDocumentId)?.firstOrNull { it.isDirectory && it.name == name }

    /** 找到或创建 [parentDocumentId] 下的目录 [name]。 */
    private fun ensureDirectory(treeUri: Uri, parentDocumentId: String, name: String): PictResult<Document> {
        val children = childrenOf(treeUri, parentDocumentId)
            ?: return failureOf(PictError.STORAGE_READONLY, "无法列出目录内容（授权可能已失效）：$treeUri")
        children.firstOrNull { it.isDirectory && it.name == name }?.let { return successOf(it) }

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val created = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
        }.getOrNull() ?: return failureOf(PictError.IO_WRITE, "创建目录失败：$name")

        val id = runCatching { DocumentsContract.getDocumentId(created) }.getOrNull()
            ?: return failureOf(PictError.IO_WRITE, "创建目录失败：$name")
        return successOf(Document(documentId = id, uri = created.toString(), name = name, size = null, isDirectory = true))
    }

    /** 流式复制字节（64 KiB 一块）；失败时删掉半写的目标，不留垃圾文件。 */
    private fun copyInto(source: Uri, target: Uri): PictResult<Long> {
        val input = runCatching { resolver.openInputStream(source) }.getOrNull()
            ?: return failureOf(PictError.IO_OPEN, "无法读取源：$source")
        val output = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: run {
                runCatching { input.close() }
                return failureOf(PictError.IO_WRITE, "无法写入目标：$target")
            }

        return try {
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.flush()
            successOf(total)
        } catch (e: IOException) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            failureOf(if (e.isOutOfSpace()) PictError.STORAGE_FULL else PictError.IO_WRITE, "复制失败：$source", e)
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    /** 遍历中间态：一个直接子项。 */
    private data class Document(
        val documentId: String,
        val uri: String,
        val name: String,
        val size: Long?,
        val isDirectory: Boolean,
    )

    companion object {

        /** 备份根目录，位于用户选择的输出目录下（docs/05 §7）。 */
        const val ROOT_DIR: String = "Pict"

        /** 备份子目录名。 */
        const val BACKUP_DIR: String = "backup"

        /** 保留天数（docs/05 §7；后续可由设置覆盖）。 */
        const val RETENTION_DAYS: Long = 7

        /** 源文件名取不到时的兜底名（与 [ImageSource] 的「(未命名)」同口径）。 */
        const val DEFAULT_BACKUP_NAME: String = "(未命名)"

        private const val DEFAULT_MIME = "application/octet-stream"

        private const val BUFFER_SIZE = 64 * 1024

        /** 时间戳目录名长度：`yyyyMMdd_HHmmss`。 */
        private const val STAMP_LENGTH = 15

        private val STAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

        /**
         * 解析用的格式串故意用 `uuuu` 而不是 `yyyy`：`ResolverStyle.STRICT` 下 `yyyy`
         * 是 year-of-era，必须再给纪年才认，直接抛 `DateTimeException`。
         * STRICT 是必需的 —— SMART 会把 `20260230_000000` 悄悄顺延成 3 月 2 日。
         */
        private val STRICT_STAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss", Locale.US).withResolverStyle(ResolverStyle.STRICT)

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        // ---------------- 纯函数 ----------------

        /** 当前 UTC 时刻（备份时间戳一律走 UTC，见类注释）。 */
        fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

        /** 时间戳目录名，固定 15 字符。 */
        fun folderName(at: LocalDateTime): String = STAMP_FORMATTER.format(at)

        /** 解析时间戳目录名；格式不对或日期非法（如 20260230）返回 null。 */
        fun parseFolderName(name: String): LocalDateTime? {
            if (name.length != STAMP_LENGTH) return null
            return runCatching { LocalDateTime.parse(name, STRICT_STAMP_FORMATTER) }.getOrNull()
        }

        /**
         * 是否过了保留期。区间取 `[createdAt, createdAt + retentionDays)`：
         * 正好满 7 天算过期（FR-34「超过 7 天」在下一次判定上等价，且边界只此一个口径）。
         */
        fun isExpired(
            createdAt: LocalDateTime,
            now: LocalDateTime,
            retentionDays: Long = RETENTION_DAYS,
        ): Boolean = !now.isBefore(createdAt.plusDays(retentionDays))

        /** 最近一次备份；同为最大时间时取先出现的那个（[listBackups] 已倒序）。 */
        fun selectLatest(entries: List<BackupEntry>): BackupEntry? = entries.maxByOrNull { it.createdAt }

        /** 过了保留期的备份（待清理项），按时间升序。 */
        fun expiredEntries(
            entries: List<BackupEntry>,
            now: LocalDateTime,
            retentionDays: Long = RETENTION_DAYS,
        ): List<BackupEntry> = entries.filter { isExpired(it.createdAt, now, retentionDays) }
            .sortedBy { it.createdAt }

        /** FR-34 的判定：给出可撤销的最近一次备份，或说明为什么不可用。 */
        fun availability(
            entries: List<BackupEntry>,
            now: LocalDateTime,
            retentionDays: Long = RETENTION_DAYS,
        ): PictResult<BackupEntry> {
            val latest = selectLatest(entries)
                ?: return failureOf(PictError.BACKUP_MISSING, "$ROOT_DIR/$BACKUP_DIR 下没有备份")
            if (isExpired(latest.createdAt, now, retentionDays)) {
                return failureOf(
                    PictError.BACKUP_EXPIRED,
                    "最近一次备份是 ${folderName(latest.createdAt)}，已超过 $retentionDays 天保留期",
                )
            }
            return successOf(latest)
        }

        /**
         * 备份目录内的重名处理：`IMG_1.jpg` 已存在 → `IMG_1 (2).jpg` →
         * `IMG_1 (3).jpg`。只在没扩展名或点号不在开头时才把点号当扩展名分隔符
         * （`.jpg` 这种只有扩展名的名字整体当基名，否则会切出空基名）。
         */
        fun uniqueName(name: String, taken: Collection<String>): String {
            val clean = name.trim().ifBlank { DEFAULT_BACKUP_NAME }
            if (clean !in taken) return clean

            val dot = clean.lastIndexOf('.')
            val base = if (dot > 0) clean.substring(0, dot) else clean
            val suffix = if (dot > 0) clean.substring(dot) else ""
            var index = 2
            while (true) {
                val candidate = "$base ($index)$suffix"
                if (candidate !in taken) return candidate
                index++
            }
        }
    }
}

/** 备份目录中的一个副本。 */
data class BackupFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long?,
)

/**
 * 一次备份（`Pict/backup/<时间戳>/`）。
 *
 * [documentId] 是该目录的 SAF document ID，恢复/删除时要用；纯逻辑构造时留空即可。
 */
data class BackupEntry(
    val folderName: String,
    val createdAt: LocalDateTime,
    val documentId: String = "",
    val files: List<BackupFile> = emptyList(),
) {
    val isEmpty: Boolean get() = files.isEmpty()

    /** 副本总字节数；大小未知的按 0 计。 */
    val totalBytes: Long get() = files.sumOf { it.sizeBytes ?: 0L }
}

/** `ENOSPC` 在部分 provider 上只出现在消息里，errno 拿不到，只能按文本兜底。 */
private fun IOException.isOutOfSpace(): Boolean =
    message?.contains("ENOSPC", ignoreCase = true) == true || cause?.message?.contains("ENOSPC", ignoreCase = true) == true
