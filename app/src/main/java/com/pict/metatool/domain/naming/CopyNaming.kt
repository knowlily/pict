package com.pict.metatool.domain.naming

/**
 * 副本改名：目标目录里已经有同名的就往后排「 (1)」「 (2)」……
 *
 * ### 为什么批量非做不可，编辑页可以不做
 * 编辑页的副本名交给 SAF 的「新建文档」对话框——Provider 自己会补编号，用户也看得见。
 * 批量的副本是**程序建的**（没有对话框、几百个文件一把梭），名字撞了要么覆盖掉别人，
 * 要么 Provider 悄悄改个名而报告里写的还是旧名，两种都很难解释。所以撞名要在**建之前**
 * 就用已知的同目录文件名算清楚。
 *
 * ### 编号插在扩展名之前
 * `photo-edited.jpg` → `photo-edited (1).jpg`，不是 `photo-edited.jpg (1)`：
 * 后者会让扩展名不再是最后一段，很多相册/图库按扩展名判类型，认不出来。
 *
 * ### 比对不分大小写
 * SAF 背后可能是 FAT/exFAT（不分大小写）。`Photo.JPG` 与 `photo.jpg` 在这类盘上是**同一个**
 * 文件，按大小写敏感去比会算出「不撞」，然后建文件时才发现撞了。
 */
object CopyNaming {

    /** 撞名时的编号格式；空格是照 Android 自己去重时的样子，用户一眼认得。 */
    const val COUNTER_FORMAT = "%s (%d)%s"

    /** 最多试到 99，够用；再撞就交出去让 Provider 自己想办法，别在这里转圈。 */
    const val MAX_ATTEMPTS = 99

    /**
     * @param desired 想要的名字（[ExportNaming.suggest] 算出来的那份）
     * @param taken 目标目录里**已有的**名字，大小写不敏感
     * @return 排开号的可用名字；**连 99 个号都被占就返回 null**，不硬塞一个撞着的名字回去 ——
     *   宁可让调用方报「换个输出目录」，也不能建完文件才被 Provider 悄悄改名，
     *   那时报告里写的名字和盘上的名字对不上，比直接说「没排上」难查得多
     */
    fun unique(desired: String, taken: Set<String>): String? {
        val busy = taken.mapTo(HashSet(taken.size)) { it.lowercase() }
        if (desired.lowercase() !in busy) return desired

        val dot = desired.lastIndexOf('.')
        // 与 ExportNaming 同一套判定：前导点不算扩展名，结尾点也不留空扩展名
        val hasExtension = dot > 0 && dot < desired.length - 1
        val stem = if (hasExtension) desired.substring(0, dot) else desired
        val extension = if (hasExtension) desired.substring(dot) else ""

        for (n in 1..MAX_ATTEMPTS) {
            val candidate = COUNTER_FORMAT.format(stem, n, extension)
            if (candidate.lowercase() !in busy) return candidate
        }
        return null
    }
}
