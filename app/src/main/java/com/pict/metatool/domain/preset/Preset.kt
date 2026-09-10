package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.TagKey
import java.time.LocalDateTime

/**
 * 预设模型（docs/07 T3.1、docs/03 §4/§7）。
 *
 * 设计约束：
 * - 本文件属 domain 层，**不依赖任何 Android API**，可直接在 JVM 单测里构造；
 * - 预设只描述「某个字段该从哪个分布取值」，不含任何具体随机结果——
 *   把规则变成值的是 [MetadataRandomizer]，两者分开才能对「种子 → 值」做复现性测试；
 * - 字面值保留 JSON 原始类型（[PresetValue]），落地成 `TagValue` 时按
 *   `FieldCatalog` 的类型决定（[PresetValues]）——同一份 `1.78` 在分数字段是 `178/100`，
 *   在文本字段是 `"1.78"`。
 */

/** 预设类别（docs/03 §7：设备 / 位置 / 时间 / 混合）。 */
enum class PresetKind(val id: String, val label: String) {
    DEVICE("device", "设备"),
    LOCATION("location", "位置"),
    TIME("time", "时间"),
    MIXED("mixed", "混合"),
    ;

    companion object {
        fun fromId(id: String): PresetKind? = entries.firstOrNull { it.id == id }

        val ids: List<String> get() = entries.map { it.id }
    }
}

/** 字段取值模式（docs/03 §4.1）。 */
enum class FieldMode(val id: String, val label: String) {
    FIXED("fixed", "固定值"),
    POOL("pool", "取值池"),
    RANGE("range", "数值区间"),
    DATETIME("datetime", "时间区间"),
    GPS("gps", "坐标圆面"),
    FROM_SOURCE("fromSource", "沿用原值"),
    JITTER("jitter", "就近抖动"),
    ;

    companion object {
        fun fromId(id: String): FieldMode? = entries.firstOrNull { it.id == id }

        val ids: List<String> get() = entries.map { it.id }
    }
}

/**
 * 预设里的字面值。保留 JSON 类型，不在这里判断字段类型。
 *
 * [Number.literal] 留原样字面量：整数型字段（`ISOSpeedRatings: 50`）要按 `50` 写，
 * 小数型字段（`FNumber: 1.78`）要按 `1.78` 写，靠 `Double` 反推会写出 `50.0`。
 */
sealed interface PresetValue {

    data class Text(val text: String) : PresetValue

    data class Number(val value: Double, val literal: String) : PresetValue

    data object Null : PresetValue

    companion object {
        /** 由数值构造，整数自动用整数形态的字面量。 */
        fun of(value: Double): PresetValue {
            val literal = if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
            return Number(value, literal)
        }
    }
}

/** 取值池条目（docs/03 §4.1：`value` + 可选 `weight`）。 */
data class PoolEntry(val value: PresetValue, val weight: Double = 1.0)

/** 单个字段的取值规则。 */
sealed interface FieldRule {

    val mode: FieldMode

    /** 固定值：随机填充时也照写（docs/03 §4.2）。 */
    data class Fixed(val value: PresetValue) : FieldRule {
        override val mode: FieldMode get() = FieldMode.FIXED
    }

    /** 取值池：按 [PoolEntry.weight] 加权抽样。 */
    data class Pool(val candidates: List<PoolEntry>) : FieldRule {
        override val mode: FieldMode get() = FieldMode.POOL
    }

    /** 数值区间：`[min, max]` 内均匀取值，按 [precision] 位小数落值。 */
    data class Range(val min: Double, val max: Double, val precision: Int = 2) : FieldRule {
        override val mode: FieldMode get() = FieldMode.RANGE
    }

    /**
     * 时间区间：`[start, end)` 内均匀取值；给定时段权重则先把小时/分钟换成分量
     * （docs/03 §4.1 `hourWeights` / `minuteWeights`，用于「白天拍摄」这类分布）。
     */
    data class DateTimeRule(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val hourWeights: List<Double>? = null,
        val minuteWeights: List<Double>? = null,
    ) : FieldRule {
        override val mode: FieldMode get() = FieldMode.DATETIME
    }

    /** 坐标圆面：以 ([latitude], [longitude]) 为圆心、[radiusMeters] 为半径的圆内均匀采样。 */
    data class Gps(val latitude: Double, val longitude: Double, val radiusMeters: Double) : FieldRule {
        override val mode: FieldMode get() = FieldMode.GPS
    }

    /** 沿用原值：把源文件里的 [sourceKey]（默认同名字段）原样搬过来。 */
    data class FromSource(val sourceKey: TagKey) : FieldRule {
        override val mode: FieldMode get() = FieldMode.FROM_SOURCE
    }

    /**
     * 就近抖动：经纬度在源坐标周边 [radiusMeters] 内取点；数值字段按 [percent] 比例浮动。
     * [radiusMeters] 为 null 表示用 [com.pict.metatool.domain.plan.GpsEditor.DEFAULT_JITTER_METERS]。
     */
    data class Jitter(val radiusMeters: Double? = null, val percent: Double = 0.05) : FieldRule {
        override val mode: FieldMode get() = FieldMode.JITTER
    }
}

/** 字段间约束（docs/03 §4.3）。 */
sealed interface PresetConstraint {

    /** 时间先后：按列表顺序必须递增（`DateTimeOriginal` ≤ `DateTimeDigitized` ≤ …）。 */
    data class DatetimeOrder(val keys: List<TagKey>) : PresetConstraint

    /** 互斥：至多一个字段有值。 */
    data class Mutex(val keys: List<TagKey>) : PresetConstraint

    /** 成组出现：其中任一字段有值，其余也必须有值。 */
    data class Requires(val keys: List<TagKey>) : PresetConstraint

    /** 数值收敛：把已定值夹回该字段规则给出的区间。 */
    data class Clamp(val keys: List<TagKey>) : PresetConstraint
}

/** 预设来源（内置资源 / 用户目录 / 导入文件）。 */
enum class PresetOrigin(val label: String) {
    BUILTIN("内置"),
    USER("用户"),
    IMPORTED("导入"),
}

/**
 * 一个可应用的预设。
 *
 * [fields] 用 `Map` 保存声明顺序（JSON 顺序），因为「同一预设 + 同一种子」要给出同一结果，
 * 抽样顺序必须是确定的。
 */
data class Preset(
    val id: String,
    val kind: PresetKind,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val license: String? = null,
    val fields: Map<TagKey, FieldRule> = emptyMap(),
    val constraints: List<PresetConstraint> = emptyList(),
    val origin: PresetOrigin = PresetOrigin.BUILTIN,
) {

    val keyCount: Int get() = fields.size

    fun rule(key: TagKey): FieldRule? = fields[key]

    /** 按模式筛字段，用于「只随机这几项」这类 UI 过滤。 */
    fun keysOf(mode: FieldMode): Set<TagKey> = fields.filterValues { it.mode == mode }.keys

    /** 固定值字段：随机填充时照写，不参与抽样（docs/03 §4.2）。 */
    val fixedKeys: Set<TagKey> get() = keysOf(FieldMode.FIXED)

    /** 会浮动的字段：池 / 区间 / 时间 / 坐标 / 抖动。 */
    val randomKeys: Set<TagKey>
        get() = fields.filterValues { it.mode != FieldMode.FIXED && it.mode != FieldMode.FROM_SOURCE }.keys

    companion object {
        /** 当前实现支持的 schema 版本（docs/03 §8）。 */
        const val SCHEMA_VERSION = 1
    }
}

/** 校验发现的单条问题；[path] 用 `fields.EXIF:FNumber.min` 这种点分路径，便于定位。 */
data class PresetIssue(
    val path: String,
    val message: String,
    val level: PresetIssueLevel = PresetIssueLevel.ERROR,
) {
    override fun toString(): String = "$path: $message"
}

enum class PresetIssueLevel { ERROR, WARNING }

/**
 * 预设解析结果。
 *
 * 为什么不是 `PictResult`：预设校验要一次报出**全部**问题（docs/03 §8「逐条给出错误位置」），
 * 而 `PictError` 只承载单一错误码 + 一句话。警告（未登记字段等）不阻止加载。
 */
sealed interface PresetParseResult {

    data class Success(val preset: Preset, val warnings: List<PresetIssue> = emptyList()) : PresetParseResult

    data class Invalid(val issues: List<PresetIssue>) : PresetParseResult
}

/** 预设来源集合；UI 与折叠层都只依赖这个窄接口，便于单测塞假数据。 */
interface PresetCatalog {

    fun all(): List<Preset>

    fun byId(id: String): Preset?

    companion object {
        /** 空目录：任何 id 都解析不到，用于不放预设的场景与单测默认值。 */
        val EMPTY: PresetCatalog = object : PresetCatalog {
            override fun all(): List<Preset> = emptyList()
            override fun byId(id: String): Preset? = null
        }
    }
}
