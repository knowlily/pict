package com.pict.metatool.data.job

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.onFailure
import com.pict.metatool.core.result.onSuccess
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.source.BackupFile
import com.pict.metatool.data.source.BackupManager
import com.pict.metatool.domain.job.JobUndo
import com.pict.metatool.domain.job.UndoStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次撤销干完之后的账。
 *
 * @param restored 从备份写回去的项数
 * @param deleted 删掉的新建文件数（导出/另存类任务才会走到这一档）
 * @param failures 没做成的项，形如 `t53-a.jpg：文件找不到了`，给界面原样说出来
 */
data class JobUndoOutcome(
    val jobId: String,
    val restored: Int,
    val deleted: Int,
    val failures: List<String> = emptyList(),
) {

    /** 真正动过的项数。这数大于 0，历史行才会标成「已撤销」。 */
    val changed: Int get() = restored + deleted

    /** 有没有留下没做成的项。 */
    val isClean: Boolean get() = failures.isEmpty()
}

/**
 * 执行撤销（T5.8、docs/01 FR-34）：按报告的账把文件放回执行前的样子。
 *
 * 分工：**算账在域层**（[JobUndo.plan] 是纯函数，谁该恢复谁该删都定好了），
 * 这一层只管动手——读备份副本、写回目标、删掉新建的文件。之所以要分开，
 * 是因为「恢复哪些」这件事值得单测钉死，而它一旦掺进 ContentResolver 就没法单测了。
 *
 * 调用方负责**只对 `UndoState.AVAILABLE` 的那一行**调本方法（FR-34：只撤最近一次）。
 * 这里不重算「是不是最近」——那要扫全盘的备份时间戳，重算一遍等于把界面的判断
 * 抄成第二份。这里只挡一条**硬事实**：这条任务已经撤过了。
 *
 * 备份**不删**：恢复完就删掉副本，万一恢复出来的文件不对，人就再没有退路了。
 * 一份副本几 MB，交给保留期（7 天）自然清理。
 */
class JobUndoRunner(
    private val resolver: ContentResolver,
    private val reports: JobReportStore,
    private val backups: BackupManager = BackupManager(resolver),
) {

    constructor(context: Context) : this(context.contentResolver, JobReportStore(context))

    /**
     * 撤销 [jobId] 这一次任务。
     *
     * @param nowMillis 记账用的时刻；从外面传进来，测试才能钉住写进报告的值
     */
    suspend fun run(
        jobId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): PictResult<JobUndoOutcome> = withContext(Dispatchers.IO) {
        val report = reports.load(jobId)
            ?: return@withContext failureOf(
                PictError.BACKUP_MISSING,
                "这次任务的报告不在了：不知道当时改了哪些，撤不了",
            )

        // 已经撤过：备份是一次性的，再恢复一次等于把「撤销后的样子」又改回去
        if (report.undoneAtMillis != null) {
            return@withContext failureOf(PictError.UNDO_DONE, "这次任务已经撤过了")
        }

        val plan = JobUndo.plan(report)
        if (plan.isEmpty) {
            return@withContext failureOf(PictError.BACKUP_MISSING, "这次任务没有留下能恢复的备份")
        }

        var restored = 0
        var deleted = 0
        var firstError: PictError? = null
        val failures = mutableListOf<String>()

        plan.steps.forEach { step ->
            when (step) {
                is UndoStep.Restore -> backups.restore(
                    BackupFile(uri = step.backupUri, name = step.name, sizeBytes = null),
                    Uri.parse(step.targetUri),
                ).onSuccess { restored++ }
                    .onFailure { failure ->
                        // 副本被清理、授权失效、目标被挪走……都会走到这里。
                        // 逐项记下是谁、为什么，别用一个「撤销失败」把二十张图糊过去
                        firstError = firstError ?: failure.error
                        failures += "${step.name}：${failure.detail ?: failure.code}"
                    }

                is UndoStep.Delete ->
                    if (deleteFile(Uri.parse(step.targetUri))) {
                        deleted++
                    } else {
                        firstError = firstError ?: PictError.IO_WRITE
                        failures += "${step.name}：这个副本没删掉"
                    }
            }
        }

        // 一步都没成：文件还是原样，不记账。按钮留着，等用户处理完（重新授权之类）再来
        if (restored == 0 && deleted == 0) {
            return@withContext failureOf(
                firstError ?: PictError.UNKNOWN,
                failures.firstOrNull() ?: "撤销没能改动任何一项",
            )
        }

        // 撤成一部分也**算撤过**：写回去的那几张已经是既成事实，账必须先记下来。
        // 不然按钮还在，用户再按一次——备份是一次性的，第二次会把撤销后的样子又改回去。
        // 没收尾的那几项由界面照 [JobUndoOutcome.failures] 逐条说出来
        reports.save(report.copy(undoneAtMillis = nowMillis))

        successOf(
            JobUndoOutcome(
                jobId = jobId,
                restored = restored,
                deleted = deleted,
                failures = failures,
            ),
        )
    }

    /**
     * 删掉一个我们新建的文件。
     *
     * 两条路都试：SAF 树里的东西走 `DocumentsContract.deleteDocument` 最直接；
     * 有些 provider 只肯认 `ContentResolver.delete`（媒体库那类不是 document URI）。
     * 挑一条然后指望 provider 讲道理，不如两条都比一遍。
     */
    private fun deleteFile(uri: Uri): Boolean {
        val byDocument = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .getOrDefault(false)
        if (byDocument) return true
        return runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0
    }
}
