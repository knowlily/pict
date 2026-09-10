package com.pict.metatool.ui.edit

import com.pict.metatool.domain.settings.AppSettings

/**
 * 导出副本的建议文件名（docs/06 §3.3「导出」）。
 *
 * 纯字符串处理，不碰 Android：SAF 的 `CreateDocument` 只接受一个建议名，
 * 取错名（少了扩展名、跟原名撞了、长到 Provider 直接截断）都只有上了设备才发现，
 * 所以规则放这里单测锁住。
 *
 * 约定：
 * - 保留原扩展名，主名后加后缀（默认 `-edited`，可在设置里改，见 [suggest] 的 `suffix`）：
 *   `IMG_1234.JPG` → `IMG_1234-edited.JPG`；
 * - 已经带过后缀的（用户连续导出两次）不再叠一层；
 * - 后缀设成空串时**原名照搬**：目标目录里已有同名文件的话，由 Provider 自己补「 (1)」；
 * - 拿不到原名（Provider 不回 DISPLAY_NAME）时给 `image-edited.jpg`；
 * - 超过 [MAX_LENGTH] 先截主名，别把扩展名截掉。
 */
object ExportNaming {

    /** 默认后缀。真值在 [AppSettings.DEFAULT_EXPORT_SUFFIX]，这里只做别名，免得多出第二份默认值。 */
    const val SUFFIX = AppSettings.DEFAULT_EXPORT_SUFFIX
    const val FALLBACK_BASE = "image"
    const val FALLBACK_EXT = "jpg"

    /** 文件名总长上限：留给 Provider 与文件系统，不贴着 255 这个硬限。 */
    const val MAX_LENGTH = 100

    /**
     * 给 [displayName] 配一个建议名。
     *
     * [suffix] 是设置里的「导出文件名后缀」；进来先过 [AppSettings.normalizeSuffix]——
     * 设置页那层已经规范化过一次，这里再兜一次是因为本函数是交给 SAF 之前的最后一道：
     * 带 `/` 或 `:` 的名字交出去，Provider 会直接报错。
     */
    fun suggest(displayName: String?, suffix: String = SUFFIX): String {
        val tail = AppSettings.normalizeSuffix(suffix)
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return "$FALLBACK_BASE$tail.$FALLBACK_EXT"

        val dot = name.lastIndexOf('.')
        // 前导点（.nomedia）不算扩展名，结尾点（photo.）也不留空扩展名
        val hasExtension = dot > 0 && dot < name.length - 1
        val base = when {
            hasExtension -> name.substring(0, dot)
            // 结尾那个点是噪音：别把它带进新文件名，否则会变成 "photo.-edited"
            dot == name.length - 1 && dot > 0 -> name.substring(0, dot)
            else -> name
        }
        val extension = if (hasExtension) name.substring(dot) else ""
        if (base.isBlank()) return "$FALLBACK_BASE$tail.$FALLBACK_EXT"

        // 不设后缀 = 不加，原名交出去；同名冲突交给 Provider 补编号。
        if (tail.isEmpty()) return name

        if (base.endsWith(tail)) return name

        val room = (MAX_LENGTH - tail.length - extension.length).coerceAtLeast(1)
        val trimmed = if (base.length > room) base.substring(0, room) else base
        return "$trimmed$tail$extension"
    }
}
