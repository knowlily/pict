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
 * @property notRemoved 写入前有、目标里没有（本该删除）但读回仍在的键
 * @property unexpected 写入前和目标里都没有、读回却出现的键（库自动补的也算）
 * @property pixelsIdentical 像素指纹是否一致；没采集指纹时为 null
 */
data class MetadataVerifyReport(
    val matched: Set<TagKey>,
    val mismatched: Map<TagKey, FieldMismatch>,
    val missing: Set<TagKey>,
    val notRemoved: Set<TagKey>,
    val unexpected: Set<TagKey>,
    val pixelsIdentical: Boolean?,
) {

    /** 字段全部按目标落地，且像素没被动过。 */
    val isLossless: Boolean
        get() = mismatched.isEmpty() &&
            missing.isEmpty() &&
            notRemoved.isEmpty() &&
            unexpected.isEmpty() &&
            pixelsIdentical != false

    /** 一句话摘要，用于日志和错误提示。 */
    fun summary(): String = buildString {
        append("匹配 ${matched.size} 项")
        if (mismatched.isNotEmpty()) append("，值不符 ${mismatched.size} 项")
        if (missing.isNotEmpty()) append("，缺失 ${missing.size} 项")
        if (notRemoved.isNotEmpty()) append("，未删除 ${notRemoved.size} 项")
        if (unexpected.isNotEmpty()) append("，多出 ${unexpected.size} 项")
        when (pixelsIdentical) {
            true -> append("，像素未变")
            false -> append("，像素已变")
            null -> append("，未校验像素")
        }
    }
}

/** 单个字段的期望值 / 实际值，都是归一化后的字面量。 */
data class FieldMismatch(
    val expected: String?,
    val actual: String?,
)
