package com.pict.metatool.domain.plan

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey

/**
 * 编辑计划的折叠结果（docs/07 T2.2）。
 *
 * @param target 折叠后的目标元数据（全量，可直接交给 MetadataWriter）
 * @param changedKeys 与源相比真正变化的键（新增、删除、值不同）；空计划时为 empty
 * @param segmentClears 字段级折叠表达不了的**段级清除意图**（缩略图 / ICC，T2.9）。
 *   写通道只按 [target] 重写字段、丢不了段，所以这里显式带出来给写通道消费；
 *   非空即表示「还有事情要做」，因此计入 [isEmpty]
 */
data class EditOutcome(
    val target: MetadataSet,
    val changedKeys: Set<TagKey>,
    val segmentClears: Set<ClearSegment> = emptySet(),
) {

    val isEmpty: Boolean get() = changedKeys.isEmpty() && segmentClears.isEmpty()

    /** 变化的字段数；段级意图不含在内（它们不是字段）。 */
    val size: Int get() = changedKeys.size
}

/**
 * 把操作序列折叠成「目标 MetadataSet + 变更 diff」（docs/07 T2.2）。
 *
 * 语义（docs/08 验收点）：
 * - 操作按列表顺序依次生效，**后者覆盖前者**——先清后设会留下值，先设后清会删掉值；
 * - 空计划不产生 diff；
 * - 纯函数、无副作用，dryRun 与否对折叠结果没有影响（是否落盘由调用方决定）。
 *
 * 段级清除（缩略图 / ICC，T2.9）在字段 diff 里看不见，单独累计进
 * [EditOutcome.segmentClears]；写通道尚未消费它，所以它既不会被丢掉也不会被冒充成
 * 「已经清干净」。
 *
 * 范围说明：RandomFill（T3.4）、ApplyPreset（Phase 3）在本执行器里**显式失败**而不是静默跳过——
 * 静默跳过会让用户以为改了，而这两类操作的取值来自预设（docs/03 §6），执行器又不认识预设
 * （否则 plan ↔ preset 成包环）。
 * 所以：**计划里带预设类操作时，先过 `PresetResolver.applyPlan`**，由它展开成原子操作
 * （`SetField` / `SetGps` …）再交给这里；直接拿未展开的计划调用本执行器会得到 E-FIELD-INVALID。
 */
object EditPlanExecutor {

    fun execute(plan: EditPlan, source: MetadataSet): PictResult<EditOutcome> {
        var current = source
        val segments = linkedSetOf<ClearSegment>()
        for (operation in plan.operations) {
            segments += segmentsOf(operation)
            when (val applied = applyOne(current, operation)) {
                is PictResult.Failure -> return applied
                is PictResult.Success -> current = applied.value
            }
        }
        return successOf(
            EditOutcome(
                target = current,
                changedKeys = source.changedKeys(current),
                segmentClears = segments,
            ),
        )
    }

    private fun applyOne(set: MetadataSet, operation: EditOperation): PictResult<MetadataSet> =
        when (operation) {
            is EditOperation.SetField -> setField(set, operation)
            is EditOperation.ClearField -> successOf(set.without(operation.key))
            is EditOperation.ClearGroup -> successOf(clearGroup(set, operation.group))
            is EditOperation.ClearTargets -> successOf(ClearGroups.clear(set, operation.targets))
            is EditOperation.ClearAll -> successOf(ClearGroups.clearAll(set, operation.protectStructural))
            is EditOperation.TimeShift -> TimeShift.apply(set, operation.deltaMillis)
            is EditOperation.SetGps ->
                GpsEditor.set(set, operation.latitude, operation.longitude, operation.altitudeMeters)
            is EditOperation.JitterGps ->
                GpsEditor.jitter(set, operation.radiusMeters, operation.seed)
            is EditOperation.RandomFill ->
                unsupported(operation, "随机填充尚未实现（T3.4）")
            is EditOperation.ApplyPreset ->
                unsupported(operation, "预设应用尚未实现（Phase 3）")
        }

    /** 这一步操作带出的段级清除意图；字段级操作一律为空。 */
    private fun segmentsOf(operation: EditOperation): Set<ClearSegment> = when (operation) {
        is EditOperation.ClearTargets -> ClearGroups.segmentsOf(operation.targets)
        // 整表清空按 FR-16 连缩略图与 ICC 一起丢
        is EditOperation.ClearAll -> ClearGroups.ALL_SEGMENTS
        else -> emptySet()
    }

    /**
     * 设置字段：只读字段（如 FILE 组的尺寸、颜色空间）直接拒绝，
     * 避免折叠出一个写入层根本写不进去的目标值。
     */
    private fun setField(set: MetadataSet, operation: EditOperation.SetField): PictResult<MetadataSet> {
        val spec = FieldCatalog.spec(operation.key)
        if (spec != null && !spec.canEdit) {
            return failureOf(
                PictError.FIELD_INVALID,
                "${operation.key.full} 是只读字段（${spec.writability.label}）",
            )
        }
        return successOf(set.with(operation.key, operation.value))
    }

    /**
     * 清空分组：只清 [FieldCatalog] 里登记为该组的键（未登记的厂商自定义键不在此列）。
     * 按类别清空（含未登记键推断与段级意图）请用 [EditOperation.ClearTargets] / `ClearGroups`。
     */
    private fun clearGroup(set: MetadataSet, group: FieldGroup): MetadataSet {
        val keys: Set<TagKey> = set.inGroup(group).keys
        return keys.fold(set) { acc, key -> acc.without(key) }
    }

    private fun unsupported(operation: EditOperation, detail: String): PictResult<MetadataSet> =
        failureOf(PictError.META_WRITE, "$detail：${operation::class.simpleName}")
}
