package com.pict.metatool.data.job

import android.content.Context
import com.pict.metatool.data.source.BackupManager
import com.pict.metatool.domain.job.JobBackupStamp
import com.pict.metatool.domain.job.JobHistoryEntry
import com.pict.metatool.domain.job.JobReport
import java.time.LocalDateTime

/**
 * 历史列表的装配（T5.8、docs/06 §3.8）。
 *
 * 任务页要两样东西，都从这里出：
 * 1. **条目**——一行一次任务（[JobHistoryEntry]）。快照给计数与终态、定义文件给
 *    「还能不能再跑」、报告给「撤没撤过」。
 * 2. **备份时间戳**——撤销判定要的那份账（[JobBackupStamp]）。它也来自报告：
 *    逐项记的 `backupFolder` 就是留备份时那个时间戳目录名。
 *
 * 为什么时间戳不另立一个索引文件：**多一份索引就多一处会跟事实不一致的东西**。
 * 报告是撤销动手时要读的那一份（地址在里面），时间戳从它推出来，永远不会出现
 * 「索引说能撤、报告里没地址」这种自相矛盾。代价是每个任务都要读一次报告——
 * 报告是几 KB 的小文件，量级是几十份，扫一遍比维护索引便宜。
 *
 * 这一层只**装配**，不排序、不裁切、不判「能不能撤」：那是域层
 * [com.pict.metatool.domain.job.JobHistory.from] 与
 * [com.pict.metatool.domain.job.JobUndoRules.apply] 的事，规则要在纯函数里钉边界。
 */
class JobHistoryStore(
    private val snapshots: JobSnapshotStore,
    private val specs: JobSpecStore,
    private val reports: JobReportStore,
) {

    constructor(context: Context) : this(
        JobSnapshotStore(context),
        JobSpecStore(context),
        JobReportStore(context),
    )

    /**
     * 一次扫盘装出来的两样东西。
     *
     * 合成一个返回值是为了**只读一遍报告**：条目要看它（撤没撤过、备份在哪），
     * 时间戳也要看它（哪次算最近）。分两个方法各扫一遍盘，等于把同一批文件读两回。
     */
    data class Loaded(
        val entries: List<JobHistoryEntry>,
        val stamps: List<JobBackupStamp>,
    )

    fun load(): Loaded {
        // 定义文件在不在盘上：重跑的判据。一次列完，别在循环里反复问盘
        val specIds = specs.ids().toSet()
        val snapshotList = snapshots.all()
        // 报告只读一遍：条目和时间戳都是用同一份报告推出来的
        val reportsByJob = snapshotList.associate { it.jobId to reports.load(it.jobId) }
        return assemble(snapshotList, specIds) { jobId -> reportsByJob[jobId] }
    }

    companion object {

        /**
         * 纯装配：把已经读到手的快照、定义文件名、报告拼成条目与时间戳。
         *
         * 从 [load] 里拆出来是为了能对着单测跑——盘那一头（三个 store）在 JVM 单测里
         * 起不来，而排序、聚合、「哪条算最近」这些真正容易写错的地方都在这儿。
         */
        fun assemble(
            snapshots: List<JobSnapshot>,
            specIds: Set<String>,
            reportOf: (String) -> JobReport?,
        ): Loaded {
            val entries = mutableListOf<JobHistoryEntry>()
            val stamps = mutableListOf<JobBackupStamp>()

            snapshots.forEach { snapshot ->
                val report = reportOf(snapshot.jobId)
                val folders = foldersOf(report)
                val latest = latestFolderOf(folders)

                entries += JobHistoryEntry(
                    jobId = snapshot.jobId,
                    label = snapshot.label,
                    status = snapshot.status,
                    // 排队时间缺失（老快照）时退回「开始」再退回「最后更新」：
                    // 宁可排得糙一点，也不能让这几行全体沉到列表底下去
                    createdAtMillis = snapshot.createdAtMillis
                        ?: snapshot.startedAtMillis
                        ?: snapshot.updatedAtMillis,
                    finishedAtMillis = snapshot.finishedAtMillis,
                    total = snapshot.total,
                    succeeded = snapshot.succeeded,
                    failed = snapshot.failed,
                    verifyFailed = snapshot.verifyFailed,
                    skipped = snapshot.skipped,
                    unsupported = snapshot.unsupported,
                    changedKeysTotal = snapshot.changedKeys,
                    dryRun = snapshot.dryRun,
                    hasSpec = snapshot.jobId in specIds,
                    undoneAtMillis = report?.undoneAtMillis,
                    backupFolder = latest,
                    backupFiles = latest?.let { folders.getValue(it) } ?: 0,
                )

                folders.forEach { (folder, files) ->
                    // 解析不出时间戳的目录名不进账：撤销判定靠它的先后定「最近」，
                    // 认不出来的目录宁可当它不存在，也不能瞎猜一个位置
                    val createdAt = BackupManager.parseFolderName(folder) ?: return@forEach
                    stamps += JobBackupStamp(
                        jobId = snapshot.jobId,
                        folder = folder,
                        createdAt = createdAt,
                        files = files,
                    )
                }
            }

            return Loaded(entries = entries, stamps = stamps)
        }

        /**
         * 报告里按时间戳目录聚合的副本数（目录名 → 项数）。
         *
         * 一份报告可能横跨多个目录（跑到一半过了几秒、重试另起一炉），
         * 所以是「报告 → 若干个目录」，不是一个目录。
         */
        private fun foldersOf(report: JobReport?): Map<String, Int> =
            report?.items.orEmpty()
                .mapNotNull { item -> item.backupFolder?.takeIf { it.isNotBlank() } }
                .groupingBy { it }
                .eachCount()

        /** 时间戳最晚的那个目录（就是这一行的「最新一次备份」）。 */
        private fun latestFolderOf(folders: Map<String, Int>): String? =
            folders.keys.maxByOrNull { BackupManager.parseFolderName(it) ?: LocalDateTime.MIN }
    }
}
