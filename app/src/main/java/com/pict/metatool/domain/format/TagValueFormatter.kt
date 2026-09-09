package com.pict.metatool.domain.format

import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagValue
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 字段值 → 展示字符串（docs/07 T1.12）。
 *
 * 原则：**只负责显示，不负责解析**。写回路径用原始 [TagValue]，绝不从显示串反解，
 * 避免「1/250 秒」这类带单位的文案污染写入层。
 */
object TagValueFormatter {

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun format(value: TagValue?, spec: FieldSpec? = null): String = when (value) {
        null -> "—"
        is TagValue.Text -> value.value.ifBlank { "—" }
        is TagValue.IntValue -> enumLabel(value.value, spec) ?: value.value.toString()
        is TagValue.DecimalValue -> trimZeros(plain(value.value))
        is TagValue.RationalValue -> formatRational(value.value, spec)
        is TagValue.IntList -> value.values.joinToString(", ").ifEmpty { "—" }
        is TagValue.DecimalList -> value.values.joinToString(", ") { trimZeros(plain(it)) }
        is TagValue.RationalList -> value.values.joinToString(", ") { formatRational(it, spec) }
        is TagValue.Timestamp -> buildString {
            append(value.value.format(DATE_TIME))
            value.offset?.let { append(" ").append(it.id) }
        }
        is TagValue.DateValue -> value.value.format(DATE)
        is TagValue.TimeValue -> value.value.format(TIME)
        is TagValue.Binary -> formatBinary(value)
        is TagValue.LangAlt -> formatLangAlt(value)
        is TagValue.TextList -> value.values.filter { it.isNotBlank() }.joinToString("、").ifEmpty { "—" }
        is TagValue.UriValue -> value.value
    }

    /** 复制到剪贴板的纯值（不带单位），如 `1/250`、`31.2372`。 */
    fun raw(value: TagValue?): String = when (value) {
        null -> ""
        is TagValue.Text -> value.value
        is TagValue.IntValue -> value.value.toString()
        is TagValue.DecimalValue -> trimZeros(plain(value.value))
        is TagValue.RationalValue -> formatRational(value.value, null)
        is TagValue.IntList -> value.values.joinToString(",")
        is TagValue.DecimalList -> value.values.joinToString(",") { trimZeros(plain(it)) }
        is TagValue.RationalList -> value.values.joinToString(",") { formatRational(it, null) }
        is TagValue.Timestamp -> value.value.format(DATE_TIME) + (value.offset?.let { " " + it.id } ?: "")
        is TagValue.DateValue -> value.value.format(DATE)
        is TagValue.TimeValue -> value.value.format(TIME)
        is TagValue.Binary -> value.hex
        is TagValue.LangAlt -> value.values["x-default"] ?: value.values.values.firstOrNull().orEmpty()
        is TagValue.TextList -> value.values.joinToString(",")
        is TagValue.UriValue -> value.value
    }

    // ---------- EXIF 专用展示 ----------

    /** 曝光时间：`1/250 秒` / `2.5 秒`。 */
    fun exposureTime(value: TagValue?): String = when (value) {
        is TagValue.RationalValue -> {
            val r = value.value
            when {
                r.denominator == 0L -> "—"
                r.numerator == 1L -> "1/${r.denominator} 秒"
                r.asDouble >= 1.0 -> "${trimZeros(plain(r.asDouble))} 秒"
                else -> "${formatRational(r, null)} 秒"
            }
        }
        else -> format(value)
    }

    /** 光圈：`f/1.8`。 */
    fun aperture(value: TagValue?): String = when (value) {
        is TagValue.RationalValue -> "f/${trimZeros(plain(value.value.asDouble))}"
        is TagValue.DecimalValue -> "f/${trimZeros(plain(value.value))}"
        else -> format(value)
    }

    /** 焦距：`4.25 mm`。 */
    fun focalLength(value: TagValue?): String = when (value) {
        is TagValue.RationalValue -> "${trimZeros(plain(value.value.asDouble))} mm"
        is TagValue.DecimalValue -> "${trimZeros(plain(value.value))} mm"
        is TagValue.IntValue -> "${value.value} mm"
        else -> format(value)
    }

    /** ISO：`ISO 100`，多值时取首个。 */
    fun iso(value: TagValue?): String = when (value) {
        is TagValue.IntList -> value.values.firstOrNull()?.let { "ISO $it" } ?: "—"
        is TagValue.IntValue -> "ISO ${value.value}"
        else -> format(value)
    }

    // ---------- 内部工具 ----------

    internal fun formatRational(r: Rational, spec: FieldSpec?): String {
        if (r.denominator == 0L) return "—"
        if (r.denominator == 1L) return r.numerator.toString()
        if (r.numerator == 0L) return "0"
        // 倒数形式（快门速度、曝光时间）保留分子 1 的写法
        if (r.numerator == 1L && r.denominator > 1L) return "1/${r.denominator}"
        val d = r.asDouble
        if (abs(d - d.roundToLong()) < 1e-9) return d.roundToLong().toString()
        return trimZeros(plain(d))
    }

    private fun enumLabel(raw: Long, spec: FieldSpec?): String? =
        spec?.options?.firstOrNull { it.first == raw }?.second

    private fun formatBinary(value: TagValue.Binary): String {
        if (value.byteCount == 0) return "—"
        val head = value.hex.take(16)
        val ellipsis = if (value.hex.length > 16) "…" else ""
        return "0x$head$ellipsis (${value.byteCount} 字节)"
    }

    private fun formatLangAlt(value: TagValue.LangAlt): String {
        val v = value.values["x-default"]
            ?: value.values["zh-CN"]
            ?: value.values["en-US"]
            ?: value.values.values.firstOrNull()
        return v?.ifBlank { "—" } ?: "—"
    }

    /** 小数最多 4 位、去掉尾随 0（1.78 → "1.78"，2.0 → "2"）。 */
    internal fun plain(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "—"
        if (abs(d - d.roundToLong()) < 1e-9 && abs(d) < 1e15) return d.roundToLong().toString()
        return trimZeros("%.4f".format(d))
    }

    internal fun trimZeros(text: String): String =
        if (!text.contains('.')) text else text.trimEnd('0').trimEnd('.')
}
