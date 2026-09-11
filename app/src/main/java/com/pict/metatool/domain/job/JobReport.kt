package com.pict.metatool.domain.job

/**
 * 一次任务跑完之后的**逐项明细**（docs/07 T5.7、docs/01 FR-31）。
 *
 * 与 [com.pict.metatool.data.job.JobSnapshot] 的分工：
 * 快照是「一边跑一边往盘上写」的小账本（只有计数与当前文件名，几百项也不能撑大它）；
 * 报告是**跑完之后**才写一次的完整明细——每项一行、带耗时与错误码，
 * 用来导出 CSV/JSON，也是任务历史将来能「回看这一轮到底做了什么」的依据。
 *
 * 序列化不在这里：JSON 的读写放在 data 层（`JobReportJson`），
 * 域层只负责「报告长什么样」和「CSV 怎么排」。这样这个文件不碰任何 Android API，
 * 也能在纯 JVM 单测里把每一列钉住。
 */
data class JobReport(
    val jobId: String,
    val label: String,
    val status: JobStatus,
    val dryRun: Boolean,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long?,
    val params: JobReportParams,
    /** 与任务定义里的顺序一致——对不上顺序的报告对不了账。 */
    val items: List<JobReportItem>,
) {

    val total: Int get() = items.size

    val succeeded: Int get() = count(JobItemStatus.SUCCESS)

    val failed: Int get() = count(JobItemStatus.FAILED)

    val verifyFailed: Int get() = count(JobItemStatus.VERIFY_FAILED)

    val skipped: Int get() = count(JobItemStatus.SKIPPED)

    val unsupported: Int get() = count(JobItemStatus.UNSUPPORTED)

    /** 变更字段合计（每一项的「变更字段数」加起来）。 */
    val changedKeysTotal: Int get() = items.sumOf { it.changedKeys }

    /** 有备注的项：失败原因、校验对不上哪几项、以及写入器事前声明的丢弃字段。 */
    val noted: List<JobReportItem> get() = items.filter { !it.note.isNullOrBlank() }

    private fun count(status: JobItemStatus): Int = items.count { it.status == status }

    /**
     * 导出 CSV（FR-31）：**一项一行**，表头一行，行数永远等于 [total] + 1。
     *
     * 几个刻意的选择：
     * - 开头加 BOM（`\uFEFF`）：不加的话 Excel 在中文 Windows 上按 GBK 解，
     *   表头会变成乱码——「能被 Excel 打开」是这条需求的原文。
     * - 状态写中文标签而不是 `SUCCESS` 这类码：报告是给人看的；
     *   机器可读的那份是 JSON（那边的 `status` 用稳定名）。
     * - 耗时缺失写成空列而不是 0：没跑完和「跑了 0 毫秒」不是一回事。
     * - 字段里的换行一律换成空格：CSV 规范允许带引号的换行，但那样「一项一行」就没了，
     *   而「行数与任务数一致」正是这条需求的验收口径（按行数对账的工具会数错）。
     */
    fun toCsv(): String = buildString {
        append(BOM)
        append(CSV_HEADER)
        items.forEach { item ->
            append('\n')
            append(csvField(item.name)).append(',')
            append(csvField(item.status.label)).append(',')
            append(item.durationMillis?.toString().orEmpty()).append(',')
            append(csvField(item.errorCode.orEmpty())).append(',')
            append(item.changedKeys).append(',')
            append(csvField(item.note.orEmpty()))
        }
        append('\n')
    }

    companion object {

        const val BOM = "\uFEFF"

        const val CSV_HEADER = "文件名,状态,耗时(毫秒),错误码,变更字段数,备注"

        /**
         * 由任务现状生成报告（跑完时、或被掐断收尾时各调一次）。
         *
         * @param finishedAtMillis 收尾时刻；不传就用任务自己记的（还在跑时是 null）。
         */
        fun of(job: Job, params: JobReportParams, finishedAtMillis: Long? = null): JobReport = JobReport(
            jobId = job.id,
            label = job.label,
            status = job.status,
            dryRun = job.options.dryRun,
            startedAtMillis = job.startedAtMillis,
            finishedAtMillis = finishedAtMillis ?: job.finishedAtMillis,
            params = params,
            items = job.items.map { item ->
                JobReportItem(
                    name = item.source.displayName,
                    status = item.status,
                    durationMillis = item.durationMillis,
                    errorCode = item.error?.code,
                    changedKeys = item.changedCount,
                    // note 是给用户看的短句（「跳过：只读」），detail 是校验/写入的细节；
                    // 两者都有时优先 note——它是当时最有用的那句
                    note = item.note?.takeIf { it.isNotBlank() }
                        ?: item.detail?.takeIf { it.isNotBlank() },
                )
            },
        )

        /**
         * CSV 字段：先折掉换行（保证一项一行），再按需加引号。
         *
         * 含逗号、引号、BOM 才加引号，引号自身翻倍；普通文件名不进引号，导出来肉眼好看。
         * **全角逗号（，）不加引号**：它是内容，不是分隔符——中文备注里到处都是它。
         */
        private fun csvField(raw: String): String {
            val flattened = raw
                .replace('\n', ' ')
                .replace('\r', ' ')
            return if (flattened.any { it == ',' || it == '"' || it == BOM[0] }) {
                "\"" + flattened.replace("\"", "\"\"") + "\""
            } else {
                flattened
            }
        }
    }
}

/**
 * 报告里的**参数与种子**（FR-31 明说要带上）。
 *
 * 值全部来自任务定义（`BatchJobSpec`），不是在报告里重新算的：
 * 种子错一个数，「这批随机值是哪来的」就永远说不清了。
 *
 * [mode] / [clearTargets] 用字符串而不是枚举：域层不认识 data 层的批量模式枚举，
 * 报告也不该因为枚举改名就读不出来——它是要存很久的东西。
 */
data class JobReportParams(
    val mode: String,
    val presetId: String?,
    val overwriteExisting: Boolean,
    val seed: Long,
    val clearTargets: List<String>,
    val dryRun: Boolean,
    val concurrency: Int,
    val maxRetries: Int,
    val itemCount: Int,
)

/**
 * 报告里的一行（= 一张图）。
 *
 * [changedKeys] 是数量而不是键名清单：报告是「导出的表格」，一格里塞二十个键名
 * 谁也读不了；要看具体改了哪些键，详情页那边有逐项的键清单。
 */
data class JobReportItem(
    val name: String,
    val status: JobItemStatus,
    /** 处理耗时；没跑完（或被跳过）时为 null，CSV 里留空。 */
    val durationMillis: Long?,
    /** 错误码（`E-STORAGE-READONLY` 这类）；顺利时为 null。 */
    val errorCode: String?,
    /** 与源文件相比真正变化的键数（dry-run 时是「将会变」）。 */
    val changedKeys: Int,
    /** 失败原因 / 丢弃字段说明；没有就是 null。 */
    val note: String?,
)
