package com.pict.metatool.domain.batch

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.preset.PresetCatalog

/**
 * 批量要做的**一件事**（docs/07 T5.4 的「操作类型选择」）。
 *
 * 刻意只有三种：批量场景下用户想做的无非「照预设改一遍」「按预设重掷一遍随机值」
 * 「把某些字段清掉」。更细的逐字段编辑交给单张编辑页——批量的价值是一次决定、批量生效，
 * 不是在小屏上填一百次表单。
 */
enum class BatchMode(val label: String) {
    PRESET("套用预设"),
    RANDOM("随机填充"),
    CLEAR("清除字段"),
}

/**
 * 批量计划的草稿：界面上一堆选择，转成 [EditPlan] 之前的样子。
 *
 * 这一层单独存在，是为了让「选择 → 计划」可单测：界面只负责改草稿，
 * 计划怎么拼、什么情况算没填完，全在这里定，UI 不重复判断一遍。
 *
 * @param presetIds [BatchMode.PRESET] 与 [BatchMode.RANDOM] 必填；**按栏给**（设备 / 位置 / 时间 / 混合），
 *   套用与重掷都按这个顺序依次来（见 `PresetSelection`）
 * @param clearTargets [BatchMode.CLEAR] 用；值自带中文标签（见 `ClearTarget`）
 * @param overwriteExisting 只在套用预设时有意义：随机填充按定义就是重掷，清除不看旧值
 * @param seed 基准种子；批量时每张图按序号错开（见 [seededFor]）
 */
data class BatchDraft(
    val mode: BatchMode = BatchMode.PRESET,
    val presetIds: List<String> = emptyList(),
    val clearTargets: Set<ClearTarget> = emptySet(),
    val overwriteExisting: Boolean = false,
    val seed: Long = 0L,
) {

    /** 这一份草稿能不能算出计划（预览按钮的启用条件）。 */
    val isReady: Boolean
        get() = when (mode) {
            BatchMode.PRESET, BatchMode.RANDOM -> presetIds.isNotEmpty()
            BatchMode.CLEAR -> clearTargets.isNotEmpty()
        }

    val needsPreset: Boolean get() = mode == BatchMode.PRESET || mode == BatchMode.RANDOM

    val needsClearTargets: Boolean get() = mode == BatchMode.CLEAR

    val supportsOverwrite: Boolean get() = mode == BatchMode.PRESET

    /**
     * 拼计划。预设 id 与参数在这里校验，界面不必先查一遍目录——
     * 「选了但不存在」这类问题只有一个地方报错，措辞也就只有一套。
     *
     * @param dryRun 交给自己算 diff 的预览用；批量执行（T5.3）传 false
     */
    fun toPlan(
        catalog: PresetCatalog,
        dryRun: Boolean = true,
        backupBeforeOverwrite: Boolean = true,
    ): PictResult<EditPlan> = when (mode) {
        BatchMode.PRESET -> {
            val ids = presetIds.ifEmpty { return failureOf(PictError.FIELD_INVALID, "先挑一个预设") }
            ids.firstOrNull { catalog.byId(it) == null }
                ?.let { missing -> return failureOf(PictError.FIELD_INVALID, "预设不存在：$missing") }
            successOf(
                plan(
                    ids.mapIndexed { index, id ->
                        EditOperation.ApplyPreset(
                            presetId = id,
                            overwriteExisting = overwriteExisting,
                            // 多个预设（机型 + 位置 + 时间）时按序错开种子：同一个种子喂给两个
                            // 都声明了取值的预设，抽出来的是同一条序列，看着像没换过。
                            seed = seed + index,
                        )
                    },
                    dryRun,
                    backupBeforeOverwrite,
                ),
            )
        }

        BatchMode.RANDOM -> {
            if (presetIds.isEmpty()) {
                return failureOf(
                    PictError.FIELD_INVALID,
                    "随机填充要先挑预设：取值来自它的池 / 区间 / 圆心（docs/03 §6）",
                )
            }
            // 每栏一个 RandomFill，各管自己声明的那批字段：机型归机型、位置归位置、时间归时间，
            // 同一个字段被两个预设同时声明时，后面的（时间 → 混合）覆盖前面的。
            val presets = presetIds.map { id ->
                catalog.byId(id) ?: return failureOf(PictError.FIELD_INVALID, "预设不存在：$id")
            }
            presets.firstOrNull { it.fields.isEmpty() }
                ?.let { empty -> return failureOf(PictError.FIELD_INVALID, "预设 ${empty.id} 没声明任何字段") }
            successOf(
                plan(
                    presets.mapIndexed { index, preset ->
                        EditOperation.RandomFill(
                            fields = preset.fields.keys,
                            seed = seed + index,
                            presetId = preset.id,
                        )
                    },
                    dryRun,
                    backupBeforeOverwrite,
                ),
            )
        }

        BatchMode.CLEAR -> {
            if (clearTargets.isEmpty()) return failureOf(PictError.FIELD_INVALID, "至少要选一组要清掉的字段")
            successOf(
                plan(
                    listOf(EditOperation.ClearTargets(targets = clearTargets)),
                    dryRun,
                    backupBeforeOverwrite,
                ),
            )
        }
    }

    private fun plan(
        operations: List<EditOperation>,
        dryRun: Boolean,
        backupBeforeOverwrite: Boolean,
    ): EditPlan = EditPlan(
        operations = operations,
        dryRun = dryRun,
        backupBeforeOverwrite = backupBeforeOverwrite,
    )
}
