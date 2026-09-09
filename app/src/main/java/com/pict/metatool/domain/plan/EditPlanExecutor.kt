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
 */
data class EditOutcome(
    val target: MetadataSet,
    val changedKeys: Set<TagKey>,
) {

    val isEmpty: Boolean get() = changedKeys.isEmpty()

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
 * 范围说明：TimeShift（T2.7）、RandomFill（T3.4）、ApplyPreset（Phase 3）依赖尚未
 * 实现的外部数据，这里显式返回失败而不是静默跳过——静默跳过会让用户以为改了。
 * 后续任务落地后把对应分支替换为真正的折叠逻辑。
 */
object EditPlanExecutor {

    fun execute(plan: EditPlan, source: MetadataSet): PictResult<EditOutcome> {
        var current = source
        for (operation in plan.operations) {
            when (val applied = applyOne(current, operation)) {
                is PictResult.Failure -> return applied
                is PictResult.Success -> current = applied.value
            }
        }
        return successOf(EditOutcome(target = current, changedKeys = source.changedKeys(current)))
    }

    private fun applyOne(set: MetadataSet, operation: EditOperation): PictResult<MetadataSet> =
        when (operation) {
            is EditOperation.SetField -> setField(set, operation)
            is EditOperation.ClearField -> successOf(set.without(operation.key))
            is EditOperation.ClearGroup -> successOf(clearGroup(set, operation.group))
            is EditOperation.TimeShift ->
                unsupported(operation, "时间偏移尚未实现（T2.7）")
            is EditOperation.RandomFill ->
                unsupported(operation, "随机填充尚未实现（T3.4）")
            is EditOperation.ApplyPreset ->
                unsupported(operation, "预设应用尚未实现（Phase 3）")
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
     * 清空分组：只清 [FieldCatalog] 里登记为该组的键（未登记的厂商自定义键不在此列，
     * 保护范围与结构字段策略见 T2.9）。
     */
    private fun clearGroup(set: MetadataSet, group: FieldGroup): MetadataSet {
        val keys: Set<TagKey> = set.inGroup(group).keys
        return keys.fold(set) { acc, key -> acc.without(key) }
    }

    private fun unsupported(operation: EditOperation, detail: String): PictResult<MetadataSet> =
        failureOf(PictError.META_WRITE, "$detail：${operation::class.simpleName}")
}
