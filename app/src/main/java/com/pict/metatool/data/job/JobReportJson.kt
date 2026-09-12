package com.pict.metatool.data.job

import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobReport
import com.pict.metatool.domain.job.JobReportItem
import com.pict.metatool.domain.job.JobReportParams
import com.pict.metatool.domain.job.JobStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 任务报告的 JSON 读写（FR-31：报告要能导出 JSON，**含参数与种子**）。
 *
 * 同一份 JSON 有两个去处：写进 `<jobId>.report.json` 当明细存档，以及用户在报告页
 * 「导出 JSON」时原样导出去。**刻意不分两套格式**——分了两套就意味着导出那份
 * 总有一天会跟存档那份对不上，而报告的全部价值就是「当时到底发生了什么」。
 *
 * 解析对坏数据宽容：任何一处读不出来就返回 null（调用方当「没有报告」处理），
 * 不抛异常。手改过的文件不该让报告页崩掉——与 `JobSnapshot`、设置那几处一个态度。
 *
 * 编码方式与 `BatchJobSpec` 一致：kotlinx-serialization 的 `Json*` 树
 * （项目没上编译器插件，所以是手搭树，不是 `@Serializable`）。
 */
object JobReportJson {

    const val VERSION = 1

    fun write(report: JobReport): String = buildJsonObject {
        put("v", VERSION)
        put("jobId", report.jobId)
        put("label", report.label)
        put("status", report.status.name)
        put("dryRun", report.dryRun)
        put("startedAt", report.startedAtMillis ?: 0L)
        put("finishedAt", report.finishedAtMillis ?: 0L)
        // 撤销时刻（FR-34）。跟上面两个时间戳一样用 0 表示「没这回事」：
        // 加这一个字段不用升版本——读的一头对缺字段一向是当 null 处理的
        put("undoneAt", report.undoneAtMillis ?: 0L)
        put(
            "params",
            buildJsonObject {
                put("mode", report.params.mode)
                put("presetIds", buildJsonArray { report.params.presetIds.forEach { add(JsonPrimitive(it)) } })
                put("overwrite", report.params.overwriteExisting)
                put("seed", report.params.seed)
                put(
                    "clearTargets",
                    buildJsonArray { report.params.clearTargets.forEach { add(JsonPrimitive(it)) } },
                )
                put("dryRun", report.params.dryRun)
                put("concurrency", report.params.concurrency)
                put("maxRetries", report.params.maxRetries)
                put("itemCount", report.params.itemCount)
            },
        )
        put(
            "items",
            buildJsonArray {
                report.items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("name", item.name)
                            put("status", item.status.name)
                            putNullable("durationMillis", item.durationMillis)
                            putNullable("errorCode", item.errorCode)
                            put("changedKeys", item.changedKeys)
                            putNullable("note", item.note)
                            // 撤销要用的地址（FR-34）；没有就不写，报告文件更小
                            putNullable("uri", item.uri)
                            putNullable("outputUri", item.outputUri)
                            putNullable("backupUri", item.backupUri)
                            putNullable("backupFolder", item.backupFolder)
                        },
                    )
                }
            },
        )
    }.toString()

    fun read(text: String): JobReport? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val params = root["params"]?.jsonObject
        JobReport(
            jobId = root.str("jobId") ?: error("报告缺 jobId"),
            label = root.str("label").orEmpty(),
            status = JobStatus.valueOf(root.str("status") ?: error("报告缺 status")),
            dryRun = root["dryRun"]?.jsonPrimitive?.contentOrNull == "true",
            startedAtMillis = root.longOrNull("startedAt"),
            finishedAtMillis = root.longOrNull("finishedAt"),
            params = JobReportParams(
                mode = params?.str("mode").orEmpty(),
                presetIds = (params?.get("presetIds") as? JsonArray)
                    ?.mapNotNull { node -> node.jsonPrimitive.contentOrNull }
                    .orEmpty()
                    .ifEmpty { listOfNotNull(params?.str("presetId")) },   // 老报告里是单个 presetId
                overwriteExisting = params?.str("overwrite") == "true",
                seed = params?.longOrNull("seed") ?: 0L,
                clearTargets = (params?.get("clearTargets") as? JsonArray)
                    ?.mapNotNull { node -> node.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                dryRun = params?.str("dryRun") == "true",
                concurrency = params?.intOrNull("concurrency") ?: 0,
                maxRetries = params?.intOrNull("maxRetries") ?: 0,
                itemCount = params?.intOrNull("itemCount") ?: 0,
            ),
            items = (root["items"] as? JsonArray)
                ?.map { element ->
                    val item = element.jsonObject
                    JobReportItem(
                        name = item.str("name").orEmpty(),
                        status = JobItemStatus.valueOf(item.str("status") ?: error("报告项缺 status")),
                        // 0 毫秒是合法值（快得测不出），不能当成「没有」丢掉
                        durationMillis = item["durationMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        errorCode = item.str("errorCode"),
                        changedKeys = item.intOrNull("changedKeys") ?: 0,
                        note = item.str("note"),
                        uri = item.str("uri"),
                        outputUri = item.str("outputUri"),
                        backupUri = item.str("backupUri"),
                        backupFolder = item.str("backupFolder"),
                    )
                }
                .orEmpty(),
            undoneAtMillis = root.longOrNull("undoneAt"),
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    /**
     * 可空的字符串/数字列。
     *
     * `put(key, value ?: JsonNull)` 在这里编不过：`String` 与 `JsonNull` 的公共父类型是 `Any`，
     * 而 `put` 只认 `JsonElement`。显式包一层 `JsonPrimitive`，类型就没有歧义了。
     */
    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
        put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** 0 当「没记」：这两个时间戳我们在写的时候就是拿 0 表示没有的。 */
    private fun JsonObject.longOrNull(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it != 0L }

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}
