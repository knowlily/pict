package com.pict.metatool.domain.format

import java.util.Locale

/**
 * 文件体积格式化（图库缩略图信息行、详情页「文件」Tab 共用）。
 *
 * 纯函数、不依赖 Android —— 直接喂参数就能单测。
 * 规则：
 * - 取不到（null）或负数 → `—`（不显示「0 B」误导用户）；
 * - 1024 进制，单位 B / KB / MB / GB / TB；
 * - 保留 1 位小数并去掉多余的 `.0`（`1 MB` 而不是 `1.0 MB`）；
 * - 小数点固定用 `.`（[Locale.US]），避免某些区域显示成逗号。
 */
object ByteSizeFormatter {

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    /** 未知体积的占位符（与元数据值格式化保持一致，不用空串）。 */
    const val UNKNOWN: String = "—"

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")
    private val DIVISORS = doubleArrayOf(1.0, KB, MB, GB, TB)

    fun format(bytes: Long?): String {
        if (bytes == null || bytes < 0) return UNKNOWN
        if (bytes < 1024) return "$bytes B"

        // 从大到小找第一个「除以它 ≥ 1」的单位，保证 1048575 B 显示成 1024 KB 而不是 1 MB。
        var index = UNITS.lastIndex
        while (index > 0 && bytes < DIVISORS[index]) index--

        val value = bytes / DIVISORS[index]
        val text = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        return "$text ${UNITS[index]}"
    }
}
