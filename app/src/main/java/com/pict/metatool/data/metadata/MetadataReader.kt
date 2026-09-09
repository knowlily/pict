package com.pict.metatool.data.metadata

import android.content.ContentResolver
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.metadata.exif.ExifMetadataStore
import com.pict.metatool.data.metadata.extractor.MetadataExtractorStore
import com.pict.metatool.data.metadata.imaging.CommonsImagingStore
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 单个读取器的产物（docs/07 T1.7）。成功与否都收着，合并阶段才决定谁生效。
 */
data class StoreOutput(
    /** [MetadataStore.id]。 */
    val id: String,
    val result: PictResult<MetadataSet>,
) {
    val set: MetadataSet? get() = result.getOrNull()
    val failure: String? get() = result.failureOrNull()?.let { it.detail ?: it.code }
}

/**
 * 一次读取的完整结果：合并后的字段 + 来源标注。
 *
 * [origins] 的值按路由优先级排列（低 → 高），**末位就是最终生效的来源**。
 * 例如 `EXIF:Make` 三个读取器都读到过，则 `origins[key] = [extractor, exif, imaging]`，
 * 实际显示的是 `imaging` 的值 —— UI 可据此在字段旁标注「来源：CommonsImaging」。
 */
data class MetadataReadResult(
    val set: MetadataSet,
    val origins: Map<TagKey, List<String>>,
    /** 本次实际尝试过的读取器 id，按路由顺序。 */
    val attempted: List<String>,
    /** 读取器 id → 失败摘要；成功的不在表里。 */
    val failures: Map<String, String>,
) {

    fun originOf(key: TagKey): List<String> = origins[key].orEmpty()

    fun originOf(fullKey: String): List<String> =
        runCatching { originOf(TagKey.of(fullKey)) }.getOrElse { emptyList() }

    /** 最终生效的来源（优先级最高那个）；没读到返回 null。 */
    fun effectiveOrigin(key: TagKey): String? = origins[key]?.lastOrNull()

    fun effectiveOrigin(fullKey: String): String? =
        runCatching { effectiveOrigin(TagKey.of(fullKey)) }.getOrNull()

    /** 真正产出过字段的读取器，按路由顺序。 */
    val contributors: List<String>
        get() = attempted.filter { id -> origins.values.any { id in it } }

    val failedStores: List<String> get() = failures.keys.toList()

    /** 失败摘要，进日志/任务报告用。 */
    fun failureSummary(): String =
        failures.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * 读取路由（docs/07 T1.7、docs/02 §5）：按格式挑读取器 → 并行读 → 合并 → 标注来源。
 *
 * 分工固定：**读取器之间不互相调用**，谁都不负责「补全」别人；重叠字段靠优先级解决。
 *
 * ### 优先级（低 → 高，高的覆盖低的）
 * | 格式 | 顺序 | 依据 |
 * | --- | --- | --- |
 * | TIFF / DNG | extractor → exif → imaging | docs/02：TIFF 读取主实现是 CommonsImaging（直读 IFD） |
 * | RAW | extractor → imaging → exif | docs/02：RAW 只读，主实现是 ExifInterface；但 exif store 目前不含 RAW（T1.4 决定），实际只有前两个 |
 * | 其余 | extractor → exif → imaging | extractor 只做 IPTC/ICC/结构补充，EXIF 以 ExifInterface 为准 |
 *
 * ### 退化策略
 * 格式识别不出来（无扩展名、无 MIME、`UNKNOWN`）时，不报错而是**让全部读取器尽力一试**，
 * 失败的那个记进 [MetadataReadResult.failures] 即可 —— `supports` 只做纯格式判断，识别失败不代表文件读不了。
 */
class MetadataReader(
    private val stores: List<MetadataStore> = defaultStores(),
) {

    /** 按格式挑出读取器，并排成「低优先级在前」的顺序；纯函数，可单测。 */
    fun storesFor(info: SourceInfo): List<MetadataStore> {
        val supported = stores.filter { it.supports(info) }
        val chosen = supported.ifEmpty { stores }
        val order = ORDER[info.format] ?: DEFAULT_ORDER
        // 顺序表里没登记的 id 排到最后（稳定排序保持注入顺序）
        return chosen.sortedBy { order.indexOf(it.id).let { if (it < 0) order.size else it } }
    }

    /**
     * 读取并合并。全部读取器都失败才算失败；只要有一个成功，就返回成功的部分，
     * 其余失败记在 [MetadataReadResult.failures] 里 —— 「能显示多少显示多少」。
     */
    suspend fun read(resolver: ContentResolver, source: ImageSource): PictResult<MetadataReadResult> {
        val selected = storesFor(source.info)
        if (selected.isEmpty()) {
            return failureOf(PictError.META_PARSE, "没有注册任何元数据读取器")
        }

        // 并行读：各读取器互不依赖，串行会让同一个文件被反复完整读一遍。
        val outputs = coroutineScope {
            selected.map { store ->
                async {
                    val result = try {
                        store.read(resolver, source)
                    } catch (e: Exception) {
                        // store 契约要求不抛，但真抛了也不能拖垮整条链路
                        failureOf<MetadataSet>(PictError.UNKNOWN, e.message, e)
                    }
                    StoreOutput(store.id, result)
                }
            }.awaitAll()
        }

        return resolve(outputs, source.info)
    }

    /**
     * 决定「这次读取算成功还是失败」并合并（纯函数）。
     *
     * 规则：**全部读取器都失败才算失败**；只要有一个成功，就返回成功的部分，
     * 其余失败记在 [MetadataReadResult.failures] 里 —— 「能显示多少显示多少」。
     */
    fun resolve(outputs: List<StoreOutput>, info: SourceInfo): PictResult<MetadataReadResult> {
        if (outputs.isEmpty()) {
            return failureOf(PictError.META_PARSE, "没有可用的元数据读取器")
        }
        val combined = combine(outputs, info)
        return if (outputs.none { it.result.isSuccess }) {
            failureOf(PictError.META_PARSE, "所有读取器都失败：${combined.failureSummary()}")
        } else {
            successOf(combined)
        }
    }

    companion object {

        /** 默认读取器集合；顺序不重要，路由顺序由 [ORDER] 决定。 */
        fun defaultStores(): List<MetadataStore> = listOf(
            ExifMetadataStore(),
            MetadataExtractorStore(),
            CommonsImagingStore(),
        )

        /** 兜底优先级：extractor 补充 → exif 权威 EXIF → imaging 兜底 TIFF/RAW。 */
        val DEFAULT_ORDER: List<String> = listOf("extractor", "exif", "imaging")

        /** 格式特例：见类注释的优先级表。 */
        val ORDER: Map<ImageFormatHint, List<String>> = mapOf(
            ImageFormatHint.TIFF to listOf("extractor", "exif", "imaging"),
            ImageFormatHint.RAW to listOf("extractor", "imaging", "exif"),
        )

        /**
         * 合并多个读取器的产物并记录来源（纯函数，不碰 IO，单测直接喂 [StoreOutput]）。
         *
         * [outputs] 必须按「低优先级 → 高优先级」传入，[MetadataSet.merge] 的覆盖语义即由此决定。
         */
        fun combine(outputs: List<StoreOutput>, info: SourceInfo): MetadataReadResult {
            val origins = mutableMapOf<TagKey, MutableList<String>>()
            val failures = mutableMapOf<String, String>()
            var entries: Map<TagKey, TagValue> = emptyMap()

            outputs.forEach { out ->
                val set = out.set
                if (set == null) {
                    failures[out.id] = out.failure ?: "未知失败"
                    return@forEach
                }
                // 高优先级后写入：同键覆盖，同时把贡献者按顺序记下来
                set.entries.forEach { (key, _) ->
                    origins.getOrPut(key) { mutableListOf() }.add(out.id)
                }
                entries = entries + set.entries
            }

            return MetadataReadResult(
                set = MetadataSet(source = info, entries = entries),
                origins = origins.mapValues { (_, ids) -> ids.toList() },
                attempted = outputs.map { it.id },
                failures = failures.toMap(),
            )
        }
    }
}
