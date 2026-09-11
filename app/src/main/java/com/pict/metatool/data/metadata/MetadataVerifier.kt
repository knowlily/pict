package com.pict.metatool.data.metadata

import com.pict.metatool.data.metadata.exif.ExifValueWriter
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue

/**
 * 写入无损校验（docs/07 T2.4）。
 *
 * 只做比对，不碰文件：写入前后各读一次 [MetadataSet]、各采一次像素指纹，交给这里判断
 * 「目标字段是否都落地、该删的是否都删掉、有没有凭空多出字段、像素有没有被改动」。
 *
 * 纯函数、无 Android 依赖，JVM 单测直接覆盖。真正的重解析和指纹采集由调用方完成，
 * 因为那两步需要 [android.content.ContentResolver] 和图像解码器。
 */
object MetadataVerifier {

    /**
     * 比对三个快照。
     *
     * @param before 写入前读到的元数据，用来区分「该删的没删」和「本来就有」
     * @param target 本次写入的目标元数据
     * @param after 写入后重新读到的元数据
     * @param fingerprintBefore 写入前的像素指纹；任一为空则不校验像素
     * @param fingerprintAfter 写入后的像素指纹
     * @param dropped 写入器**事前声明**丢弃的键（`WriteResult.droppedKeys`）。这些键读不回来
     *   不算缺失（它早知道装不下），单独记一档；但只信声明——真写进去了就照样比对读回值。
     */
    fun compare(
        before: MetadataSet,
        target: MetadataSet,
        after: MetadataSet,
        fingerprintBefore: String? = null,
        fingerprintAfter: String? = null,
        dropped: Set<TagKey> = emptySet(),
    ): MetadataVerifyReport {
        val matched = mutableSetOf<TagKey>()
        val mismatched = mutableMapOf<TagKey, FieldMismatch>()
        val missing = mutableSetOf<TagKey>()
        val declaredDropped = mutableSetOf<TagKey>()

        for ((key, expected) in target.entries) {
            val actual = after.entries[key]
            when {
                actual == null && key in dropped -> declaredDropped += key
                actual == null -> missing += key
                sameValue(expected, actual) -> matched += key
                else -> mismatched[key] = FieldMismatch(normalize(expected), normalize(actual))
            }
        }

        // 目标里没有、写入前有：本该被删掉，读回还在就是没删干净
        val notRemoved = (before.entries.keys - target.entries.keys)
            .filter { it in after.entries }
            .toSet()

        // 写入前和目标里都没有：要么是库自动补的，要么是我们不该写的东西
        val unexpected = after.entries.keys - target.entries.keys - before.entries.keys

        val pixelsIdentical = when {
            fingerprintBefore == null || fingerprintAfter == null -> null
            else -> fingerprintBefore == fingerprintAfter
        }

        return MetadataVerifyReport(
            matched = matched,
            mismatched = mismatched,
            missing = missing,
            dropped = declaredDropped,
            notRemoved = notRemoved,
            unexpected = unexpected,
            pixelsIdentical = pixelsIdentical,
        )
    }

    /** 归一化成 EXIF 字面量后比较；写不回去的变体（二进制块）退化成 toString。 */
    private fun normalize(value: TagValue): String =
        ExifValueWriter.format(value) ?: value.toString()

    /**
     * 值是否等价。
     *
     * 先比对象，再比归一化字面量，最后按数值比：`28/10` 与 `2.8` 是同一个数，
     * 读回时 EXIF 用哪种写法取决于标签类型，不该判成不一致。
     */
    private fun sameValue(expected: TagValue, actual: TagValue): Boolean {
        if (expected == actual) return true
        if (normalize(expected) == normalize(actual)) return true
        val a = expected.numericOrNull() ?: return false
        val b = actual.numericOrNull() ?: return false
        return a == b
    }

    private fun TagValue.numericOrNull(): Double? = when (this) {
        is TagValue.IntValue -> value.toDouble()
        is TagValue.DecimalValue -> value
        is TagValue.RationalValue -> value.asDouble
        else -> null
    }
}
