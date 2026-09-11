package com.pict.metatool.data.job

import android.content.Context
import com.pict.metatool.domain.job.JobReport
import java.io.File

/**
 * 任务报告的落盘（T5.7）：`files/jobs/<jobId>.report.json`，与任务定义同目录。
 *
 * 为什么跟定义放一起：这三份东西（定义 / 快照 / 报告）讲的是同一个任务的三个面，
 * 放一处才好一起列、一起清（清理策略见 `JobSpecStore` 那边）。
 *
 * 写盘套路与 [JobSnapshotStore] 一致：先写 `.tmp` 再改名——中途被杀不会留半份 JSON
 * 冒充完整报告。**大小**不必像快照那样克制：报告只在收尾时写一次，不在热路径上。
 *
 * ★ 注意它与 [JobSpecStore.SUFFIX] 的关系：`<id>.report.json` 也以 `.json` 结尾，
 * 任务列表扫 id 时必须把它排除（`JobSpecStore.ids()` 里已经包含这一条），
 * 否则任务列表上会冒出一个叫 `job-x.report` 的假任务。
 */
class JobReportStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, JobSpecStore.DIR))

    fun save(report: JobReport) {
        if (!JobSpecStore.isSafeId(report.jobId)) return
        runCatching {
            if (!dir.exists() && !dir.mkdirs()) return
            val target = fileOf(report.jobId)
            val tmp = File(dir, "${report.jobId}$SUFFIX.tmp")
            tmp.writeText(JobReportJson.write(report))
            if (target.exists() && !target.delete()) return
            tmp.renameTo(target)
        }
    }

    fun load(jobId: String): JobReport? {
        if (!JobSpecStore.isSafeId(jobId)) return null
        val file = fileOf(jobId)
        if (!file.isFile) return null
        return runCatching { JobReportJson.read(file.readText()) }.getOrNull()
    }

    fun delete(jobId: String) {
        if (!JobSpecStore.isSafeId(jobId)) return
        fileOf(jobId).delete()
        File(dir, "$jobId$SUFFIX.tmp").delete()
    }

    fun fileOf(jobId: String): File = File(dir, "$jobId$SUFFIX")

    companion object {

        const val SUFFIX = ".report.json"
    }
}
