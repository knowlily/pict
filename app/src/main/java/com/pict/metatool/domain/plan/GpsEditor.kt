package com.pict.metatool.domain.plan

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.domain.format.GpsCoordinate
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import java.util.Random
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS 坐标编辑（docs/07 T2.8、docs/01 FR-15）。
 *
 * 范围：
 * - 手动输入：十进制度或度分秒文本（[parseDegrees] / [setFromText]），
 * - 就近抖动：在源坐标附近按半径采样（[jitter]），
 * - 两者都同步写 `GPSLatitude/Longitude` 与其 `*Ref`（docs/03 §2：EXIF 存无符号度分秒，
 *   符号由 Ref 承载），并在写入前做范围校验。
 *
 * 为什么 Ref 必须由本类一并写：只改 `GPSLatitude` 不改 `GPSLatitudeRef`，
 * 会把「南纬」读成「北纬」——这是静默的错误，比报错严重得多。
 *
 * 输出格式与读取链路一致（`ExifMetadataStore.readGps`）：度分秒用
 * [GpsCoordinate.decimalToDms]，海拔用「米 × 1000 / 1000」的有理数 + `GPSAltitudeRef`
 * （0 = 海平面以上、1 = 以下）。
 *
 * 随机可复现：`java.util.Random` 的线性同余算法由 JDK 规范固定，同种子在任何 JVM
 * 上都给出同一序列——T3.6 要求「同种子逐字段一致」，所以这里不用平台随机源。
 *
 * 说明：本类只算「目标元数据长什么样」，不落盘；真正写回由 data 层负责。
 */
object GpsEditor {

    val LATITUDE: TagKey = TagKey.of("GPS:GPSLatitude")
    val LATITUDE_REF: TagKey = TagKey.of("GPS:GPSLatitudeRef")
    val LONGITUDE: TagKey = TagKey.of("GPS:GPSLongitude")
    val LONGITUDE_REF: TagKey = TagKey.of("GPS:GPSLongitudeRef")
    val ALTITUDE: TagKey = TagKey.of("GPS:GPSAltitude")
    val ALTITUDE_REF: TagKey = TagKey.of("GPS:GPSAltitudeRef")

    /** 抖动半径默认值与可选范围（docs/01 FR-15：默认 500 m，可调 50 m–5 km）。 */
    const val DEFAULT_JITTER_METERS: Double = 500.0
    const val MIN_JITTER_METERS: Double = 50.0
    const val MAX_JITTER_METERS: Double = 5_000.0

    /** 坐标范围（docs/01 FR-15 类型校验）。 */
    const val LATITUDE_LIMIT: Double = 90.0
    const val LONGITUDE_LIMIT: Double = 180.0

    /** 球面近似用的地球平均半径（IUGG 平均半径，与 haversine 实现保持一致）。 */
    private const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    private val HEMISPHERES: Set<Char> = setOf('N', 'S', 'E', 'W')

    /** 度分秒分隔符：度符号、分符号、秒符号、弯引号、冒号。 */
    private val SEPARATORS = Regex("[\u00B0\u2032\u2033'\u0022:]")

    /** 读出的坐标三元组；[altitudeMeters] 为 null 表示源里没有海拔。 */
    data class Position(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?,
    )

    /**
     * 从 [set] 读出当前坐标（EXIF 度分秒 + Ref 合成十进制，负值即南纬/西经）。
     * 缺任一方向返回 null —— 源里没有 GPS 时调用方应显式提示，而不是当成 (0, 0)。
     */
    fun position(set: MetadataSet): Position? {
        val latitude = decimalOf(set, LATITUDE, LATITUDE_REF, isLatitude = true) ?: return null
        val longitude = decimalOf(set, LONGITUDE, LONGITUDE_REF, isLatitude = false) ?: return null
        return Position(latitude, longitude, altitudeOf(set))
    }

    /**
     * 写入十进制度坐标（负值即南/西），并同步 Ref。
     *
     * @param altitudeMeters 为 null 表示不改海拔（源里的原值保留）；
     *   给定值一律重写 `GPSAltitudeRef`，避免出现「新海拔 + 旧方向」的组合
     * @return 写好的新集合；坐标超范围或非有限值时返回失败
     */
    fun set(
        set: MetadataSet,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = null,
    ): PictResult<MetadataSet> {
        validateCoordinate(latitude, LATITUDE_LIMIT, "纬度")?.let { return it }
        validateCoordinate(longitude, LONGITUDE_LIMIT, "经度")?.let { return it }
        if (altitudeMeters != null && !altitudeMeters.isFinite()) {
            return failureOf(PictError.FIELD_INVALID, "海拔不是有效数值：$altitudeMeters")
        }

        var written = set
            .with(LATITUDE_REF, TagValue.Text(GpsCoordinate.refFor(latitude, isLatitude = true)))
            .with(LATITUDE, TagValue.RationalList(GpsCoordinate.decimalToDms(latitude)))
            .with(LONGITUDE_REF, TagValue.Text(GpsCoordinate.refFor(longitude, isLatitude = false)))
            .with(LONGITUDE, TagValue.RationalList(GpsCoordinate.decimalToDms(longitude)))

        if (altitudeMeters != null) {
            written = written
                .with(ALTITUDE_REF, TagValue.IntValue(if (altitudeMeters < 0) 1 else 0))
                .with(ALTITUDE, altitudeToRational(altitudeMeters))
        }
        return successOf(written)
    }

    /**
     * 从用户输入文本写入坐标：两个方向都必须是可识别的十进制度或度分秒（[parseDegrees]）。
     *
     * @param altitudeText 为 null / 空白表示不改海拔
     */
    fun setFromText(
        set: MetadataSet,
        latitudeText: String,
        longitudeText: String,
        altitudeText: String? = null,
    ): PictResult<MetadataSet> {
        val latitude = parseDegrees(latitudeText)
            ?: return failureOf(PictError.FIELD_INVALID, "无法识别的纬度输入：${latitudeText.trim()}")
        val longitude = parseDegrees(longitudeText)
            ?: return failureOf(PictError.FIELD_INVALID, "无法识别的经度输入：${longitudeText.trim()}")

        val altitudeText2 = altitudeText?.trim()
        val altitude = if (altitudeText2.isNullOrEmpty()) {
            null
        } else {
            altitudeText2.toDoubleOrNull()
                ?: return failureOf(PictError.FIELD_INVALID, "无法识别的海拔输入：$altitudeText2")
        }
        return set(set, latitude, longitude, altitude)
    }

    /**
     * 在源坐标附近随机抖动（docs/01 FR-15：球面等距近似，保证落在半径内）。
     *
     * 采样方式：方位角在 [0, 2π) 均匀取值，角距取 `acos`/`sqrt` 形式让点在圆面内均匀分布
     * （docs/03 §6 的 gps 类型同口径）——只取 `radius` 会全落在圆周上。
     * 海拔不参与抖动（抖的是「站在哪」，不是「站多高」）。
     *
     * @param radiusMeters 必须落在 [MIN_JITTER_METERS]~[MAX_JITTER_METERS]，
     *   越界直接失败而不是静默截断 —— 静默截断会让用户以为抖了 6 km，实际只抖了 5 km
     * @param seed 同一种子必然给出同一结果（可复现）
     * @return 抖动后的新集合；源里没有完整经纬度时返回失败
     */
    fun jitter(
        set: MetadataSet,
        radiusMeters: Double = DEFAULT_JITTER_METERS,
        seed: Long,
    ): PictResult<MetadataSet> {
        if (!radiusMeters.isFinite() || radiusMeters < MIN_JITTER_METERS || radiusMeters > MAX_JITTER_METERS) {
            return failureOf(
                PictError.FIELD_INVALID,
                "抖动半径需在 ${MIN_JITTER_METERS.toInt()}~${MAX_JITTER_METERS.toInt()} 米之间，收到：$radiusMeters",
            )
        }
        val origin = position(set)
            ?: return failureOf(
                PictError.FIELD_INVALID,
                "源文件没有完整的 GPS 坐标（$LATITUDE / $LONGITUDE），无法抖动",
            )

        val random = Random(seed)
        val moved = sampleNear(origin.latitude, origin.longitude, radiusMeters, random)
        // 海拔原样带过：set() 只在 altitudeMeters 非空时才动海拔字段
        return set(set, moved.first, moved.second, origin.altitudeMeters)
    }

    /**
     * 在 ([latitude], [longitude]) 周边 [radiusMeters] 内按圆面均匀采样，返回新坐标。
     *
     * 采样口径与 [jitter] 完全一致（方位角均匀 + `sqrt` 角距，圆面内均匀而非只落圆周），
     * 区别只有两点：
     * - 随机源由调用方传入，便于「同一种子 → 同一坐标」的复现（T3.6，预设的 gps 模式也走这里）；
     * - **不**施加 [MIN_JITTER_METERS]~[MAX_JITTER_METERS] 限制：抖动是相机端微调，
     *   而预设的坐标圆面允许街区级（几百米）到城市级（几十公里）的偏移。
     *
     * 调用方负责校验半径非负与坐标范围（见 `PresetParser` 的 gps 规则校验）。
     */
    fun sampleNear(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        random: Random,
    ): Pair<Double, Double> {
        val bearing = random.nextDouble() * 2 * Math.PI
        // sqrt 让点在圆面内均匀分布；乘满半径时距离恰为 radiusMeters
        val angularDistance = radiusMeters / EARTH_RADIUS_METERS * sqrt(random.nextDouble())
        return offset(latitude, longitude, bearing, angularDistance)
    }

    /**
     * 解析用户输入的坐标文本，返回十进制度（南纬/西经为负），无法识别返回 null。
     *
     * 支持（大小写不敏感）：
     * - 十进制度：`31.2304`、`-31.2304`、`31.2304N`、`S31.2304`
     * - 度分秒：`31°14'12.4"N`、`31 14 12.4 S`、`31:14:12.4`
     * - 分/秒缺省：`31°14'N`
     *
     * 半球字母与负号冲突（如 `N-31.2`）按无法识别处理：两者都在表达符号，
     * 猜哪一个都可能写出方向相反的坐标。范围校验不在这里做（见 [set]）。
     */
    fun parseDegrees(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        var body = trimmed
        var hemisphereSign = 0
        val first = body.first().uppercaseChar()
        val last = body.last().uppercaseChar()
        if (first in HEMISPHERES) {
            hemisphereSign = signOf(first)
            body = body.drop(1)
        } else if (last in HEMISPHERES) {
            hemisphereSign = signOf(last)
            body = body.dropLast(1)
        }

        val stripped = body.trim()
        if (stripped.isEmpty()) return null
        val negative = stripped.startsWith("-")
        if (hemisphereSign != 0 && negative) return null

        val tokens = SEPARATORS.replace(stripped, " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 3) return null

        val degrees = tokens[0].toDoubleOrNull() ?: return null
        if (!degrees.isFinite()) return null

        val minutes = if (tokens.size >= 2) tokens[1].toDoubleOrNull() ?: return null else 0.0
        val seconds = if (tokens.size >= 3) tokens[2].toDoubleOrNull() ?: return null else 0.0
        if (!minutes.isFinite() || !seconds.isFinite()) return null
        // 分/秒必须落在各自进位区间内，否则「31 90 0」这类输入会被静默算成 32.5°
        if (minutes < 0.0 || minutes >= 60.0 || seconds < 0.0 || seconds >= 60.0) return null

        val magnitude = abs(degrees) + minutes / 60.0 + seconds / 3600.0
        val sign = when {
            hemisphereSign < 0 -> -1
            hemisphereSign > 0 -> 1
            negative -> -1
            else -> 1
        }
        return sign * magnitude
    }

    /** 球面距离（haversine，米）——FR-15 的验收口径，UI 预览也用同一套。 */
    fun sphericalDistanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double,
    ): Double {
        val phi1 = Math.toRadians(latitude1)
        val phi2 = Math.toRadians(latitude2)
        val deltaPhi = phi2 - phi1
        val deltaLambda = Math.toRadians(longitude2 - longitude1)
        val a = sin(deltaPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a).coerceAtMost(1.0))
    }

    // ---------- 内部 ----------

    private fun signOf(hemisphere: Char): Int = when (hemisphere) {
        'S', 'W' -> -1
        else -> 1
    }

    private fun validateCoordinate(value: Double, limit: Double, label: String): PictResult<Nothing>? {
        if (!value.isFinite()) return failureOf(PictError.FIELD_INVALID, "$label 不是有效数值：$value")
        if (abs(value) > limit) {
            return failureOf(PictError.FIELD_INVALID, "$label 超出范围 -$limit~$limit：$value")
        }
        return null
    }

    private fun decimalOf(
        set: MetadataSet,
        key: TagKey,
        refKey: TagKey,
        isLatitude: Boolean,
    ): Double? {
        val dms = (set[key] as? TagValue.RationalList)?.values ?: return null
        val ref = (set[refKey] as? TagValue.Text)?.value
        val magnitude = GpsCoordinate.dmsToDecimal(dms)
        // 没有 Ref 时按正方向处理（EXIF 缺 Ref 时 exiftool 也读作正）
        return if (ref == null) magnitude else GpsCoordinate.applyRef(magnitude, ref)
    }

    private fun altitudeOf(set: MetadataSet): Double? {
        val value = (set[ALTITUDE] as? TagValue.RationalValue)?.value ?: return null
        val belowSeaLevel = (set[ALTITUDE_REF] as? TagValue.IntValue)?.value == 1L
        return if (belowSeaLevel) -abs(value.asDouble) else value.asDouble
    }

    /** 海拔按毫米精度写入（与读取链路 `× 1000 / 1000` 对称）。 */
    private fun altitudeToRational(meters: Double): TagValue.RationalValue {
        val numerator = (meters * 1000).toLong()
        return TagValue.RationalValue(Rational(numerator, 1000))
    }

    /**
     * 在球面上从 (lat, lon) 沿 [bearing] 方向前进 [angularDistance]（弧度）。
     * 经度结果归一化到 -180~180：跨 ±180 时（如东京以东）不归一会写出 181° 这类值。
     */
    private fun offset(
        latitude: Double,
        longitude: Double,
        bearing: Double,
        angularDistance: Double,
    ): Pair<Double, Double> {
        val phi1 = Math.toRadians(latitude)
        val lambda1 = Math.toRadians(longitude)
        val phi2 = asin(
            (sin(phi1) * cos(angularDistance) +
                cos(phi1) * sin(angularDistance) * cos(bearing)).coerceIn(-1.0, 1.0),
        )
        val lambda2 = lambda1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(phi1),
            cos(angularDistance) - sin(phi1) * sin(phi2),
        )
        val normalised = ((Math.toDegrees(lambda2) + 540.0) % 360.0) - 180.0
        val clipped = Math.toDegrees(phi2).coerceIn(-LATITUDE_LIMIT, LATITUDE_LIMIT)
        return clipped to normalised
    }
}
