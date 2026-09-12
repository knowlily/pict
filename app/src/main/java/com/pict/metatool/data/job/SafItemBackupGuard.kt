package com.pict.metatool.data.job

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.getOrElse
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.source.BackupManager
import com.pict.metatool.domain.job.ItemBackupGuard
import com.pict.metatool.domain.job.ItemBackupMark
import com.pict.metatool.domain.job.JobItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 覆写前备份的 SAF 实现（docs/01 FR-34、docs/05 §7）。域层只知道
 * [ItemBackupGuard] 这个接口，真正读源、写备份目录的活在 `data` 层这一头。
 *
 * **备份落在哪**：源文件所在的那棵树里，即 `<用户选的目录>/Pict/backup/<时间戳>/原名`。
 * 批量是**原位覆写**（FR-28：改的就是用户相册里那张），没有「另一个输出目录」，
 * 所以备份必须和源一起待在同一个树下 —— 否则撤销要从两个不同的授权里读，用户一旦
 * 只给了一个目录的权限，备份就白留了。
 *
 * **一次任务一个时间戳目录**：[at] 在构造时定一次，同一批任务的上百张副本都进同一个
 * `Pict/backup/<stamp>/`。这样「这次任务留了什么」在盘上一眼可见，清理也整齐
 * （用户想手动清掉某次批量，删一个目录就行）。
 *
 * **同一棵树里两次任务**：时间戳到了秒，同一秒内不会撞（撞了 [BackupManager.backup]
 * 也会改名而不是覆盖）。
 */
class SafItemBackupGuard(
    private val resolver: ContentResolver,
    private val at: LocalDateTime = BackupManager.nowUtc(),
) : ItemBackupGuard {

    private val backups = BackupManager(resolver)

    override suspend fun markFor(item: JobItem): PictResult<ItemBackupMark> =
        withContext(Dispatchers.IO) {
            val origin = runCatching { Uri.parse(item.uri) }.getOrNull()
                ?: return@withContext failureOf(PictError.FIELD_INVALID, "源地址读不出来：${item.uri}")

            val tree = treeUriOf(origin)
                ?: return@withContext failureOf(
                    PictError.BACKUP_MISSING,
                    "这个来源不在文件夹授权里，留不下备份：${item.uri}",
                )

            val entry = backups.backup(
                origin = origin,
                outputTreeUri = tree,
                at = at,
                // 用源自己的文件名与 MIME 建副本：撤销时人要在备份目录里认得出是哪张
                sourceName = item.source.displayName,
                sourceMime = item.source.mimeType,
            ).getOrElse { failure -> return@withContext PictResult.Failure(failure) }

            val copy = entry.files.firstOrNull()
                ?: return@withContext failureOf(PictError.IO_WRITE, "备份目录建了但没放进副本")
            successOf(ItemBackupMark(uri = copy.uri, folder = entry.folderName))
        }
}

/**
 * 从文档 URI 反推它所属的**树** URI（`content://<authority>/tree/<树根 id>`）。
 *
 * 为什么能从子项的地址反推：SAF 的子项地址本身就是
 * `content://<authority>/tree/<树根 id>/document/<子项 id>` 这个形状，
 * `DocumentProvider` 用前半段判断授权，所以「用户授权过的那棵树」=
 * 地址里的 `/tree/` 那一段。于是不必把树 URI 一路存进任务定义、
 * 再跟着 WorkManager 的入参传下来（那还要处理定义是先写的、授权是后给的）。
 *
 * 不是从树里出来的地址（手输的、别的 App 给的、provider 自定义形状的）返回 null：
 * 拿不到树就没法确定备份往哪写，宁可不写也不猜。
 */
internal fun treeUriOf(documentUri: Uri): Uri? = runCatching {
    val treeId = DocumentsContract.getTreeDocumentId(documentUri)
    val authority = documentUri.authority
    if (treeId.isNullOrBlank() || authority.isNullOrBlank()) {
        null
    } else {
        DocumentsContract.buildTreeDocumentUri(authority, treeId)
    }
}.getOrNull()
