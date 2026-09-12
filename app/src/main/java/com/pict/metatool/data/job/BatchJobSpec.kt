package com.pict.metatool.data.job

import com.pict.metatool.core.result.PictResult
import com.pict.metatool.domain.batch.BatchDraft
import com.pict.metatool.domain.batch.BatchMode
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.job.Job
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.job.JobReportParams
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.preset.PresetCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 一个批量任务的**可落盘定义**（docs/07 T5.3）。
 *
 * 为什么不让 `JobWorker` 在内存里拿着 `Job` 就跑：WorkManager 的 worker 会在
 * 进程被杀、系统重启、机器过载之后**重新创建**，那时内存里什么都没有，只剩 `inputData`
 * 里那个任务 id。于是「要跑的是什么」必须能自己存下来、自己读回来——就是这一层，
 * 也是「杀掉进程后任务状态还能恢复」（T5.9）能成立的前提。
 *
 * 编码方式与 `PresetParser` 一致：手写 JSON 树（项目没上 kotlinx-serialization 编译器插件），
 * 整份定义因此可以在 JVM 单测里来回转。
 *
 * 刻意**不存** `Job` 的执行态（状态、耗时、变更键）：定义是输入，执行态是输出，
 * 输出进 [JobSnapshot]。混在一起会让「重新排队」带着上一轮的旧账跑。
 *
 * @param dryRun 只算不写（FR-32）。dry-run 任务同样排队、同样会生成定义文件——
 *   FR-32 禁止的是碰**用户的图片**（含图片旁的临时文件），不是禁止我们记自己的账。
 */
data class BatchJobSpec(
    val jobId: String,
    val label: String,
    val createdAtMillis: Long,
    val mode: BatchMode,
    val presetIds: List<String>,
    val overwriteExisting: Boolean,
    val seed: Long,
    val clearTargets: List<ClearTarget>,
    val dryRun: Boolean,
    val options: JobOptions,
    val items: List<SpecItem>,
) {

    /** 项 id → 序号。每张图按序号错开种子（`EditPlan.seededFor`），与预览一模一样。 */
    val indices: Map<String, Int>
        get() = items.withIndex().associate { (index, item) -> item.id to index }

    /** 项 id → 授权位：排队那一刻来源准不准写。执行时可能已经变了，所以按项快照带着走。 */
    val writableByIds: Map<String, Boolean> get() = items.associate { it.id to it.writable }

    fun toDraft(): BatchDraft = BatchDraft(
        mode = mode,
        presetIds = presetIds,
        clearTargets = clearTargets.toSet(),
        overwriteExisting = overwriteExisting,
        seed = seed,
    )

    /**
     * 拼计划：走的是与预览**同一个** `BatchDraft.toPlan`，不另写一套。
     * 于是「预览说要改 11 处，执行就真改 11 处」不是巧合，是同一份计算。
     */
    fun toPlan(catalog: PresetCatalog): PictResult<EditPlan> =
        toDraft().toPlan(catalog, dryRun = dryRun, backupBeforeOverwrite = true)

    fun toJob(): Job = Job(
        id = jobId,
        label = label,
        createdAtMillis = createdAtMillis,
        items = items.map { it.toJobItem() },
        options = options,
    )

    /**
     * 报告里那份「参数与种子」（FR-31）。
     *
     * 从定义里**原样抄**，不重算：报告要回答的是「这批值是怎么来的」，
     * 唯一的答案只能是排队那一刻写下来的这份。界面上后来改了什么，跟已经跑完的任务无关。
     */
    fun toReportParams(): JobReportParams = JobReportParams(
        mode = mode.name,
        presetIds = presetIds,
        overwriteExisting = overwriteExisting,
        seed = seed,
        clearTargets = clearTargets.map { it.name },
        dryRun = dryRun,
        concurrency = options.concurrency,
        maxRetries = options.maxRetries,
        itemCount = items.size,
    )

    fun toJson(): String = buildJsonObject {
        put("v", VERSION)
        put("jobId", jobId)
        put("label", label)
        put("createdAt", createdAtMillis)
        put("mode", mode.name)
        put("presetIds", buildJsonArray { presetIds.forEach { add(JsonPrimitive(it)) } })
        put("overwrite", overwriteExisting)
        put("seed", seed)
        put("dryRun", dryRun)
        put("clear", buildJsonArray { clearTargets.forEach { add(JsonPrimitive(it.name)) } })
        put(
            "options",
            buildJsonObject {
                put("concurrency", options.concurrency)
                put("maxRetries", options.maxRetries)
                put("dryRun", options.dryRun)
            },
        )
        put(
            "items",
            buildJsonArray {
                items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("uri", item.uri)
                            put("name", item.displayName)
                            put("mime", item.mimeType)
                            put("size", item.sizeBytes)
                            put("format", item.format.name)
                            put("writable", item.writable)
                        },
                    )
                }
            },
        )
    }.toString()

    companion object {

        const val VERSION = 1

        /**
         * 由界面上的「目标 + 草稿」攒一份定义。
         *
         * [presetNames] 只为标签好看（「套用预设『iPhone 15 + 北京』· 32 张」），
         * 缺席也能攒——标签不该成为排队的门槛。
         */
        fun from(
            draft: BatchDraft,
            targets: List<BatchTarget>,
            options: JobOptions,
            jobId: String,
            nowMillis: Long,
            presetNames: List<String> = emptyList(),
        ): BatchJobSpec = BatchJobSpec(
            jobId = jobId,
            label = labelOf(draft, targets.size, presetNames),
            createdAtMillis = nowMillis,
            mode = draft.mode,
            presetIds = draft.presetIds,
            overwriteExisting = draft.overwriteExisting,
            seed = draft.seed,
            clearTargets = draft.clearTargets.sortedBy { it.ordinal },
            dryRun = options.dryRun,
            options = options,
            items = targets.mapIndexed { index, target -> SpecItem.of(index, target) },
        )

        /** 任务标签（历史列表与通知标题都显示它）。多栏一起选时按「A + B」连起来。 */
        fun labelOf(draft: BatchDraft, count: Int, presetNames: List<String> = emptyList()): String {
            val names = presetNames.filter { it.isNotBlank() }
            val joined = if (names.isEmpty()) null else names.joinToString(" + ")
            val what = when (draft.mode) {
                BatchMode.PRESET -> joined?.let { "套用预设「$it」" } ?: "套用预设"
                BatchMode.RANDOM -> joined?.let { "按「$it」重掷随机值" } ?: "随机填充"
                BatchMode.CLEAR -> {
                    val labels = draft.clearTargets.sortedBy { it.ordinal }.map { it.label }
                    val shown = labels.take(2).joinToString("、")
                    val more = if (labels.size > 2) " 等 ${labels.size} 组" else ""
                    if (labels.isEmpty()) "清除字段" else "清除$shown$more"
                }
            }
            return "$what · $count 张"
        }

        /** 读回定义。坏文件一律当「没有」，不让一条烂 JSON 把整个任务列表拖死。 */
        fun fromJson(text: String): BatchJobSpec? = runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val items = root["items"]?.let { element ->
                (element as? JsonArray)?.mapNotNull { node -> itemFrom(node.jsonObject) }
            }.orEmpty()

            BatchJobSpec(
                jobId = root.str("jobId") ?: return null,
                label = root.str("label").orEmpty(),
                createdAtMillis = root.long("createdAt") ?: 0L,
                mode = root.str("mode")?.let { name -> BatchMode.entries.firstOrNull { it.name == name } }
                    ?: return null,
                presetIds = (root["presetIds"] as? JsonArray)
                    ?.mapNotNull { node -> node.jsonPrimitive.contentOrNull }
                    .orEmpty()
                    .ifEmpty { listOfNotNull(root.str("presetId")) },   // 老定义里是单个 presetId
                overwriteExisting = root.bool("overwrite") ?: false,
                seed = root.long("seed") ?: 0L,
                clearTargets = (root["clear"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { node -> node.jsonPrimitive.contentOrNull }
                    .mapNotNull { name -> ClearTarget.entries.firstOrNull { it.name == name } },
                dryRun = root.bool("dryRun") ?: false,
                options = optionsFrom(root["options"]?.jsonObject),
                items = items,
            )
        }.getOrNull()

        private fun optionsFrom(node: JsonObject?): JobOptions {
            val defaults = JobOptions()
            if (node == null) return defaults
            return JobOptions(
                concurrency = node.long("concurrency")?.toInt() ?: defaults.concurrency,
                maxRetries = node.long("maxRetries")?.toInt() ?: defaults.maxRetries,
                dryRun = node.bool("dryRun") ?: defaults.dryRun,
            ).normalized()
        }

        private fun itemFrom(node: JsonObject): SpecItem? {
            val id = node.str("id") ?: return null
            val uri = node.str("uri") ?: return null
            val format = node.str("format")
                ?.let { name -> ImageFormatHint.entries.firstOrNull { it.name == name } }
                ?: ImageFormatHint.UNKNOWN
            return SpecItem(
                id = id,
                uri = uri,
                displayName = node.str("name").orEmpty(),
                mimeType = node.str("mime"),
                sizeBytes = node.long("size"),
                format = format,
                writable = node.bool("writable") ?: true,
            )
        }
    }
}

/** 定义里的一项：不透明地址 + 排队时的路由/授权快照。 */
data class SpecItem(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val format: ImageFormatHint,
    val writable: Boolean,
) {

    fun toJobItem(): JobItem = JobItem(
        id = id,
        uri = uri,
        source = SourceInfo(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            format = format,
        ),
    )

    companion object {

        /**
         * 项 id 用序号而不是 URI：报告、重跑、断点续跑都按 id 对账，
         * 而 URI 是用户随时可能挪走、改名、删掉的东西，不适合当锚点。
         */
        fun of(index: Int, target: BatchTarget): SpecItem = SpecItem(
            id = "item-$index",
            uri = target.uri,
            displayName = target.info.displayName,
            mimeType = target.info.mimeType,
            sizeBytes = target.info.sizeBytes,
            format = target.info.format,
            writable = target.writable,
        )
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

private fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
