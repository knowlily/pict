package com.pict.metatool.data.metadata

import com.pict.metatool.domain.model.TagKey

/**
 * 写入无损校验报告（docs/07 T2.4）。
 *
 * 把「写完之后文件到底变成什么样」摊开成五类差异，而不是只给一个通过/失败：
 * 出问题时能直接看出是丢字段、值被改写、该删的没删，还是凭空多出来东西。
 *
 * @property matched 目标里的键，读回值与目标一致
 * @property mismatched 目标里的键，读回值不一致（两侧都归一化成 EXIF 字面量后比较）
 * @property missing 目标里有、读回没有的键
 * @property dropped 目标里有、读回没有，但**写入器已经声明**「这个格式存不下」的键
 *   （[com.pict.metatool.data.metadata.WriteResult.droppedKeys]）
 * @property notRemoved 写入前有、目标里没有（本该删除）但读回仍在的键
 * @property unexpected 写入前和目标里都没有、读回却出现的键（库自动补的也算）
 * @property pixelsIdentical 像素指纹是否一致；没采集指纹时为 null
 */
data class MetadataVerifyReport(
    val matched: Set<TagKey>,
    val mismatched: Map<TagKey, FieldMismatch>,
    val missing: Set<TagKey>,
    val dropped: Set<TagKey> = emptySet(),
    val notRemoved: Set<TagKey>,
    val unexpected: Set<TagKey>,
    val pixelsIdentical: Boolean?,
) {

    /**
     * 字段全部按目标落地，且像素没被动过。
     *
     * [dropped] 不算在内：写入器**事前就说了**这些键这个格式装不下，把它算成失败会让所有
     * 用得上这些键的图片永远报「校验未通过」。但它也不是免检——写入器没声明的缺失
     * （[missing]）照样是失败。
     */
    val isLossless: Boolean
        get() = mismatched.isEmpty() &&
            missing.isEmpty() &&
            notRemoved.isEmpty() &&
            unexpected.isEmpty() &&
            pixelsIdentical != false

    /** 「有几项格式存不下」的一句话；没有就是 null（报告/失败原因里带上它）。 */
    val droppedNote: String?
        get() = if (dropped.isEmpty()) null else "${dropped.size} 项格式存不下"

    /** 一句话摘要，用于日志和错误提示。 */
    fun summary(): String = buildString {
        append("匹配 ${matched.size} 项")
        if (mismatched.isNotEmpty()) append("，值不符 ${mismatched.size} 项")
        if (missing.isNotEmpty()) append("，缺失 ${missing.size} 项")
        if (dropped.isNotEmpty()) append("，格式存不下 ${dropped.size} 项")
        if (notRemoved.isNotEmpty()) append("，未删除 ${notRemoved.size} 项")
        if (unexpected.isNotEmpty()) append("，多出 ${unexpected.size} 项")
        when (pixelsIdentical) {
            true -> append("，像素未变")
            false -> append("，像素已变")
            null -> append("，未校验像素")
        }
    }

    /**
     * 比 [summary] 多一层：把出问题的键名点出来（每类最多 [maxPerCategory] 个，多的用「等 N 项」带过）。
     *
     * 批量跑完只在盘上/通知里留一行字，光看「值不符 3 项」没法定位是哪个字段；而一次写几十上百个
     * 键时全列出来又太长，所以按类截断。单张编辑页仍用 [summary]（界面宽度有限）。
     */
    fun detail(maxPerCategory: Int = 4): String = buildString {
        append("匹配 ${matched.size} 项")
        appendKeys("值不符", mismatched.keys, maxPerCategory)
        appendKeys("缺失", missing, maxPerCategory)
        appendKeys("格式存不下", dropped, maxPerCategory)
        appendKeys("未删除", notRemoved, maxPerCategory)
        appendKeys("多出", unexpected, maxPerCategory)
        when (pixelsIdentical) {
            true -> append("，像素未变")
            false -> append("，像素已变")
            null -> append("，未校验像素")
        }
    }

    private fun StringBuilder.appendKeys(label: String, keys: Collection<TagKey>, max: Int) {
        if (keys.isEmpty()) return
        append("，").append(label).append(' ').append(keys.size).append(" 项[")
        append(keys.take(max).joinToString("、"))
        if (keys.size > max) append(" 等 ").append(keys.size).append(" 项")
        append(']')
    }
}

/** 单个字段的期望值 / 实际值，都是归一化后的字面量。 */
data class FieldMismatch(
    val expected: String?,
    val actual: String?,
)
