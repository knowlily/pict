package com.pict.metatool.domain.format

import com.pict.metatool.domain.model.Rational
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 有理数转换（分数 ↔ 小数）的**唯一实现**。
 *
 * 为什么独立成文件：EXIF 大量字段是分数（`FNumber`、`ExposureTime`…），
 * UI 输入解析（`ui.edit.EditFieldInput`）与预设/随机填充（`domain.preset`）都要把
 * 「1.78」这样的字面量落成分数。两处各写一份，取整/分母规则迟早分叉，
 * 于是统一到这里，UI 侧改为委托调用——原行为由既有 `EditFieldInputTest` 原样守住。
 *
 * 分层：本文件属 domain 层，不依赖任何 Android API，可直接在 JVM 单测里跑。
 */
object Rationals {

    /** 分母最多放大到 10^[MAX_POW10]，超出即视为调用方给了离谱的小数位。 */
    private const val MAX_POW10 = 9

    /**
     * 小数转分数：按小数位定分母（最多 [maxScale] 位），`2.8` → `28/10`。
     * 分母保留十进制形态而非化简，便于原样写回 EXIF。
     */
    fun fromDecimal(value: Double, maxScale: Int = 6): Rational {
        val decimal = BigDecimal.valueOf(value).setScale(maxScale, RoundingMode.HALF_UP).stripTrailingZeros()
        val scale = decimal.scale()
        return if (scale <= 0) {
            Rational(decimal.unscaledValue().toLong() * pow10(-scale), 1L)
        } else {
            Rational(decimal.unscaledValue().toLong(), pow10(scale))
        }
    }

    /**
     * 文本转分数：`1/250` 原样保留分子分母；`2.8` 按 [fromDecimal] 变换。
     * 分母为 0、含非数字、空串一律返回 null（调用方负责转成校验错误）。
     */
    fun parse(text: String): Rational? {
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

    /** 数值转分数，供随机填充直接使用（与 [fromDecimal] 同一口径）。 */
    fun of(value: Double, maxScale: Int = 6): Rational = fromDecimal(value, maxScale)

    private fun pow10(exponent: Int): Long {
        require(exponent in 0..MAX_POW10) { "指数超出范围：$exponent" }
        var result = 1L
        repeat(exponent) { result *= 10 }
        return result
    }
}
