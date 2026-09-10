package com.pict.metatool.ui.edit

/**
 * 导出副本的默认文件名（docs/06 §3.3「导出」）。
 *
 * 纯字符串处理，不碰 Android：SAF 的 `CreateDocument` 只接受一个建议名，
 * 取错名（少了扩展名、跟原名撞了、长到 Provider 直接截断）都只有上了设备才发现，
 * 所以规则放这里单测锁住。
 *
 * 约定：
 * - 保留原扩展名，主名后加 `-edited`：`IMG_1234.JPG` → `IMG_1234-edited.JPG`；
 * - 已经带过后缀的（用户连续导出两次）不再叠一层；
 * - 拿不到原名（Provider 不回 DISPLAY_NAME）时给 `image-edited.jpg`；
 * - 超过 [MAX_LENGTH] 先截主名，别把扩展名截掉。
 */
object ExportNaming {

    const val SUFFIX = "-edited"
    const val FALLBACK_BASE = "image"
    const val FALLBACK_EXT = "jpg"

    /** 文件名总长上限：留给 Provider 与文件系统，不贴着 255 这个硬限。 */
    const val MAX_LENGTH = 100

    fun suggest(displayName: String?): String {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return "$FALLBACK_BASE$SUFFIX.$FALLBACK_EXT"

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
        if (base.isBlank()) return "$FALLBACK_BASE$SUFFIX.$FALLBACK_EXT"

        if (base.endsWith(SUFFIX)) return name

        val room = (MAX_LENGTH - SUFFIX.length - extension.length).coerceAtLeast(1)
        val trimmed = if (base.length > room) base.substring(0, room) else base
        return "$trimmed$SUFFIX$extension"
    }
}
