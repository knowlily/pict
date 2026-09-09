package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagValue
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 领域值 → ExifInterface 字面量（docs/07 T2.3）。
 *
 * [ExifValueCodec] 的反向：读的时候把 EXIF 字面量解析成领域值，写的时候再序列化回去。
 * 纯函数、无 Android 依赖，JVM 单测直接覆盖。
 *
 * 无法无损写回的变体返回 null（二进制块、多语言文本），由调用方记入丢弃集合并**保留文件里的原值**，
 * 而不是塞一个编造的值进去。
 */
object ExifValueWriter {

    /** EXIF 时间字面量形如 `2008:07:31 10:38:11`。 */
    private val EXIF_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)

    private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.ROOT)

    private val EXIF_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    /**
     * 序列化单个值；返回 null 表示这个变体写不回去。
     *
     * `Timestamp` 的时区不拼进时间串——EXIF 把偏移放在独立的 `OffsetTime*` 字段里，
     * 拼进去会被 ExifInterface 当成非法时间。
     */
    fun format(value: TagValue): String? = when (value) {
        is TagValue.Text -> value.value
        is TagValue.UriValue -> value.value
        is TagValue.IntValue -> value.value.toString()
        is TagValue.DecimalValue -> formatDecimal(value.value)
        is TagValue.RationalValue -> formatRational(value.value)
        is TagValue.IntList -> value.values.joinToString(",")
        is TagValue.DecimalList -> value.values.joinToString(",") { formatDecimal(it) }
        is TagValue.RationalList -> value.values.joinToString(",") { formatRational(it) }
        is TagValue.Timestamp -> value.value.format(EXIF_DATETIME)
        is TagValue.DateValue -> value.value.format(EXIF_DATE)
        is TagValue.TimeValue -> value.value.format(EXIF_TIME)
        is TagValue.LangAlt -> value.values["x-default"] ?: value.values.values.firstOrNull()
        is TagValue.TextList -> value.values.joinToString(",")
        is TagValue.Binary -> null
    }

    /** 整数不带小数点；小数去掉 Java 默认表示里的尾随零噪音。 */
    fun formatDecimal(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> value.toString()
        value == value.toLong().toDouble() -> value.toLong().toString()
        else -> value.toString().trimEnd('0').trimEnd('.')
    }

    /** 分母为 0 的退化分数写成 `n/1`，避免 ExifInterface 写出非法字面量。 */
    fun formatRational(value: Rational): String =
        if (value.denominator == 0L) "${value.numerator}/1" else value.toString()
}
