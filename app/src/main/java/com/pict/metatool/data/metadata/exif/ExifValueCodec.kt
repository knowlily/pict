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

    fun parseRational(raw: String): Rational? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val slash = s.indexOf('/')
        return if (slash < 0) {
            s.toLongOrNull()?.let { Rational(it, 1) }
        } else {
            val num = s.substring(0, slash).trim().toLongOrNull() ?: return null
            val den = s.substring(slash + 1).trim().toLongOrNull() ?: return null
            Rational(num, den)
        }
    }

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
