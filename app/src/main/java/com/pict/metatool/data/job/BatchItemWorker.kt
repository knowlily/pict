package com.pict.metatool.data.job

import android.content.ContentResolver
import android.net.Uri
import com.pict.metatool.core.result.getOrElse
import com.pict.metatool.data.batch.SafBatchSourceReader
import com.pict.metatool.data.metadata.BitmapPixelHasher
import com.pict.metatool.data.metadata.MetadataReader
import com.pict.metatool.data.metadata.MetadataVerifier
import com.pict.metatool.data.metadata.MetadataWriter
import com.pict.metatool.data.metadata.PixelHasher
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.job.ItemResult
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.job.JobItemWorker
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.preset.PresetCatalog
import com.pict.metatool.domain.preset.PresetResolver

/**
 * 批量的单项执行者（docs/07 T5.3）：**读源 → 折计划 → 落盘 → 读回校验**，
 * 与编辑页单张的写路径同一套零件、同一个顺序。
 *
 * 顺序为什么卡这么死（编辑页踩过，批量会踩得更狠）：
 * 1. 先看授权位再动文件——只读来源写了会抛 `SecurityException`，那时文件已经被打开过；
 * 2. 写完**必须**读回来比一遍（FR-33）：批量是几十上百个文件一把梭，
 *    没有逐张回读校验，用户拿到的就是「看起来成功了」而非「确实写对了」；
 * 3. dry-run 在第 2 步之后就返回——不进写通道、不建临时文件（FR-32）。
 *
 * 域层（`domain/job`）不知道 `ContentResolver` 是什么，所以这一层是它唯一的下水道口：
 * 判断（怎么算不支持、怎么算校验未通过）全在 [BatchItemRules] 里，这里只搬数据。
 *
 * 尚未接的：覆写前的备份（FR-31 撤销，T5.8）。今天批量写的是原位覆写、无备份，
 * 所以计划里的 `backupBeforeOverwrite` 在这里还没有落点——不假装有。
 */
class BatchItemWorker(
    private val resolver: ContentResolver,
    private val plan: EditPlan,
    private val indices: Map<String, Int>,
    private val writableByIds: Map<String, Boolean>,
    private val catalog: PresetCatalog,
    private val reader: MetadataReader = MetadataReader(),
    private val writers: List<MetadataWriter> = SafBatchSourceReader.defaultWriters(),
    private val hasher: PixelHasher = BitmapPixelHasher(),
) : JobItemWorker {

    override suspend fun run(item: JobItem, options: JobOptions): ItemResult {
        val source = ImageSource(uri = Uri.parse(item.uri), info = item.source)

        BatchItemRules.blockBeforeRead(
            writable = writableByIds[item.id] ?: true,
            format = item.source.format,
            hasWriter = writers.any { writer -> writer.canWrite(item.source) },
        )?.let { return it }

        val before = reader.read(resolver, source).getOrElse { failure ->
            return ItemResult.Failed(error = failure.error, detail = failure.detail ?: "读不了元数据")
        }

        val index = indices[item.id] ?: 0
        val applied = PresetResolver
            .applyPlanDetailed(BatchItemRules.planFor(plan, index), before.set, catalog)
            .getOrElse { failure ->
                return ItemResult.Failed(error = failure.error, detail = failure.detail ?: "计划算不出来")
            }
        val changedKeys = applied.outcome.changedKeys

        // 本来就是这个值：不写、不报错。批量里「这几张已经填好了」是正常结局，不是失败。
        if (changedKeys.isEmpty()) return ItemResult.Done(changedKeys = emptySet())

        // FR-32：算完就收工，一个字节都不落盘
        if (options.dryRun) return BatchItemRules.dryRunResult(changedKeys)

        val writer = writers.firstOrNull { it.canWrite(item.source) }
            ?: return ItemResult.Unsupported("${item.source.format.label} 不支持原地写元数据")

        val fingerprintBefore = hasher.hashOf(resolver, source.uri).getOrNull()
        val written = writer.write(resolver, source, applied.outcome.target).getOrElse { failure ->
            return ItemResult.Failed(error = failure.error, detail = failure.detail ?: "写不进去")
        }

        val after = reader.read(resolver, source).getOrNull()
        val fingerprintAfter = hasher.hashOf(resolver, source.uri).getOrNull()
        val report = after?.let {
            MetadataVerifier.compare(
                before = before.set,
                target = applied.outcome.target,
                after = it.set,
                fingerprintBefore = fingerprintBefore,
                fingerprintAfter = fingerprintAfter,
                // 写入器声明「这个格式存不下」的键不算缺失（XMP 段/段大小上限），否则
                // 只要预设里有这些键，每一张都会被判成校验未通过
                dropped = written.droppedKeys,
            )
        }
        return BatchItemRules.resultOf(changedKeys, report)
    }
}
