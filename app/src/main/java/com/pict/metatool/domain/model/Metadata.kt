package com.pict.metatool.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 元数据领域模型（docs/03 §2、docs/07 T1.1）。
 *
 * 设计约束（ADR-01 / docs/02 §4）：
 * - 本文件属于 domain 层，**不依赖任何 Android API**，可直接在 JVM 单测中构造。
 * - 展示文案由 [FieldSpec.label] 提供（中文字符串），i18n 后续按 id 映射，不在此处引 R。
 */

/** 字段分组（附录 A 图例：B/C/E/G/T/X/I/F）。 */
enum class FieldGroup(val id: String, val label: String) {
    BASIC("B", "基础"),
    CAMERA("C", "相机"),
    EXPOSURE("E", "曝光"),
    LOCATION("G", "位置"),
    TIME("T", "时间"),
    XMP("X", "XMP"),
    IPTC("I", "IPTC"),
    FILE("F", "文件结构"),
    ;

    companion object {
        fun fromId(id: String): FieldGroup? = entries.firstOrNull { it.id == id }
    }
}

/** 值类型（附录 A「类型」列）。 */
enum class ValueType(val label: String) {
    TEXT("文本"),
    INT("整数"),
    INT_LIST("整数数组"),
    RATIONAL("分数"),
    RATIONAL_LIST("分数数组"),
    DECIMAL("小数"),
    DECIMAL_LIST("小数数组"),
    DATETIME("日期时间"),
    DATE("日期"),
    TIME("时间"),
    BYTES("二进制"),
    LANG_ALT("多语言文本"),
    TEXT_SEQ("文本序列"),
    TEXT_BAG("文本集合"),
    URI("URI"),
    ;

    val isNumeric: Boolean
        get() = this == INT || this == DECIMAL || this == RATIONAL ||
            this == INT_LIST || this == DECIMAL_LIST || this == RATIONAL_LIST

    val isTemporal: Boolean
        get() = this == DATETIME || this == DATE || this == TIME
}

/** 可写性（附录 A 图例 ✅ / ⚠️ / ❌）。 */
enum class Writability(val symbol: String, val label: String) {
    WRITABLE("✅", "可写"),
    CONDITIONAL("⚠️", "有条件"),
    READ_ONLY("❌", "只读"),
    ;

    val canEdit: Boolean get() = this != READ_ONLY
}

/** 标签键：`命名空间:名称`，如 `EXIF:DateTimeOriginal`、`XMP:dc:title`。 */
data class TagKey(val namespace: String, val name: String) {

    /** 规范全名，用于日志、导出报告、与 exiftool 比对。 */
    val full: String get() = "$namespace:$name"

    override fun toString(): String = full

    companion object {
        /** 解析 `EXIF:Foo` / `XMP:dc:title`（名称内允许再出现冒号）。 */
        fun of(full: String): TagKey {
            val idx = full.indexOf(':')
            require(idx > 0 && idx < full.length - 1) { "非法的 TagKey：$full" }
            return TagKey(full.substring(0, idx), full.substring(idx + 1))
        }
    }
}

/** 有理数（EXIF 大量使用），保留分子分母以便原样写回。 */
data class Rational(val numerator: Long, val denominator: Long) {
    val asDouble: Double get() = if (denominator == 0L) 0.0 else numerator.toDouble() / denominator

    /** 简化后展示，如 `1/250`、`178/100`。 */
    override fun toString(): String = "$numerator/$denominator"
}

/**
 * 字段值。用密封接口而非 `Any`，让写入层可以按类型分派、格式化器可以穷举。
 * 二进制统一存十六进制字符串，避免 data class 持有 ByteArray 引发的 equals 陷阱。
 */
sealed interface TagValue {

    data class Text(val value: String) : TagValue

    data class IntValue(val value: Long) : TagValue

    data class DecimalValue(val value: Double) : TagValue

    data class RationalValue(val value: Rational) : TagValue

    data class IntList(val values: List<Long>) : TagValue

    data class DecimalList(val values: List<Double>) : TagValue

    data class RationalList(val values: List<Rational>) : TagValue

    /** 无时区语义的 EXIF 时间；offset 仅在 `OffsetTime*` 存在时非空。 */
    data class Timestamp(val value: LocalDateTime, val offset: ZoneOffset? = null) : TagValue

    data class DateValue(val value: LocalDate) : TagValue

    data class TimeValue(val value: LocalTime) : TagValue

    data class Binary(val hex: String, val byteCount: Int) : TagValue

    /** XMP LangAlt，键为语言标签（`x-default`、`zh-CN`…）。 */
    data class LangAlt(val values: Map<String, String>) : TagValue

    data class TextList(val values: List<String>) : TagValue

    data class UriValue(val value: String) : TagValue
}

/** 来源文件信息（只读快照，不含 URI 权限细节）。 */
data class SourceInfo(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val format: ImageFormatHint,
)

/** 格式提示：仅用于路由到合适的读取器，不代表能力承诺。 */
enum class ImageFormatHint(val label: String, val extensions: Set<String>) {
    JPEG("JPEG", setOf("jpg", "jpeg", "jpe")),
    PNG("PNG", setOf("png")),
    WEBP("WebP", setOf("webp")),
    HEIF("HEIF", setOf("heic", "heif", "avif")),
    BMP("BMP", setOf("bmp")),
    TIFF("TIFF", setOf("tif", "tiff", "dng")),
    RAW("RAW", setOf("cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "pef", "srw", "nrw")),
    UNKNOWN("未知", emptySet()),
    ;

    companion object {
        fun fromExtension(ext: String?): ImageFormatHint {
            val e = ext?.lowercase() ?: return UNKNOWN
            return entries.firstOrNull { e in it.extensions } ?: UNKNOWN
        }

        fun fromMimeType(mime: String?): ImageFormatHint {
            val m = mime?.lowercase() ?: return UNKNOWN
            return when {
                m.contains("jpeg") || m.contains("jpg") -> JPEG
                m.contains("png") -> PNG
                m.contains("webp") -> WEBP
                m.contains("heic") || m.contains("heif") || m.contains("avif") -> HEIF
                m.contains("bmp") -> BMP
                m.contains("tiff") -> TIFF
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 一次读取得到的元数据集合。
 * 不可变；`entries` 的迭代顺序按 [FieldCatalog] 顺序由调用方决定（Map 本身不保证）。
 */
data class MetadataSet(
    val source: SourceInfo,
    val entries: Map<TagKey, TagValue> = emptyMap(),
) {

    val isEmpty: Boolean get() = entries.isEmpty()

    val size: Int get() = entries.size

    operator fun get(key: TagKey): TagValue? = entries[key]

    operator fun get(full: String): TagValue? = entries[TagKey.of(full)]

    /** 按分组筛选，保持传入顺序由 [FieldCatalog] 决定（见 `FieldCatalog.group()`）。 */
    fun inGroup(group: FieldGroup): Map<TagKey, TagValue> =
        entries.filterKeys { FieldCatalog.spec(it)?.group == group }

    fun with(key: TagKey, value: TagValue): MetadataSet = copy(entries = entries + (key to value))

    fun without(key: TagKey): MetadataSet = copy(entries = entries - key)

    /**
     * 合并：以 [other] 为准（后写入的覆盖先前的）。
     * 用于「ExifInterface 读到的 EXIF + MetadataExtractor 读到的 IPTC」这类多来源合并。
     */
    fun merge(other: MetadataSet): MetadataSet = copy(entries = entries + other.entries)

    /** 值发生变化的键集合（新增、删除、值不同）。 */
    fun changedKeys(other: MetadataSet): Set<TagKey> {
        val all = entries.keys + other.entries.keys
        return all.filter { entries[it] != other.entries[it] }.toSet()
    }

    /** 双方都有且值相同的键。 */
    fun commonKeys(other: MetadataSet): Set<TagKey> =
        entries.keys.intersect(other.entries.keys).filter { entries[it] == other.entries[it] }.toSet()

    companion object {
        fun empty(format: ImageFormatHint = ImageFormatHint.UNKNOWN): MetadataSet =
            MetadataSet(SourceInfo("", null, null, format))
    }
}
