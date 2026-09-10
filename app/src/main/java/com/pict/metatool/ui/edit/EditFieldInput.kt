package com.pict.metatool.ui.edit

import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 单字段输入的解析与校验（docs/07 T2.10、docs/06 §3.3）。
 *
 * 设计约束：
 * - **无 Android 依赖**，纯函数，JVM 单测直接覆盖（与 [EditUiState] 同层）；
 * - 空输入 = **清除字段**，不是「不合法」——删除是常见操作，不该逼用户去点别的按钮；
 * - 解析失败只返回 [FieldInput.Invalid] + 一句给用户看的原因，不抛异常；
 * - 输入框里用**原始字面量**（`TagValueFormatter.raw`），不是展示格式：
 *   展示会把 `28/10` 美化成 `f/2.8`，回填进输入框再写回就把值改了。
 */
enum class InputKind {
    TEXT,
    INTEGER,
    DECIMAL,
    FRACTION,
    DATETIME,
    DATE,
    TIME,
    HEX,
    LIST_TEXT,
    LIST_NUMBER,
    ENUM,
}

/** 一次输入解析的结果。 */
sealed interface FieldInput {

    data class Value(val value: TagValue) : FieldInput

    /** 空输入：清除该字段。 */
    data object Clear : FieldInput

    data class Invalid(val reason: String) : FieldInput
}

/** 不合法时的一句原因，合法/清除时为 null。 */
fun FieldInput.errorOrNull(): String? = (this as? FieldInput.Invalid)?.reason

object EditFieldInput {

    private val DATETIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy:MM:dd HH:mm:ss",
        "yyyy:MM:dd HH:mm",
    )

    private val DATE_PATTERNS = listOf("yyyy-MM-dd", "yyyy:MM:dd", "yyyy/MM/dd")

    private val TIME_PATTERNS = listOf("HH:mm:ss", "HH:mm")

    private val OFFSET_TAIL = Regex("""([+-])(\d{2}):?(\d{2})$""")

    /** 列表型输入的分隔符：中英文逗号、顿号、分号都能用（用户不会记哪个才对）。 */
    private const val LIST_SEPARATORS = "[,，、;；]"

    private const val MAX_POW10 = 9

    /** 输入框该给哪种键盘。 */
    fun kindOf(spec: FieldSpec?): InputKind {
        if (spec != null && spec.options.isNotEmpty()) return InputKind.ENUM
        return when (spec?.type ?: ValueType.TEXT) {
            ValueType.INT -> InputKind.INTEGER
            ValueType.DECIMAL -> InputKind.DECIMAL
            ValueType.RATIONAL -> InputKind.FRACTION
            ValueType.INT_LIST, ValueType.DECIMAL_LIST, ValueType.RATIONAL_LIST -> InputKind.LIST_NUMBER
            ValueType.TEXT_SEQ, ValueType.TEXT_BAG -> InputKind.LIST_TEXT
            ValueType.DATETIME -> InputKind.DATETIME
            ValueType.DATE -> InputKind.DATE
            ValueType.TIME -> InputKind.TIME
            ValueType.BYTES -> InputKind.HEX
            else -> InputKind.TEXT
        }
    }

    /** 输入框下方的格式提示。 */
    fun hint(spec: FieldSpec?): String = when (kindOf(spec)) {
        InputKind.ENUM -> "可选：" + spec!!.options.joinToString(" / ") { it.second }
        InputKind.INTEGER -> "整数，例如 100"
        InputKind.DECIMAL -> "小数，例如 1.78"
        InputKind.FRACTION -> "分数或小数，例如 1/250、2.8"
        InputKind.DATETIME -> "例 2026-09-10 15:30:00，可带 +08:00"
        InputKind.DATE -> "例 2026-09-10"
        InputKind.TIME -> "例 15:30:00"
        InputKind.HEX -> "十六进制，偶数位，例 0x49492A00"
        InputKind.LIST_TEXT -> "用逗号分隔，例 上海, 外滩"
        InputKind.LIST_NUMBER -> "用逗号分隔，例 100, 200"
        InputKind.TEXT -> spec?.note ?: "文本"
    }

    /**
     * 预填值：原始字面量（回填不改变原值语义）。
     *
     * 分数单独拼 `分子/分母`：展示用的 `formatRational` 会把 `2/3` 印成 `0.6667`，
     * 照着回填就变成 `6667/10000`——用户什么都没改也会被算成一笔改动、写回文件。
     * 其余类型沿用 [TagValueFormatter.raw]，与详情页的原始值口径一致。
     */
    fun prefill(value: TagValue?): String = when (value) {
        is TagValue.RationalValue -> exact(value.value)
        is TagValue.RationalList -> value.values.joinToString(",") { exact(it) }
        else -> TagValueFormatter.raw(value)
    }

    /** 分数写回用户能再填一遍的字面量：`2/3` 就是 `2/3`。 */
    private fun exact(value: Rational): String = "${value.numerator}/${value.denominator}"

    /**
     * 解析用户输入。
     *
     * @param spec 字段目录里的定义；未登记的厂商自定义键没有类型信息，按文本处理
     */
    fun parse(text: String, spec: FieldSpec?): FieldInput {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FieldInput.Clear
        if (spec != null && !spec.canEdit) {
            return FieldInput.Invalid("${spec.writability.label}字段，改不了")
        }

        return when (spec?.type ?: ValueType.TEXT) {
            ValueType.TEXT -> FieldInput.Value(TagValue.Text(trimmed))
            ValueType.INT -> parseInt(trimmed, spec)
            ValueType.INT_LIST -> parseList(trimmed) { it.toLongOrNull() }
                ?.let { FieldInput.Value(TagValue.IntList(it)) }
                ?: FieldInput.Invalid("需要整数数组，例 100, 200")
            ValueType.DECIMAL -> parseDecimal(trimmed)?.let { FieldInput.Value(TagValue.DecimalValue(it)) }
                ?: FieldInput.Invalid("需要小数，例 1.78")
            ValueType.DECIMAL_LIST -> parseList(trimmed) { parseDecimal(it) }
                ?.let { FieldInput.Value(TagValue.DecimalList(it)) }
                ?: FieldInput.Invalid("需要小数数组，例 2.8, 3.2")
            ValueType.RATIONAL -> parseRational(trimmed)?.let { FieldInput.Value(TagValue.RationalValue(it)) }
                ?: FieldInput.Invalid("需要分数或小数，例 1/250、2.8")
            ValueType.RATIONAL_LIST -> parseList(trimmed) { parseRational(it) }
                ?.let { FieldInput.Value(TagValue.RationalList(it)) }
                ?: FieldInput.Invalid("需要分数数组，例 1/250, 2/250")
            ValueType.DATETIME -> parseDateTime(trimmed)?.let { (moment, offset) ->
                FieldInput.Value(TagValue.Timestamp(moment, offset))
            } ?: FieldInput.Invalid("需要日期时间，例 2026-09-10 15:30:00")
            ValueType.DATE -> parseDate(trimmed)?.let { FieldInput.Value(TagValue.DateValue(it)) }
                ?: FieldInput.Invalid("需要日期，例 2026-09-10")
            ValueType.TIME -> parseTime(trimmed)?.let { FieldInput.Value(TagValue.TimeValue(it)) }
                ?: FieldInput.Invalid("需要时间，例 15:30:00")
            ValueType.BYTES -> parseHex(trimmed)?.let { FieldInput.Value(it) }
                ?: FieldInput.Invalid("需要十六进制（偶数位），例 0x49492A00")
            ValueType.LANG_ALT -> FieldInput.Value(TagValue.LangAlt(mapOf("x-default" to trimmed)))
            ValueType.TEXT_SEQ, ValueType.TEXT_BAG -> splitList(trimmed)
                .takeIf { it.isNotEmpty() }
                ?.let { FieldInput.Value(TagValue.TextList(it)) }
                ?: FieldInput.Invalid("至少填一项")
            ValueType.URI -> if (trimmed.contains(':')) {
                FieldInput.Value(TagValue.UriValue(trimmed))
            } else {
                FieldInput.Invalid("需要完整链接，例 https://example.com")
            }
        }
    }

    /** 整数：枚举字段允许直接填中文选项（用户看得到的是「手动」，不该逼他记 1）。 */
    private fun parseInt(text: String, spec: FieldSpec?): FieldInput {
        val byLabel = spec?.options?.firstOrNull { it.second == text }?.first
        val value = byLabel ?: text.toLongOrNull()
        return value?.let { FieldInput.Value(TagValue.IntValue(it)) }
            ?: FieldInput.Invalid(
                if (spec != null && spec.options.isNotEmpty()) {
                    "可选：" + spec.options.joinToString(" / ") { it.second }
                } else {
                    "需要整数，例 100"
                },
            )
    }

    private fun parseDecimal(text: String): Double? =
        text.toDoubleOrNull() ?: parseRational(text)?.asDouble

    /** 分数：`1/250` 原样保留分子分母；`2.8` 转成 `28/10`，EXIF 写回时用得上。 */
    fun parseRational(text: String): Rational? {
        val slash = text.indexOf('/')
        if (slash > 0) {
            val numerator = text.substring(0, slash).trim().toLongOrNull() ?: return null
            val denominator = text.substring(slash + 1).trim().toLongOrNull() ?: return null
            if (denominator == 0L) return null
            return Rational(numerator, denominator)
        }
        val value = text.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return fromDecimal(value)
    }

    /** 小数转分数：按小数位定分母（最多 6 位），`2.8` → `28/10`。 */
    fun fromDecimal(value: Double, maxScale: Int = 6): Rational {
        val decimal = BigDecimal.valueOf(value).setScale(maxScale, RoundingMode.HALF_UP).stripTrailingZeros()
        val scale = decimal.scale()
        return if (scale <= 0) {
            Rational(decimal.unscaledValue().toLong() * pow10(-scale), 1L)
        } else {
            Rational(decimal.unscaledValue().toLong(), pow10(scale))
        }
    }

    private fun parseDateTime(text: String): Pair<LocalDateTime, ZoneOffset?>? {
        var body = text
        var offset: ZoneOffset? = null
        OFFSET_TAIL.find(text)?.let { match ->
            val (sign, hours, minutes) = match.destructured
            val h = hours.toInt()
            val m = minutes.toInt()
            if (h in 0..18 && m in 0..59) {
                val total = h * 60 + m
                offset = ZoneOffset.ofTotalSeconds(if (sign == "-") -total * 60 else total * 60)
                body = text.substring(0, match.range.first).trim().removeSuffix("Z")
            }
        }
        val moment = DATETIME_PATTERNS.firstNotNullOfOrNull { pattern ->
            runCatching { LocalDateTime.parse(body, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
        } ?: runCatching { LocalDateTime.parse(body) }.getOrNull()
        return moment?.let { it to offset }
    }

    private fun parseDate(text: String): LocalDate? =
        DATE_PATTERNS.firstNotNullOfOrNull { pattern ->
            runCatching { LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
        }

    private fun parseTime(text: String): LocalTime? =
        TIME_PATTERNS.firstNotNullOfOrNull { pattern ->
            runCatching { LocalTime.parse(text, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
        }

    /** 十六进制：允许 `0x` 前缀、空格、冒号分隔；位数必须偶数。 */
    private fun parseHex(text: String): TagValue.Binary? {
        val cleaned = text.removePrefix("0x").removePrefix("0X")
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it in "0123456789abcdefABCDEF" }) return null
        return TagValue.Binary(cleaned.lowercase(), cleaned.length / 2)
    }

    private fun <T> parseList(text: String, parseOne: (String) -> T?): List<T>? {
        val parts = splitList(text)
        if (parts.isEmpty()) return null
        return parts.map { parseOne(it) ?: return null }
    }

    private fun splitList(text: String): List<String> =
        text.split(Regex(LIST_SEPARATORS)).map { it.trim() }.filter { it.isNotEmpty() }

    private fun pow10(exponent: Int): Long {
        require(exponent in 0..MAX_POW10) { "指数超出范围：$exponent" }
        var result = 1L
        repeat(exponent) { result *= 10 }
        return result
    }
}
