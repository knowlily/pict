package com.pict.metatool.domain.job

import com.pict.metatool.core.result.PictResult
import java.time.LocalDateTime

/**
 * 覆写前的备份与「从备份撤销」（docs/07 T5.8、docs/01 FR-34、docs/05 §7）。
 *
 * 分三块，都在这个文件里，因为它们说的是同一件事的三种时态：
 * - [ItemBackupMark] / [ItemBackupGuard]：**写之前**留一份（data 层实现，域层只说「留了没有」）；
 * - [JobBackupStamp] / [JobUndoRules]：**写之后**判定「哪一条能撤」（纯函数，边界都在单测里）；
 * - [JobUndo]：真要撤的时候，按报告算出「恢复哪几张、删哪几张」。
 *
 * 为什么撤销要从**报告**取地址而不是去扫备份目录：备份目录里只有文件名，
 * 同名文件会被改名成 `IMG_1 (2).jpg`，靠名字反推目标目录是猜。
 * 报告是任务自己写的账，哪一项写到哪个 URI 是它记下来的。
 */

/**
 * 一次备份的落点（写进任务项与报告）。
 *
 * @param uri 备份副本自己的地址（恢复时读它）
 * @param folder 时间戳目录名（`20260912_101530`）：显示与过期判定都用它
 */
data class ItemBackupMark(
    val uri: String,
    val folder: String,
)

/**
 * 「覆写这张之前，先把它现在的样子存一份」。
 *
 * 实现要读源、写备份目录，所以在 data 层；域层只依赖这个接口，
 * 于是一个假的 guard 就能把「备份失败 → 这一项不许写」这条规则钉进单测。
 */
fun interface ItemBackupGuard {

    /**
     * 留一份 [item] 的备份。
     *
     * 失败必须**如实返回失败**：备份的意义就是「万一写坏了能回去」，
     * 备份没成还照写，等于把回滚能力悄悄丢掉——这类静默降级比直接报错更伤人。
     */
    suspend fun markFor(item: JobItem): PictResult<ItemBackupMark>
}

/**
 * 把备份落点挂到结果上。
 *
 * 三档都要挂，一档都不能漏：成功、校验未通过（值写进去了）、失败（可能写了一半）。
 * 撤销的定义是「回到执行前的样子」，不是「把成功的反着做一遍」——留下备份的那些项
 * 一律要能还原。跳过/不支持/试运行本来就没动文件，硬挂一个地址只会让报告出现
 * 「没写但可恢复」这种说不清的行。
 */
fun ItemResult.withBackup(mark: ItemBackupMark?): ItemResult = when {
    mark == null -> this
    this is ItemResult.Done -> copy(backupUri = mark.uri, backupFolder = mark.folder)
    this is ItemResult.VerifyFailed -> copy(backupUri = mark.uri, backupFolder = mark.folder)
    this is ItemResult.Failed -> copy(backupUri = mark.uri, backupFolder = mark.folder)
    else -> this
}

/**
 * 盘上的一次任务备份。
 *
 * @param jobId 哪次任务留的（来自报告里的 `backupFolder` + 任务 id 的对账）
 * @param files 目录里的副本数
 */
data class JobBackupStamp(
    val jobId: String,
    val folder: String,
    val createdAt: LocalDateTime,
    val files: Int,
)

/** FR-34 的判定规则，纯函数。 */
object JobUndoRules {

    /** 保留期（天）；与 `BackupManager.RETENTION_DAYS` 同一个数，两处都钉了边界单测。 */
    const val RETENTION_DAYS: Long = 7L

    /**
     * 是否过期。区间取 `[createdAt, createdAt + days)`：正好满 7 天算过期，
     * 与 `BackupManager.isExpired` 完全一致——同一句话不能有两个边界。
     */
    fun isExpired(
        createdAt: LocalDateTime,
        now: LocalDateTime,
        days: Long = RETENTION_DAYS,
    ): Boolean = !now.isBefore(createdAt.plusDays(days))

    /**
     * 把「能不能撤」标到该标的那一条上。
     *
     * 规则一句话：**只有最近一次留了备份的那条能撤**（FR-34）。其余情况分别标成
     * [UndoState.UNDONE] / [UndoState.NOT_LATEST] / [UndoState.NO_BACKUP] /
     * [UndoState.EXPIRED] / [UndoState.DRY_RUN]，好让界面说清楚为什么。
     *
     * 「最近」按备份时间戳比，不按任务创建时间：用户可能先跑了一次不带备份的旧任务，
     * 再跑了一次带备份的——能撤的是后者，哪怕它在列表里不是第一行。
     *
     * 撤过的那次**照旧算「最近」**：撤销之后文件已经回到执行前，这时再去恢复更早那一次
     * 的备份，等于把更早的那批改动也一并回退——那是另一次撤销，不是这一次的补充。
     */
    fun apply(
        entries: List<JobHistoryEntry>,
        stamps: List<JobBackupStamp>,
        now: LocalDateTime,
        days: Long = RETENTION_DAYS,
    ): List<JobHistoryEntry> {
        val byJob = stamps.groupBy { it.jobId }
            .mapValues { (_, group) -> group.maxByOrNull { it.createdAt }!! }
        val latest = byJob.values.maxByOrNull { it.createdAt }

        return entries.map { entry ->
            val stamp = byJob[entry.jobId]
            when {
                // 撤过的：不再给按钮，但备份目录名照旧显示（人要知道上一步做了什么）
                entry.undoneAtMillis != null -> entry.copy(
                    undoState = UndoState.UNDONE,
                    backupFolder = stamp?.folder ?: entry.backupFolder,
                    backupFiles = stamp?.files ?: entry.backupFiles,
                )

                // 试运行没写过文件：有没有备份都不该给撤销按钮
                entry.dryRun -> entry.copy(undoState = UndoState.DRY_RUN)
                stamp == null -> entry.copy(undoState = UndoState.NO_BACKUP)
                latest == null || stamp.jobId != latest.jobId ->
                    entry.copy(
                        undoState = UndoState.NOT_LATEST,
                        backupFolder = stamp.folder,
                        backupFiles = stamp.files,
                    )

                isExpired(stamp.createdAt, now, days) -> entry.copy(
                    undoState = UndoState.EXPIRED,
                    backupFolder = stamp.folder,
                    backupFiles = stamp.files,
                )

                else -> entry.copy(
                    undoState = UndoState.AVAILABLE,
                    backupFolder = stamp.folder,
                    backupFiles = stamp.files,
                )
            }
        }
    }
}

/** 撤销要做的一件事。 */
sealed interface UndoStep {

    val name: String

    /** 把备份副本的内容写回目标（覆盖）——这是「回到执行前」。 */
    data class Restore(
        val targetUri: String,
        val backupUri: String,
        override val name: String,
    ) : UndoStep

    /** 删掉本次新建的文件（导出副本）；目标是源文件时永远不会走到这一档。 */
    data class Delete(
        val targetUri: String,
        override val name: String,
    ) : UndoStep
}

/** 一次撤销的完整计划；空计划 = 没什么可撤的。 */
data class JobUndoPlan(
    val jobId: String,
    val steps: List<UndoStep>,
) {

    val isEmpty: Boolean get() = steps.isEmpty()

    val size: Int get() = steps.size

    val restores: List<UndoStep.Restore> get() = steps.filterIsInstance<UndoStep.Restore>()

    val deletes: List<UndoStep.Delete> get() = steps.filterIsInstance<UndoStep.Delete>()
}

/** 按报告算撤销计划。 */
object JobUndo {

    /**
     * 从报告算出「要恢复哪几张、要删哪几张」。
     *
     * 两条规则：
     * 1. **留了备份的项一律恢复**，不看它当时成没成——撤销的定义是「回到执行前的样子」，
     *    不是「把成功的那些反着做一遍」。写失败的那张本来就没变，恢复一遍是幂等的。
     * 2. 没有备份、但有 [JobReportItem.outputUri] 的项，删掉那个副本。
     *    批量现在是原位覆写（没有副本），这一档留给导出/另存类任务。
     *
     * 报告里缺 URI 的项（旧版本写的报告）直接跳过：地址都没有，动手就是瞎猜。
     */
    fun plan(report: JobReport): JobUndoPlan {
        if (report.dryRun) return JobUndoPlan(report.jobId, emptyList())

        val steps = report.items.mapNotNull { item ->
            val target = item.uri
            val backup = item.backupUri
            when {
                target.isNullOrBlank() -> null
                !backup.isNullOrBlank() -> UndoStep.Restore(
                    targetUri = target,
                    backupUri = backup,
                    name = item.name,
                )

                !item.outputUri.isNullOrBlank() && item.outputUri != target ->
                    UndoStep.Delete(targetUri = item.outputUri, name = item.name)

                else -> null
            }
        }
        return JobUndoPlan(report.jobId, steps)
    }
}
