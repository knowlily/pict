package com.pict.metatool.domain.format

import com.pict.metatool.domain.model.Rational
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * GPS 坐标转换（docs/07 T1.12）。
 *
 * EXIF 里经纬度是「度/分/秒」三元素有理数数组，展示与随机填充都需要在两种表示之间来回转。
 * 这里只做纯计算，不碰 Android API。
 */
object GpsCoordinate {

    /** DMS → 十进制度。输入长度不足 3 时缺失部分按 0 处理；超出部分忽略。 */
    fun dmsToDecimal(dms: List<Rational>): Double {
        if (dms.isEmpty()) return 0.0
        val d = dms.getOrNull(0)?.asDouble ?: 0.0
        val m = dms.getOrNull(1)?.asDouble ?: 0.0
        val s = dms.getOrNull(2)?.asDouble ?: 0.0
        return d + m / 60.0 + s / 3600.0
    }

    /**
     * 十进制度 → DMS 有理数数组，**输出无符号**（与 EXIF 一致：符号由 `GPSLatitudeRef` /
     * `GPSLongitudeRef` 承载，调用方需配合 [refFor] 使用，否则负值会丢符号）。
     * 分与秒用 1/10000 精度的有理数表示，写回 EXIF 后 exiftool 读出的误差 < 0.4 米（赤道）。
     */
    fun decimalToDms(decimal: Double): List<Rational> {
        val abs = abs(decimal)
        var deg = abs.toInt()
        val minFull = (abs - deg) * 60.0
        var min = minFull.toInt()
        var sec = (minFull - min) * 60.0

        // 处理进位：59.9999" → 60" 时向上归一
        val secTenThousandths = (sec * 10_000).roundToLong()
        if (secTenThousandths >= 600_000L) {
            sec = 0.0
            min += 1
            if (min >= 60) {
                min = 0
                deg += 1
            }
        } else {
            sec = secTenThousandths / 10_000.0
        }

        return listOf(
            Rational(deg.toLong(), 1),
            Rational(min.toLong(), 1),
            Rational((sec * 10_000).roundToLong(), 10_000),
        )
    }

    /** 按参考值（N/S/E/W）给十进制度加符号。 */
    fun applyRef(decimal: Double, ref: String?): Double = when (ref?.trim()?.uppercase()) {
        "S", "W" -> -abs(decimal)
        else -> abs(decimal)
    }

    /** 根据符号推导 EXIF 参考值：纬度 N/S，经度 E/W。 */
    fun refFor(decimal: Double, isLatitude: Boolean): String = when {
        isLatitude && decimal < 0 -> "S"
        isLatitude -> "N"
        decimal < 0 -> "W"
        else -> "E"
    }

    /** 展示用：`31°14'12.4"N`。 */
    fun format(decimal: Double, isLatitude: Boolean, digits: Int = 4): String {
        val ref = refFor(decimal, isLatitude)
        val dms = decimalToDms(decimal)
        val d = dms[0].numerator
        val m = dms[1].numerator
        val s = dms[2].asDouble
        val secText = trimZeros("%.${digits}f".format(s))
        return "$d°$m'$secText\"$ref"
    }

    /** 概览页用：`31.2304°N`（十进制度，取绝对值并附方向）。 */
    fun decimalLabel(decimal: Double, isLatitude: Boolean, digits: Int = 4): String {
        val ref = refFor(decimal, isLatitude)
        return "%.${digits}f°$ref".format(abs(decimal))
    }

    internal fun trimZeros(text: String): String =
        if (!text.contains('.')) text else text.trimEnd('0').trimEnd('.')
}
