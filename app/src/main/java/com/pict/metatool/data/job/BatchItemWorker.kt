package com.pict.metatool.data.job

import android.content.ContentResolver
import android.net.Uri
import com.pict.metatool.core.error.PictFailure
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.getOrElse
import com.pict.metatool.data.batch.CopyTarget
import com.pict.metatool.data.batch.SafBatchCopies
import com.pict.metatool.data.batch.SafBatchSourceReader
import com.pict.metatool.data.metadata.BitmapPixelHasher
import com.pict.metatool.data.metadata.MetadataReader
import com.pict.metatool.data.metadata.MetadataVerifier
import com.pict.metatool.data.metadata.MetadataWriter
import com.pict.metatool.data.metadata.PixelHasher
import com.pict.metatool.data.source.ImageCopy
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.job.ItemBackupGuard
import com.pict.metatool.domain.job.ItemBackupMark
import com.pict.metatool.domain.job.ItemResult
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.job.JobItemWorker
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.job.withBackup
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
 * ### 落点有两种，由构造参数决定
 * - **原地覆写**（`copies == null`）：落点就是源文件。动手之前先留备份（FR-34，T5.8）：
 *   把这张现在的样子复制到 `Pict/backup/<时间戳>/`，副本地址挂在结果上（[ItemResult.Done] /
 *   校验未通过 / 写失败三档都挂），撤销就是照着报告里的地址把副本写回去。
 *   备份失败**不许接着写**——备份存在的理由就是「万一写坏了能回去」，备份没成还照写，
 *   等于把回滚能力悄悄丢掉，比直接报错伤人。
 * - **另存**（`copies != null`，默认）：落点是**刚从源文件旁边建出来的新文件**，源文件全程
 *   不开写通道（连备份都不需要——没有「改坏了」这回事，留一份只是白占空间）。
 *   副本先复制字节、再在副本上写元数据，与编辑页「导出」是同一个顺序（[ImageCopy] 同一个函数）。
 *
 * ### 失败时不留半个产物
 * 副本的字节没复制完、或元数据没写进去，这一项就是失败的：**已经建出来的那个副本会被撤掉**
 * （[SafBatchCopies.discard]），报告里说清楚是「已撤掉」还是「残留了一份，请手动删」。
 * 一个半截的新文件躺在相册里，看着像一张能打开的照片——那比一句失败更容易骗到人。
 *
 * 重试时**不再留第二份备份**：第一次的备份才是「执行前的样子」，第二次再留一份，留的是
 * 第一次改了一半的样子，撤销会把那张半成品写回去。所以 [JobItem.backupUri] 已经有值
 * 就跳过备份，直接把旧的地址续到这一次的结果上。
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
    /** 不留备份（单测、不需要回滚的场景）时传 null。 */
    private val backup: ItemBackupGuard? = null,
    /**
     * 另存模式的落点工厂（见类注释）。
     *
     * null = 原地覆写源文件（此时才用得上 [backup]）；非 null = 每一项都先建一个副本再改。
     * 两个参数是**互斥**的：另存模式即使传了 [backup] 也不会去留备份（原图没动，留了没用）。
     */
    private val copies: SafBatchCopies? = null,
    /** 这一批是不是原地覆写（`JobOptions.writesInPlace`）；只用于拦停判断，默认按老口径拦。 */
    private val writesInPlace: Boolean = copies == null,
) : JobItemWorker {

    override suspend fun run(item: JobItem, options: JobOptions): ItemResult {
        val source = ImageSource(uri = Uri.parse(item.uri), info = item.source)

        BatchItemRules.blockBeforeRead(
            writable = writableByIds[item.id] ?: true,
            format = item.source.format,
            hasWriter = writers.any { writer -> writer.canWrite(item.source) },
            writesInPlace = writesInPlace,
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
            ?: return ItemResult.Unsupported("${item.source.format.label} 不支持写元数据")

        // 落点：另存模式先建副本（源文件一个字节都不碰），原地模式落点就是源文件本身
        val copyTarget: CopyTarget?
        val creator = copies
        if (creator == null) {
            copyTarget = null
        } else {
            when (val placed = prepareCopy(creator, item, source)) {
                is PictResult.Failure -> return fail(placed.failure, "另存副本没准备好")
                is PictResult.Success -> copyTarget = placed.value
            }
        }
        val landing = copyTarget
            ?.let { copy -> ImageSource(uri = copy.uri, info = item.source.copy(displayName = copy.name)) }
            ?: source

        // FR-34：动手之前先把这张现在的样子留一份（只在地模式）；理由见类注释；
        // 重试的第二次不再留新份 —— 第一次那份才是「执行前的样子」。
        var mark = if (copyTarget != null) {
            null
        } else {
            item.backupUri?.let { uri -> ItemBackupMark(uri = uri, folder = item.backupFolder.orEmpty()) }
        }
        if (copyTarget == null && mark == null && backup != null) {
            mark = backup.markFor(item).getOrElse { failure ->
                return ItemResult.Failed(
                    error = failure.error,
                    detail = failure.detail?.let { "覆写前备份失败：$it" } ?: "覆写前备份失败",
                )
            }
        }

        val fingerprintBefore = hasher.hashOf(resolver, landing.uri).getOrNull()
        val written = writer.write(resolver, landing, applied.outcome.target).getOrElse { failure ->
            // 原地模式：写坏了也把备份地址带上——这张已经动过了，撤销要能把它恢复回去
            if (copyTarget == null) {
                return ItemResult.Failed(error = failure.error, detail = failure.detail ?: "写不进去")
                    .withBackup(mark)
            }
            // 另存模式：源文件没动过，这个没写成的副本撤掉就行（留着是张会骗人的半成品）
            val removed = copies?.discard(copyTarget.uri) == true
            val tail = if (removed) {
                "（这次没写成的副本已撤掉，原图没动）"
            } else {
                "（原图没动；没写成的副本 ${copyTarget.name} 残留了，请手动删掉）"
            }
            return ItemResult.Failed(
                error = failure.error,
                detail = (failure.detail ?: "写不进副本") + tail,
            )
        }

        val after = reader.read(resolver, landing).getOrNull()
        val fingerprintAfter = hasher.hashOf(resolver, landing.uri).getOrNull()
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
        return if (copyTarget == null) {
            BatchItemRules.resultOf(changedKeys, report).withBackup(mark)
        } else {
            // 另存：把副本地址与名字一并带上，报告要能告诉用户新文件在哪儿
            BatchItemRules.resultOfCopy(
                changedKeys = changedKeys,
                report = report,
                outputUri = copyTarget.uri.toString(),
                copyName = copyTarget.name,
            )
        }
    }

    /**
     * 另存模式下把字节复制进一个新副本；原地模式直接返回 null（没有副本这回事）。
     *
     * 只负责「建副本 + 搬字节」这两步：判断与收尾留在 [BatchItemRules]，
     * 元数据写入留在上面那段主流程里，三条路（原地 / 另存 / dry-run）共用同一份收尾口径。
     *
     * 复制不完整就把刚建出来的副本撤掉（[SafBatchCopies.discard]）：半截文件比报错更骗人。
     */
    private suspend fun prepareCopy(
        creator: SafBatchCopies,
        item: JobItem,
        source: ImageSource,
    ): PictResult<CopyTarget> {
        val created = when (val attempt = creator.createFor(item)) {
            // 建不出来：原样把原因带上去（Provider 不给建、目录不在授权里……）
            is PictResult.Failure -> return attempt
            is PictResult.Success -> attempt.value
        }

        return when (val copied = ImageCopy.copy(resolver, source.uri, created.uri)) {
            is PictResult.Success -> PictResult.Success(created)
            is PictResult.Failure -> {
                val removed = creator.discard(created.uri)
                val tail = if (removed) {
                    "（没写完的副本已撤掉，原图没动）"
                } else {
                    "（原图没动；没写完的副本 ${created.name} 残留了，请手动删掉）"
                }
                PictResult.Failure(
                    copied.failure.copy(detail = (copied.failure.detail ?: "复制到副本失败") + tail),
                )
            }
        }
    }

    private fun fail(failure: PictFailure, fallback: String, tail: String = ""): ItemResult.Failed =
        ItemResult.Failed(
            error = failure.error,
            detail = (failure.detail ?: fallback) + tail,
        )
}
