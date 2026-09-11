package com.pict.metatool.domain.batch

import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan

/**
 * 批量口径：同一个计划跑一批图时，**每张图的操作种子按序号错开**。
 *
 * 为什么必须错开：计划在一批目标之间是共享的，原样传下去的话，
 * `RandomFill(seed = 0)` 会把五百张图填成同一个机型、同一个 GPS、同一秒拍摄——
 * 「像同一台相机拍的」是想要的，但「五百张像同一张」恰好是假的地方。
 * 错开之后由 `(种子, 序号)` 唯一决定结果：换个种子整批可复现（T3.6），批内互不重样。
 *
 * **预览与执行必须同口径**：[BatchPreviewer] 与本函数是这条规则的唯二入口，
 * 批量执行（T5.3）也要走 `plan.seededFor(index)`。否则用户预览到的值和真正落盘的值
 * 不是一回事——预览就白做了，而 FR-32 的承诺也变成一句空话。
 *
 * 序号 0 返回原计划（第一项与单张编辑的结果一致，便于对照）。
 */
fun EditPlan.seededFor(index: Int): EditPlan {
    if (index == 0) return this
    return copy(
        operations = operations.map { operation ->
            when (operation) {
                is EditOperation.RandomFill -> operation.copy(seed = operation.seed + index)
                is EditOperation.ApplyPreset -> operation.copy(seed = operation.seed + index)
                is EditOperation.JitterGps -> operation.copy(seed = operation.seed + index)
                else -> operation
            }
        },
    )
}
