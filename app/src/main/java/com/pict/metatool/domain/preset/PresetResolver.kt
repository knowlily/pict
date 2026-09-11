package com.pict.metatool.domain.preset

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditOutcome
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.plan.EditPlanExecutor
import java.util.Random

/**
 * 预设类操作（`ApplyPreset` / `RandomFill`）→ 具体值的粘合层（docs/07 T3.4、T3.7）。
 *
 * 为什么独立于 `EditPlanExecutor`：
 * - 执行器在 `domain/plan`，刻意只存 `presetId`、不认识预设模型；
 * - `domain/preset` 需要 `GpsEditor`（坐标必须连 `*Ref` 一起写）。
 *
 * 若执行器反向依赖预设就成包环，所以折中：本类先把预设类操作展开成
 * `SetField` / `SetGps` 等**原子操作**，再交执行器折叠。
 * 好处是 GPS 语义只实现一次，执行器的既有语义（后者覆盖前者、只读字段拒绝）也完全不变。
 *
 * `RandomFill.presetId` 为 null 时返回失败而不是「随便填点值」：随机填充的取值来自
 * 预设声明的池/区间/圆心（docs/03 §6），没有预设就只能拒绝——替用户编 ISO 和光圈
 * 比报错危险得多。
 */
object PresetResolver {

    /** 一次填充的结果：目标集合 + 变化键 + 被保留的键 + 没能填的字段。 */
    data class Fill(
        val target: MetadataSet,
        val changedKeys: Set<TagKey>,
        val keptKeys: Set<TagKey>,
        val skipped: List<MetadataRandomizer.Skip>,
    )

    /** 计划展开结果：原子操作序列 + 汇报信息。 */
    data class Expanded(
        val operations: List<EditOperation>,
        val keptKeys: Set<TagKey>,
        val skipped: List<MetadataRandomizer.Skip>,
    )

    /**
     * 按预设算出「目标元数据」（不落盘）。
     *
     * @param keys 只填这些字段；null = 预设声明的全部
     * @param onlyMissing true = 只填源里没有的字段（`ApplyPreset` 默认口径）
     * @param seed 随机种子，同种子同结果（T3.6）
     */
    fun fill(
        preset: Preset,
        source: MetadataSet,
        keys: Set<TagKey>? = null,
        onlyMissing: Boolean = false,
        seed: Long = 0L,
    ): PictResult<Fill> {
        val result = MetadataRandomizer(Random(seed)).fill(preset, source, keys, onlyMissing)
        return when (val folded = fold(result.toOperations(), source)) {
            is PictResult.Failure -> folded
            is PictResult.Success -> successOf(
                Fill(
                    target = folded.value.target,
                    changedKeys = folded.value.changedKeys,
                    keptKeys = result.keptKeys,
                    skipped = result.skipped,
                ),
            )
        }
    }

    /**
     * 把计划里的预设类操作展开成原子操作，其余操作原样保留、顺序不变。
     *
     * 展开时按「逐个操作就地折叠」推进虚拟集合：`ApplyPreset(只填缺失)` 看到的是
     * 它前面那些操作生效后的样子，而不是最初的源文件——与执行器「后者覆盖前者」的口径一致。
     */
    fun expand(
        plan: EditPlan,
        source: MetadataSet,
        catalog: PresetCatalog,
    ): PictResult<Expanded> {
        val operations = mutableListOf<EditOperation>()
        val skipped = mutableListOf<MetadataRandomizer.Skip>()
        val kept = mutableSetOf<TagKey>()
        var virtual = source

        for (operation in plan.operations) {
            val expanded: List<EditOperation> = when (operation) {
                is EditOperation.ApplyPreset -> {
                    val preset = catalog.byId(operation.presetId)
                        ?: return failureOf(PictError.FIELD_INVALID, "预设不存在：${operation.presetId}")
                    val result = MetadataRandomizer(Random(operation.seed))
                        .fill(preset, virtual, keys = null, onlyMissing = !operation.overwriteExisting)
                    skipped += result.skipped
                    kept += result.keptKeys
                    result.toOperations()
                }

                is EditOperation.RandomFill -> {
                    val presetId = operation.presetId
                        ?: return failureOf(
                            PictError.FIELD_INVALID,
                            "随机填充需要先选预设（docs/03 §6）：取值来自预设的池/区间/圆心",
                        )
                    val preset = catalog.byId(presetId)
                        ?: return failureOf(PictError.FIELD_INVALID, "预设不存在：$presetId")
                    val result = MetadataRandomizer(Random(operation.seed))
                        .fill(preset, virtual, keys = operation.fields, onlyMissing = false)
                    skipped += result.skipped
                    kept += result.keptKeys
                    result.toOperations()
                }

                else -> listOf(operation)
            }

            when (val folded = fold(expanded, virtual)) {
                is PictResult.Failure -> return folded
                is PictResult.Success -> virtual = folded.value.target
            }
            operations += expanded
        }

        return successOf(Expanded(operations, kept, skipped))
    }

    /**
     * 计划路径的**完整一次执行**：先把 `RandomFill` / `ApplyPreset` 展开成原子操作，
     * 再交 [EditPlanExecutor] 折叠。
     *
     * 为什么必须有这一层：执行器（`domain/plan`）刻意不认识预设，否则 plan ↔ preset 成包环，
     * 于是「按预设填充」这类操作只能由本类落地。T5.2 之前 [expand] 只有测试在调、没有生产调用点，
     * 结果是走计划路径（批量必然走它）的随机填充 / 预设会直接撞「尚未实现」。
     *
     * dry-run 与备份标志原样带过去：预览只算 diff，不落盘（FR-32）。
     */
    fun applyPlan(
        plan: EditPlan,
        source: MetadataSet,
        catalog: PresetCatalog,
    ): PictResult<EditOutcome> {
        if (plan.isEmpty) return EditPlanExecutor.execute(plan, source)
        return when (val expanded = expand(plan, source, catalog)) {
            is PictResult.Failure -> expanded
            is PictResult.Success ->
                EditPlanExecutor.execute(plan.copy(operations = expanded.value.operations), source)
        }
    }

    private fun fold(operations: List<EditOperation>, source: MetadataSet): PictResult<EditOutcome> =
        EditPlanExecutor.execute(EditPlan(operations = operations, dryRun = true), source)
}
