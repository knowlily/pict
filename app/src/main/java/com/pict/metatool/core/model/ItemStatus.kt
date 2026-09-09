package com.pict.metatool.core.model

import androidx.annotation.StringRes
import com.pict.metatool.R

/**
 * 单项处理终态（docs/01 §5）。
 * 报告导出（FR-31）按此枚举逐行统计。
 */
enum class ItemStatus(@param:StringRes val labelRes: Int) {
    /** 处理并校验通过 */
    SUCCESS(R.string.item_status_success),

    /** 处理失败（不可写、解码失败等） */
    FAILED(R.string.item_status_failed),

    /** 写成功但写后校验不通过，原文件未被改动 */
    VERIFY_FAILED(R.string.item_status_verify_failed),

    /** 用户跳过或字段全部无变化 */
    SKIPPED(R.string.item_status_skipped),

    /** 该格式/字段组合不受支持，提前判定不处理 */
    UNSUPPORTED(R.string.item_status_unsupported),
    ;

    val isTerminal: Boolean get() = true

    val isSuccessLike: Boolean get() = this == SUCCESS || this == SKIPPED
}
