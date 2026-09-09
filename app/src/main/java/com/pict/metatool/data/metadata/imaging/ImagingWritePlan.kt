package com.pict.metatool.data.metadata.imaging

import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue

/**
 * 一次 Commons Imaging 写入的完整指令（docs/07 T2.6）。
 *
 * 与 `ExifWritePlan` 同构，但值保持领域模型原样：commons-imaging 按 `TagInfo.dataTypes`
 * 声明的类型直接编码字节，不需要先序列化成 EXIF 字面量再反解析。
 *
 * @property removals 文件里有、目标里没有的键——从所在目录删掉该字段
 * @property assignments 已成功写进 TiffOutputSet 的键（值为写入后的领域值）
 * @property dropped 目标里要求了、但库里没有常量或无法按目录声明类型编码的键，原值保持不变
 */
data class ImagingWritePlan(
    val removals: Set<TagKey>,
    val assignments: Map<TagKey, TagValue>,
    val dropped: Set<TagKey>,
)
