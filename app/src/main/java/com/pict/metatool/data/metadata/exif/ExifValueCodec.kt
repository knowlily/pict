package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * ExifInterface 字符串 → 领域值（docs/07 T1.4）。
 *
 * 纯函数、无 Android 依赖，可在 JVM 单测里用真实 EXIF 属性表直接验证。
 * 之所以不逐字段手写映射：androidx 的 `ExifInterface.attributes` 已经给出了它认识的全部标签，
 * 我们只负责「改名 + 定类型 + 转值」，目录里没登记的标签按 `EXIF:<原名>` 兜底保留，
 * 保证「读全」而不是「只读我们认识的」。
 */
object ExifValueCodec {

    /** ExifInterface 属性名 → 目录名（不一致的少数几个）。 */
    private val ALIASES: Map<String, String> = mapOf(
        "PhotographicSensitivity" to "ISOSpeedRatings",
    )

    /** 不作为元数据展示的辅助属性（缩略图尺寸、XMP 原文等）。 */
    private val SKIPPED: Set<String> = setOf("Xmp", "ThumbnailImageLength", "ThumbnailImageWidth")

    private val RATIONAL = Regex("^-?\\d+\\s*/\\s*\\d+$")
    private val DATETIME = Regex("^\\d{4}[:\\-]\\d{2}[:\\-]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}")
    private val DATE = Regex("^\\d{4}[:\\-]\\d{2}[:\\-]\\d{2}$")
    private val TIME = Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$")

    fun isSkipped(attribute: String): Boolean = attribute in SKIPPED

    /** GPS 前缀的走 GPS 命名空间，其余归 EXIF；目录里没有的也保留。 */
    fun keyFor(attribute: String): TagKey {
        val name = ALIASES[attribute] ?: attribute
        val namespace = if (name.startsWith("GPS")) "GPS" else "EXIF"
        return TagKey.of("$namespace:$name")
    }

    /** 优先用目录声明的类型（ISO 是数组、曝光是分数），目录没有就按字面量推断。 */
    fun typeFor(key: TagKey, raw: String): ValueType =
        FieldCatalog.spec(key)?.type ?: inferType(raw)

    fun inferType(raw: String): ValueType {
        val s = raw.trim()
        return when {
            RATIONAL.matches(s) -> ValueType.RATIONAL
            DATETIME.containsMatchIn(s) -> ValueType.DATETIME
            DATE.matches(s) -> ValueType.DATE
            TIME.matches(s) -> ValueType.TIME
            s.contains(',') -> if (s.split(',').all { RATIONAL.matches(it.trim()) }) {
                ValueType.RATIONAL_LIST
            } else {
                ValueType.DECIMAL_LIST
            }
            s.toLongOrNull() != null -> ValueType.INT
            s.toDoubleOrNull() != null -> ValueType.DECIMAL
            else -> ValueType.TEXT
        }
    }

    /**
     * 解析单个属性值。空串、纯空白、以及无法按声明类型解析的输入返回 null
     * （宁可少一项，也不塞脏值进 UI）。
     */
    fun parse(raw: String, type: ValueType): TagValue? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when (type) {
            ValueType.TEXT -> TagValue.Text(s)
            ValueType.URI -> TagValue.UriValue(s)
            ValueType.INT -> s.toLongOrNull()?.let { TagValue.IntValue(it) }
            ValueType.DECIMAL -> s.toDoubleOrNull()?.let { TagValue.DecimalValue(it) }
            ValueType.RATIONAL -> parseRational(s)?.let { TagValue.RationalValue(it) }
            ValueType.INT_LIST -> parseList(s) { it.toLongOrNull() }?.let { TagValue.IntList(it) }
            ValueType.DECIMAL_LIST -> parseList(s) { it.toDoubleOrNull() }?.let { TagValue.DecimalList(it) }
            ValueType.RATIONAL_LIST -> parseRationals(s)?.let { TagValue.RationalList(it) }
            ValueType.DATETIME -> parseDateTime(s)?.let { TagValue.Timestamp(it) }
            ValueType.DATE -> parseDate(s)?.let { TagValue.DateValue(it) }
            ValueType.TIME -> parseTime(s)?.let { TagValue.TimeValue(it) }
            ValueType.LANG_ALT -> TagValue.LangAlt(mapOf("x-default" to s))
            ValueType.TEXT_SEQ, ValueType.TEXT_BAG -> TagValue.TextList(splitValues(s))
            ValueType.BYTES -> TagValue.Text(s)
        }
    }

    /**
     * 解析有理数字面量。两条入路都要吃：
     * - 真分数 `483328/65536`——库对多数标签原样返回；
     * - **十进制 `0.00625`**——androidx 对 `ExposureTime`/`FNumber` 这类标签会先把有理数转成
     *   十进制字符串再返回（真机与 JVM 上一致，实测 `0.00625` / `7.1`）。
     *
     * 只认真分数时，`EXIF:ExposureTime` / `EXIF:FNumber` 会**静默解析失败**：值写进了文件
     * （exiftool 能读到），可回读永远缺这两个键 → 写完校验永远判「缺失」→ 每张都报
     * 校验未通过。十进制按连分数还成最简分数（`0.00625` → `1/160`），见 [rationalOfDecimal]。
     */
    fun parseRational(raw: String): Rational? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val slash = s.indexOf('/')
        return if (slash < 0) {
            s.toLongOrNull()?.let { Rational(it, 1) } ?: rationalOfDecimal(s)
        } else {
            val num = s.substring(0, slash).trim().toLongOrNull() ?: return null
            val den = s.substring(slash + 1).trim().toLongOrNull() ?: return null
            Rational(num, den)
        }
    }

    /**
     * 十进制字面量 → 最简分数；不是十进制就返回 null。
     *
     * 不能直接当成 `unscaledValue / 10^scale`：EXIF 有理数是两个 32 位整数，`ExifInterface`
     * 打印时已经截断（真值 `1/75` 变成 `0.01333333333`）。照抄成分母 10^11 的分数，写回文件
     * 时分母溢出 32 位、值直接写坏——金标准用例抓到过：`ExposureTime 0.01333333333 → 0.3519331784`。
     * 所以用连分数取分母不超过 [MAX_RATIONAL_DENOMINATOR] 的最佳逼近：`1/75` 还它 `1/75`，
     * `0.00625` 还它 `1/160`，误差上界约 `1/q²`。
     */
    private fun rationalOfDecimal(raw: String): Rational? {
        val value = raw.toBigDecimalOrNull()?.toDouble() ?: return null
        if (!value.isFinite()) return null
        if (value == 0.0) return Rational(0, 1)

        val sign = if (value < 0) -1L else 1L
        var x = kotlin.math.abs(value)
        var pPrev = 0L // h(-2) = 0/1
        var qPrev = 1L
        var p = 1L // h(-1) = 1/0
        var q = 0L
        var guard = 0
        while (guard++ < MAX_CONVERGENTS) {
            val a = x.toLong()
            val pNext = a * p + pPrev
            val qNext = a * q + qPrev
            // 分母超限、溢出成负数就停在上一个收敛项——宁可放宽到 1/q² 也别写坏文件
            if (qNext > MAX_RATIONAL_DENOMINATOR || qNext <= 0L || pNext < 0L) break
            pPrev = p
            qPrev = q
            p = pNext
            q = qNext
            val remainder = x - a
            if (remainder <= 0.0) break
            x = 1.0 / remainder
        }
        return if (q <= 0L) null else Rational(sign * p, q)
    }

    /** 连分数收敛项轮数上限，挡住 `1e18` 这种病态输入。 */
    private const val MAX_CONVERGENTS = 32

    /** 分母上限：压到 10^6 时逼近误差约 1e-12，又远小于 32 位有理数能存的量级。 */
    private const val MAX_RATIONAL_DENOMINATOR = 1_000_000L

    fun parseRationals(raw: String): List<Rational>? {
        val parts = splitValues(raw)
        if (parts.isEmpty()) return null
        return parts.map { parseRational(it) ?: return null }
    }

    private fun <T> parseList(raw: String, parse: (String) -> T?): List<T>? {
        val parts = splitValues(raw)
        if (parts.isEmpty()) return null
        return parts.map { parse(it) ?: return null }
    }

    /** 逗号、分号、空白都当分隔符（EXIF 里三种都出现过）。 */
    fun splitValues(raw: String): List<String> =
        raw.split(',', ';', ' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private val DATETIME_PARTS =
        Regex("^(\\d{4})[:\\-](\\d{2})[:\\-](\\d{2})[ T](\\d{2})[:\\-](\\d{2})[:\\-](\\d{2})(?:\\.(\\d+))?")

    fun parseDateTime(raw: String): LocalDateTime? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // EXIF 用 `2008:07:31 10:38:11`，ISO 用 `2008-07-31T10:38:11`，两种都吃
        DATETIME_PARTS.find(s)?.let { m ->
            val (y, mo, d, h, mi, se) = m.destructured
            return runCatching {
                LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt())
            }.getOrNull()
        }
        return runCatching { OffsetDateTime.parse(s.replaceFirst(' ', 'T')).toLocalDateTime() }
            .recoverCatching { LocalDateTime.parse(s) }
            .getOrNull()
    }

    fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().replace(':', '-')
        return runCatching { LocalDate.parse(s) }.getOrNull()
    }

    fun parseTime(raw: String): LocalTime? {
        val s = raw.trim()
        if (s.contains(',')) {
            // GPS 时间可能是 "12/1,34/1,56/1" 形式的有理数数组
            val parts = parseRationals(s) ?: return null
            if (parts.size < 3) return null
            return runCatching {
                LocalTime.of(parts[0].asDouble.toInt(), parts[1].asDouble.toInt(), parts[2].asDouble.toInt())
            }.getOrNull()
        }
        return runCatching { LocalTime.parse(s.take(8)) }.getOrNull()
    }

    /** 供日志与报告使用的时间格式（不参与 EXIF 写回）。 */
    val DISPLAY_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
