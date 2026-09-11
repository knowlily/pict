package com.pict.metatool.domain.batch

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.preset.PresetCatalog
import com.pict.metatool.domain.preset.PresetResolver
import com.pict.metatool.domain.preset.PresetResolver.Applied

/**
 * 批量 dry-run 预览引擎（docs/07 T5.5、docs/01 FR-32）。
 *
 * **FR-32 的硬约束**：预览只能读，不能写文件、不能建临时文件。本类的实现方式是
 * 结构性的——它拿到的 [BatchSourceReader] **只有一个读方法**，domain 层既不持有
 * `ContentResolver` 也不认识 `MetadataWriter`，所以「预览顺手把文件改了」在类型层面
 * 就写不出来（`BatchSourceReaderTest` 用反射把这条钉住）。
 *
 * 为什么复用 `PresetResolver.applyPlan` 而不是另写一套计算：批量计划里必然有
 * `ApplyPreset` / `RandomFill`，它们的取值来自预设、还要按操作顺序就地折叠；
 * 第二套实现迟早在「只填缺失」和 GPS 连写上跟主路径分叉。
 *
 * 纯函数、顺序执行：逐项读元数据、折叠、算 diff，**不重排结果**——
 * [BatchPreview.items] 与传入的 [BatchTarget] 顺序一一对应，界面表格、
 * 进度、报告都按同一顺序取「第 i 个文件」。耗时都在读文件上，
 * 由调用方决定放在哪个 Dispatcher；协程取消即停（每项之间都检查取消）。
 */
class BatchPreviewer(
    private val reader: BatchSourceReader,
    private val catalog: PresetCatalog,
) {

    /**
     * 预览 [plan] 作用在 [targets] 上的结果。
     *
     * @param onItem 每完成一项回调一次（已完成数、总数、该项结果），供进度条使用；
     *   在调用方的协程里执行，别在里面做重活。
     */
    suspend fun preview(
        plan: EditPlan,
        targets: List<BatchTarget>,
        onItem: (suspend (done: Int, total: Int, item: ItemPreview) -> Unit)? = null,
    ): BatchPreview {
        val items = ArrayList<ItemPreview>(targets.size)
        targets.forEachIndexed { index, target ->
            val item = previewOne(plan, target)
            items += item
            onItem?.invoke(index + 1, targets.size, item)
        }
        return BatchPreview(items)
    }

    /** 预览单个目标；被拦下时也返回结果（[ItemPreview.blocked] 非空），不抛异常。 */
    suspend fun previewOne(plan: EditPlan, target: BatchTarget): ItemPreview {
        // 先问通道能不能原地写：不能就不必读文件了，省一次 I/O 也省得结论含糊
        if (!reader.canWriteTo(target)) {
            return blocked(
                target,
                PictError.ENCODE_UNSUPPORTED,
                detail = "${target.format.label} 不支持原地写元数据（需重编码或导出副本）",
                readable = true,
            )
        }

        val source = when (val read = reader.read(target)) {
            is PictResult.Failure -> return blocked(
                target,
                read.failure.error,
                detail = read.failure.detail,
                readable = false,
            )

            is PictResult.Success -> read.value
        }

        // 来源信息以真实读到的为准（选择器给的 MIME/大小可能缺失或不准）
        val resolved = target.copy(info = source.source)

        return when (val applied = PresetResolver.applyPlanDetailed(plan, source, catalog)) {
            is PictResult.Failure -> blocked(
                resolved,
                applied.failure.error,
                detail = applied.failure.detail,
                readable = true,
            )

            is PictResult.Success -> fromApplied(resolved, source, applied.value)
        }
    }

    private fun fromApplied(target: BatchTarget, source: MetadataSet, applied: Applied): ItemPreview =
        ItemPreview(
            target = target,
            changes = changesOf(source, applied),
            keptKeys = applied.keptKeys,
            skipped = applied.skipped,
            segmentClears = applied.outcome.segmentClears,
        )

    /**
     * 变更列表：只列**真正不同**的键（[MetadataSet.changedKeys] 的口径），
     * 按附录 A 的字段顺序排，与编辑页的 diff 预览同序。
     */
    private fun changesOf(source: MetadataSet, applied: Applied): List<FieldChange> =
        applied.outcome.changedKeys
            .sortedBy { FieldCatalog.order(it) }
            .map { key -> FieldChange(key, source.entries[key], applied.outcome.target.entries[key]) }

    private fun blocked(
        target: BatchTarget,
        error: PictError,
        detail: String?,
        readable: Boolean,
    ): ItemPreview = ItemPreview(
        target = target,
        blocked = error,
        blockedDetail = detail,
        readable = readable,
    )
}

/**
 * 预览用的源读取器（依赖倒置，与 `JobItemWorker` 同一套路）。
 *
 * 实现方在 data 层，负责把 URI 变成 [MetadataSet]，并按格式路由判断能否原地写。
 * **接口里刻意没有任何写方法**：FR-32 要求 dry-run 不动文件，这一条靠「拿不到写的能力」
 * 保证，而不是靠实现方自觉。
 */
interface BatchSourceReader {

    /** 读源文件元数据；失败返回 [PictResult.Failure]，不抛异常。 */
    suspend fun read(target: BatchTarget): PictResult<MetadataSet>

    /**
     * 是否存在能原地写这个来源的通道（纯判断，只看 [BatchTarget.info.format]）。
     * 返回 false 的项在预览里会被标成「不支持」而不是「会改坏」。
     */
    fun canWriteTo(target: BatchTarget): Boolean
}

/**
 * 内存读取器：测试与「无文件系统」场景用。
 *
 * 放在 main 而不是 test 源集，是为了让 androidTest、UI 预览和
 * 「选了几个文件后先演一遍」这类场景都能用同一份，不必各写一个假实现。
 */
class InMemorySourceReader(
    private val sources: Map<String, MetadataSet>,
    private val writable: Set<String> = sources.keys,
) : BatchSourceReader {

    /** 每个 URI 被读了几次，供测试断言「读到的东西没被改过」。 */
    val readCounts: MutableMap<String, Int> = linkedMapOf()

    override suspend fun read(target: BatchTarget): PictResult<MetadataSet> {
        readCounts[target.uri] = (readCounts[target.uri] ?: 0) + 1
        val set = sources[target.uri]
            ?: return failureOf(PictError.IO_OPEN, "预置里没有 ${target.uri}")
        return successOf(set)
    }

    override fun canWriteTo(target: BatchTarget): Boolean = target.uri in writable
}
