package com.pict.metatool.data.job

import com.pict.metatool.domain.job.Job
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobStatus
import java.io.File

/**
 * 任务的**执行态**快照（T5.3）：通知栏、任务页、以及「杀进程后还知道上次跑到哪」
 * 都读它。与 [BatchJobSpec]（输入）分开：定义重排一次不该带着上一轮的结果。
 *
 * 只记计数与当前文件名，不记每个文件改了哪些键——那是 T5.8 报告的事，
 * 而进程随时可能被杀，快照要小到能一边跑一边往盘上写。
 */
data class JobSnapshot(
    val jobId: String,
    val label: String,
    val status: JobStatus,
    val total: Int,
    /** 已经落地（终态）的项数。 */
    val finished: Int,
    val running: Int,
    val succeeded: Int,
    val failed: Int,
    val verifyFailed: Int,
    val skipped: Int,
    val unsupported: Int,
    /** 累计变更字段数（dry-run 时是「将会变」）。 */
    val changedKeys: Int,
    /** 正在处理的那张（没有就是 null）。 */
    val currentName: String?,
    val dryRun: Boolean,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long?,
    val updatedAtMillis: Long,
    /**
     * 没能顺利落地的项（失败 / 校验未通过），最多 [MAX_FAILURES] 条，形如
     * `t53-a.jpg：匹配 45 项，值不符 3 项`。
     *
     * 计数看不出「为什么」，而 worker 跑完就没了；把它们带上，任务页/报告才有话可说
     * （完整报告是 T5.7）。只留前几条，快照要能一边跑一边写。
     */
    val failures: List<String> = emptyList(),

    /**
     * 任务进队列的时刻（`Job.createdAtMillis`）。
     *
     * 与 [startedAtMillis] 差着一整个排队阶段：任务页的「进行中」组按排队时间倒序，
     * 排了十分钟才轮到的和刚点下去的，在对列里的位置不一样。老快照里没这个键，
     * 缺了按 null 处理，界面退回用 [updatedAtMillis] 排。
     */
    val createdAtMillis: Long? = null,
) {

    val isTerminal: Boolean get() = status.isTerminal

    /** 0–100；总数为 0 时按状态给 0 或 100（空任务不是「卡在 0%」）。 */
    val percent: Int
        get() = when {
            total <= 0 -> if (isTerminal) 100 else 0
            else -> (finished.toLong() * 100L / total.toLong()).toInt().coerceIn(0, 100)
        }

    fun toJson(): String = buildString {
        append('{')
        append("\"v\":").append(VERSION).append(',')
        append("\"jobId\":").append(quote(jobId)).append(',')
        append("\"label\":").append(quote(label)).append(',')
        append("\"status\":").append(quote(status.name)).append(',')
        append("\"total\":").append(total).append(',')
        append("\"finished\":").append(finished).append(',')
        append("\"running\":").append(running).append(',')
        append("\"succeeded\":").append(succeeded).append(',')
        append("\"failed\":").append(failed).append(',')
        append("\"verifyFailed\":").append(verifyFailed).append(',')
        append("\"skipped\":").append(skipped).append(',')
        append("\"unsupported\":").append(unsupported).append(',')
        append("\"changedKeys\":").append(changedKeys).append(',')
        append("\"currentName\":").append(currentName?.let(::quote) ?: "null").append(',')
        append("\"dryRun\":").append(dryRun).append(',')
        // 排队时间：加了这一个键不用升版本，读的一头对缺字段一向当 null
        append("\"createdAt\":").append(createdAtMillis ?: 0L).append(',')
        append("\"startedAt\":").append(startedAtMillis ?: 0L).append(',')
        append("\"finishedAt\":").append(finishedAtMillis ?: 0L).append(',')
        append("\"updatedAt\":").append(updatedAtMillis).append(',')
        append("\"failures\":[")
        failures.forEachIndexed { index, note ->
            if (index > 0) append(',')
            append(quote(note))
        }
        append(']')
        append('}')
    }

    companion object {

        const val VERSION = 1

        fun of(job: Job, nowMillis: Long): JobSnapshot {
            val progress = job.progress(nowMillis)
            return JobSnapshot(
                jobId = job.id,
                label = job.label,
                status = job.status,
                total = progress.total,
                finished = progress.finished,
                running = progress.running,
                succeeded = progress.succeeded,
                failed = progress.failed,
                verifyFailed = progress.verifyFailed,
                skipped = progress.skipped,
                unsupported = progress.unsupported,
                changedKeys = job.items.sumOf { it.changedKeys.size },
                currentName = progress.currentName,
                dryRun = job.options.dryRun,
                createdAtMillis = job.createdAtMillis,
                startedAtMillis = job.startedAtMillis,
                finishedAtMillis = job.finishedAtMillis,
                updatedAtMillis = nowMillis,
                failures = job.items
                    .filter { it.status == JobItemStatus.FAILED || it.status == JobItemStatus.VERIFY_FAILED }
                    .take(MAX_FAILURES)
                    .map { item ->
                        // 优先用 note（Runner 给「重试/取消」之类写的短句），其次 detail
                        //（校验未通过时带的是「哪几类对不上、哪几个键」），都没有才退回状态名
                        val what = item.note?.takeIf { it.isNotBlank() }
                            ?: item.detail?.takeIf { it.isNotBlank() }
                            ?: item.status.name
                        "${item.source.displayName}：$what"
                    },
            )
        }

        /**
         * 收尾那一版快照：worker 的 `finally` 用它，别直接写 `of(last, now())`。
         *
         * 为什么：[JobWorker] 里那个 200 ms 轮询 `isStopped` 的看门狗**抢不过 WorkManager**——
         * 取消一个 unique work 时框架会直接把 worker 的协程取消掉，于是域层那张
         * CANCELED 的终态根本没机会发出来（收集协程先死了），`last` 上留的还是 RUNNING。
         * 只写 `of(last, …)` 的话，记录就永远停在「已处理 11/19」，未开工项也永远挂着。
         * 所以收尾时补一刀：**被叫停且没到终态，就按取消记账**
         * （未开工项记 SKIPPED，与域层 [Job.cancel] 同一套语义）。
         */
        fun endOf(last: Job, stopped: Boolean, nowMillis: Long): JobSnapshot =
            of(if (stopped && !last.isTerminal) last.cancel(nowMillis) else last, nowMillis)

        /** 快照里最多留几条失败原因。 */
        const val MAX_FAILURES = 5

        fun fromJson(text: String): JobSnapshot? = runCatching {
            val node = kotlinx.serialization.json.Json.parseToJsonElement(text).let {
                it as? kotlinx.serialization.json.JsonObject ?: return null
            }
            fun str(key: String): String? =
                (node[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

            fun int(key: String): Int = str(key)?.toIntOrNull() ?: 0
            fun longOrNull(key: String): Long? = str(key)?.toLongOrNull()?.takeIf { it != 0L }

            JobSnapshot(
                jobId = str("jobId") ?: return null,
                label = str("label").orEmpty(),
                status = str("status")?.let { name -> JobStatus.entries.firstOrNull { it.name == name } }
                    ?: return null,
                total = int("total"),
                finished = int("finished"),
                running = int("running"),
                succeeded = int("succeeded"),
                failed = int("failed"),
                verifyFailed = int("verifyFailed"),
                skipped = int("skipped"),
                unsupported = int("unsupported"),
                changedKeys = int("changedKeys"),
                currentName = str("currentName"),
                dryRun = str("dryRun")?.toBooleanStrictOrNull() ?: false,
                createdAtMillis = longOrNull("createdAt"),
                startedAtMillis = longOrNull("startedAt"),
                finishedAtMillis = longOrNull("finishedAt"),
                updatedAtMillis = str("updatedAt")?.toLongOrNull() ?: 0L,
                failures = (node["failures"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { element ->
                        (element as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    .orEmpty(),
            )
        }.getOrNull()

        private fun quote(text: String): String = buildString {
            append('"')
            text.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
    }
}

/**
 * 快照的落盘处：`filesDir/jobs/<jobId>.snapshot.json`。
 *
 * 与定义分开两个文件，是为了让「读定义」永远只看到输入、不掺执行态；
 * 也让任务重排（同一个定义再来一次）只覆盖快照。
 */
class JobSnapshotStore(private val dir: File) {

    constructor(context: android.content.Context) : this(File(context.filesDir, JobSpecStore.DIR))

    fun save(snapshot: JobSnapshot) {
        if (!JobSpecStore.isSafeId(snapshot.jobId)) return
        runCatching {
            if (!dir.exists() && !dir.mkdirs()) return
            val target = fileOf(snapshot.jobId)
            val tmp = File(dir, "${snapshot.jobId}$SUFFIX.tmp")
            tmp.writeText(snapshot.toJson())
            if (target.exists() && !target.delete()) return
            tmp.renameTo(target)
        }
    }

    fun load(jobId: String): JobSnapshot? {
        if (!JobSpecStore.isSafeId(jobId)) return null
        val file = fileOf(jobId)
        if (!file.isFile) return null
        return runCatching { JobSnapshot.fromJson(file.readText()) }.getOrNull()
    }

    fun delete(jobId: String) {
        if (!JobSpecStore.isSafeId(jobId)) return
        fileOf(jobId).delete()
        File(dir, "$jobId$SUFFIX.tmp").delete()
    }

    /** 最近一次更新过的快照（任务页回来时看它）。 */
    fun latest(): JobSnapshot? = all().firstOrNull()

    /** 全部快照，最近更新的排前面（T5.8 的历史列表按它排）。 */
    fun all(): List<JobSnapshot> = runCatching {
        dir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file -> JobSnapshot.fromJson(file.readText()) }
            .sortedByDescending { it.updatedAtMillis }
    }.getOrDefault(emptyList())

    fun fileOf(jobId: String): java.io.File = java.io.File(dir, "$jobId$SUFFIX")

    companion object {
        const val SUFFIX = ".snapshot.json"
    }
}
