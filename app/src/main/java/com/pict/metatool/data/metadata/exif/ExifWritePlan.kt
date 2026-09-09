package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.TagKey

/**
 * 一次 EXIF 写入的完整指令（docs/07 T2.3）。
 *
 * 由 [ExifMetadataStore.planWrites] 纯计算得出，和 ExifInterface 的实际调用分开，
 * 这样差异逻辑能在 JVM 上单测：`ExifInterface.setAttribute` 内部用 `android.util.Pair`
 * 传值，而 JVM 单测里的那个 Pair 是空实现、字段拿不到值，只能在设备上跑。
 *
 * @property removals 文件里有、目标里没有的键——用 null 覆盖即删除
 * @property assignments 领域键 → 待写入的 EXIF 字面量
 * @property dropped 目标里要求了、但无法序列化的键（二进制块等），原值保持不变
 */
data class ExifWritePlan(
    val removals: Set<TagKey>,
    val assignments: Map<TagKey, String>,
    val dropped: Set<TagKey>,
)
