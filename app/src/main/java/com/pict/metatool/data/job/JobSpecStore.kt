package com.pict.metatool.data.job

import android.content.Context
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import java.io.File

/**
 * 任务定义的落盘处（T5.3）：`filesDir/jobs/<jobId>.json`。
 *
 * 存在**应用私有目录**，不是用户相册：任务定义是我们自己的账本，
 * 既不进媒体库，也不需要 SAF 授权。FR-32 禁止的是碰用户的图片（含图片旁的临时文件），
 * 与这里无关——dry-run 任务同样要有定义文件，否则杀掉进程后没人知道它在算什么。
 *
 * 写文件走「先写 .tmp 再改名」：改名在同一分区上是原子的，
 * 于是「进程正好在写定义时被杀」只会留下一个被忽略的 .tmp，不会留下一份半截 JSON。
 */
class JobSpecStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR))

    fun save(spec: BatchJobSpec): PictResult<File> {
        if (!isSafeId(spec.jobId)) {
            return failureOf(PictError.FIELD_INVALID, "任务 id 含非法字符：${spec.jobId}")
        }
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) error("建不了目录：${dir.absolutePath}")
            val target = fileOf(spec.jobId)
            val tmp = File(dir, "${spec.jobId}.json.tmp")
            tmp.writeText(spec.toJson())
            if (target.exists() && !target.delete()) error("旧定义删不掉：${target.name}")
            if (!tmp.renameTo(target)) error("定义改名失败：${tmp.name} → ${target.name}")
            target
        }.fold(
            onSuccess = { successOf(it) },
            onFailure = { failureOf(PictError.UNKNOWN, it.message ?: "任务定义存不下来") },
        )
    }

    fun load(jobId: String): BatchJobSpec? {
        if (!isSafeId(jobId)) return null
        val file = fileOf(jobId)
        if (!file.isFile) return null
        return runCatching { BatchJobSpec.fromJson(file.readText()) }.getOrNull()
    }

    fun delete(jobId: String) {
        if (!isSafeId(jobId)) return
        fileOf(jobId).delete()
        File(dir, "$jobId.json.tmp").delete()
    }

    /**
     * 已经落盘的任务 id，最近的排前面（按文件修改时间）。
     *
     * 要排掉快照文件（`<id>.snapshot.json`，见 `JobSnapshotStore.SUFFIX`）：
     * 它同样以 `.json` 结尾，早先会把快照当成一个任务 id 报出去
     * （`job-1.snapshot`），历史列表照着列就会多出幽灵条目。
     */
    fun ids(): List<String> = runCatching {
        dir.listFiles { file ->
            file.isFile && file.name.endsWith(SUFFIX) && !file.name.endsWith(JobSnapshotStore.SUFFIX)
        }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { it.name.removeSuffix(SUFFIX) }
    }.getOrDefault(emptyList())

    fun fileOf(jobId: String): File = File(dir, "$jobId$SUFFIX")

    companion object {

        const val DIR = "jobs"
        const val SUFFIX = ".json"

        /**
         * id 由我们自己生成（见 `JobIds.newId`），这里的检查是对**读回来那一路**的兜底：
         * 定义文件里的 id 会被拼成路径，任何一个 `..` 或分隔符都是路径穿越。
         */
        fun isSafeId(jobId: String): Boolean =
            jobId.isNotEmpty() && jobId.length <= 64 && jobId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
