package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有理数字面量解析（T5.3 真机回归）。
 *
 * androidx 的 [androidx.exifinterface.media.ExifInterface] 对 `ExposureTime`/`FNumber` 这类标签
 * 不返回 `483328/65536`，而是先转成十进制字符串（`0.00625`、`7.3`）。只认真分数时这两个键
 * 解析失败、被静默丢掉 → 写完回读永远「缺失」→ 真机上每一张都判校验未通过。
 *
 * 十进制按连分数还成**最简分数**（`0.00625` → 1/160）：读回来的值要能原样写回文件，
 * 又不能带一个 10^11 这么大的分母（32 位有理数装不下，值会被写坏）。
 */
class ExifValueCodecRationalTest {

    @Test
    fun `十进制按精确分数还原并约分`() {
        assertEquals(Rational(1, 160), ExifValueCodec.parseRational("0.00625")) // 1/160 s
        assertEquals(Rational(1, 250), ExifValueCodec.parseRational("0.004")) // 1/250 s
        assertEquals(Rational(73, 10), ExifValueCodec.parseRational("7.3")) // f/7.3
        // 目录里 f/2.8 写作 28/10，约分后 14/5——数值等价，比对时按数值走
        assertEquals(Rational(14, 5), ExifValueCodec.parseRational("2.8"))
    }

    @Test
    fun `库打印时被截断的十进制要还回简单分数，不能写坏文件`() {
        // ExifInterface 把 1/75 打成 0.01333333333（11 位）；照抄成分母 10^11 的分数写回，
        // 分母会溢出 32 位、值被写坏（金标准抓到过 0.01333333333 → 0.3519331784）
        assertEquals(Rational(1, 75), ExifValueCodec.parseRational("0.01333333333"))
        assertEquals(Rational(1, 75), ExifValueCodec.parseRational("0.013333333333333334"))
        assertTrue(ExifValueCodec.parseRational("0.01333333333")!!.denominator <= 1_000_000)
    }

    @Test
    fun `负十进制与科学计数法也能还原`() {
        assertEquals(Rational(-3, 2), ExifValueCodec.parseRational("-1.5"))
        assertEquals(Rational(-1, 4), ExifValueCodec.parseRational("-0.25"))
        assertEquals(Rational(1000, 1), ExifValueCodec.parseRational("1E+3"))
    }

    @Test
    fun `整数与真分数保持原样`() {
        assertEquals(Rational(8, 1), ExifValueCodec.parseRational("8"))
        assertEquals(Rational(1, 250), ExifValueCodec.parseRational("1/250"))
        // 真分数不约分：库里给什么就是什么，避免把原文件的写法改掉
        assertEquals(Rational(483328, 65536), ExifValueCodec.parseRational("483328/65536"))
        assertEquals(Rational(-1, 3), ExifValueCodec.parseRational("-1/3"))
    }

    @Test
    fun `解析不了的字面量仍然返回 null`() {
        assertNull(ExifValueCodec.parseRational("abc"))
        assertNull(ExifValueCodec.parseRational(""))
        assertNull(ExifValueCodec.parseRational("1/"))
        assertNull(ExifValueCodec.parseRational("1/abc"))
    }

    @Test
    fun `按目录声明的类型解析曝光时间与光圈`() {
        val timeKey = TagKey.of("EXIF:ExposureTime")
        val time = ExifValueCodec.parse("0.00625", ExifValueCodec.typeFor(timeKey, "0.00625"))
        assertEquals(TagValue.RationalValue(Rational(1, 160)), time)

        val apertureKey = TagKey.of("EXIF:FNumber")
        val aperture = ExifValueCodec.parse("7.3", ExifValueCodec.typeFor(apertureKey, "7.3"))
        assertEquals(TagValue.RationalValue(Rational(73, 10)), aperture)
    }
}
