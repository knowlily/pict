package com.pict.metatool.domain.preset

import com.pict.metatool.domain.format.Rationals
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * 预设字面值 → [TagValue]（docs/03 §4.2 的「落地」一步）。
 *
 * 为什么要有这一层：预设是**类型无关**的（同一份 `1.78` 可以喂给分数字段、也可喂给文本字段），
 * 而 `TagValue` 是强类型的。转换只在应用预设时按 `FieldCatalog` 的类型发生，于是：
 * 分数型字段落成 `178/100`、整数型落成 `2`、文本型落成 `"1.78"`，
 * 同一个数值在不同字段上各得其所。
 *
 * 类型未知（目录未登记）时给「最不会撒谎」的形态：数值给 [TagValue.DecimalValue]、文本给
 * [TagValue.Text]，最终写不写得进去由写通道判定——写通道不认识就报错，而不是这里悄悄改类型。
 */
object PresetValues {

    /** XMP LangAlt 的默认语言标签。 */
    const val DEFAULT_LANGUAGE = "x-default"

    private val DOTTED_BYTES = Regex("^\\d{1,3}(\\.\\d{1,3})+$")
    private val HEX_BYTES = Regex("^[0-9a-fA-F]+$")

    private val MOMENT_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy:MM:dd HH:mm:ss",
    )

    fun toTagValue(value: PresetValue, spec: FieldSpec?): TagValue? = when (value) {
        is PresetValue.Null -> null
        is PresetValue.Text -> text(value.text, spec)
        is PresetValue.Number -> number(value.value, value.literal, spec)
    }

    /** 数值按字段类型落地；[literal] 用于文本型字段（保住 `50` 而不是 `50.0`）。 */
    fun number(value: Double, literal: String, spec: FieldSpec?): TagValue? {
        if (!value.isFinite()) return null
        return when (spec?.type) {
            null -> TagValue.DecimalValue(value)
            ValueType.INT -> TagValue.IntValue(value.roundToLong())
            ValueType.DECIMAL -> TagValue.DecimalValue(value)
            ValueType.RATIONAL -> TagValue.RationalValue(Rationals.of(value))
            ValueType.INT_LIST -> TagValue.IntList(listOf(value.roundToLong()))
            ValueType.DECIMAL_LIST -> TagValue.DecimalList(listOf(value))
            ValueType.RATIONAL_LIST -> TagValue.RationalList(listOf(Rationals.of(value)))
            ValueType.TEXT, ValueType.TEXT_SEQ, ValueType.TEXT_BAG, ValueType.LANG_ALT, ValueType.URI ->
                TagValue.Text(literal)
            else -> null
        }
    }

    /** 文本按字段类型落地；数值型字段允许从文本解析（`"1/60"` → 分数）。 */
    fun text(value: String, spec: FieldSpec?): TagValue? {
        val trimmed = value.trim()
        return when (spec?.type) {
            null, ValueType.TEXT -> TagValue.Text(value)
            ValueType.URI -> TagValue.UriValue(trimmed)
            ValueType.LANG_ALT -> TagValue.LangAlt(mapOf(DEFAULT_LANGUAGE to value))
            ValueType.TEXT_SEQ, ValueType.TEXT_BAG -> TagValue.TextList(listOf(value))
            ValueType.INT -> trimmed.toLongOrNull()?.let { TagValue.IntValue(it) }
            ValueType.DECIMAL -> trimmed.toDoubleOrNull()?.let { TagValue.DecimalValue(it) }
            ValueType.RATIONAL -> Rationals.parse(trimmed)?.let { TagValue.RationalValue(it) }
            ValueType.INT_LIST -> trimmed.toLongOrNull()?.let { TagValue.IntList(listOf(it)) }
            ValueType.DECIMAL_LIST -> trimmed.toDoubleOrNull()?.let { TagValue.DecimalList(listOf(it)) }
            ValueType.RATIONAL_LIST -> Rationals.parse(trimmed)?.let { TagValue.RationalList(listOf(it)) }
            ValueType.DATETIME -> moment(trimmed)?.let { TagValue.Timestamp(it) }
            ValueType.DATE -> dateOf(trimmed)?.let { TagValue.DateValue(it) }
            ValueType.TIME -> timeOf(trimmed)?.let { TagValue.TimeValue(it) }
            ValueType.BYTES -> bytesOf(trimmed)?.let { TagValue.Binary(it.first, it.second) }
        }
    }

    /** 时间点按字段类型落地（随机填充的 datetime 规则与时间约束用）。 */
    fun moment(value: LocalDateTime, spec: FieldSpec?): TagValue? = when (spec?.type) {
        null, ValueType.DATETIME -> TagValue.Timestamp(value)
        ValueType.DATE -> TagValue.DateValue(value.toLocalDate())
        ValueType.TIME -> TagValue.TimeValue(value.toLocalTime())
        ValueType.TEXT -> TagValue.Text(formatMoment(value))
        else -> null
    }

    /** 文本里读时间点（`2024-05-01T09:12:33`、`2024:05:01 09:12:33` 等都认）。 */
    fun moment(text: String): LocalDateTime? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        MOMENT_PATTERNS.forEach { pattern ->
            runCatching { LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern)) }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching { LocalDateTime.parse(trimmed) }.getOrNull()
    }

    /** EXIF 常规形态：`2024-05-01 09:12:33`。 */
    fun formatMoment(value: LocalDateTime): String =
        value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /** 二进制：支持十六进制（`02030000`、`0x0203 0000`）与 GPSVersionID 那种点分十进制（`2.3.0.0`）。 */
    private fun bytesOf(text: String): Pair<String, Int>? {
        if (DOTTED_BYTES.matches(text)) {
            val parts = text.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.any { it !in 0..255 }) return null
            val hex = parts.joinToString("") { byte -> "%02x".format(byte) }
            return hex to parts.size
        }
        val cleaned = text.removePrefix("0x").removePrefix("0X")
            .replace(" ", "").replace(":", "").replace("-", "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!HEX_BYTES.matches(cleaned)) return null
        return cleaned.lowercase() to cleaned.length / 2
    }

    private fun dateOf(text: String): LocalDate? =
        runCatching { LocalDate.parse(text) }.getOrNull() ?: moment(text)?.toLocalDate()

    private fun timeOf(text: String): LocalTime? =
        runCatching { LocalTime.parse(text) }.getOrNull() ?: moment(text)?.toLocalTime()
}
