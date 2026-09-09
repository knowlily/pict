package com.pict.metatool.domain.format

import com.pict.metatool.domain.model.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPS 坐标换算（docs/07 T1.12）。 */
class GpsCoordinateTest {

    @Test
    fun `DMS 转十进制度`() {
        val d = GpsCoordinate.dmsToDecimal(listOf(Rational(31, 1), Rational(14, 1), Rational(1240, 100)))
        assertEquals(31.2367778, d, 1e-6)
    }

    @Test
    fun `缺失元素按 0 处理`() {
        assertEquals(31.0, GpsCoordinate.dmsToDecimal(listOf(Rational(31, 1))), 1e-9)
        assertEquals(31.5, GpsCoordinate.dmsToDecimal(listOf(Rational(31, 1), Rational(30, 1))), 1e-9)
        assertEquals(0.0, GpsCoordinate.dmsToDecimal(emptyList()), 1e-9)
    }

    @Test
    fun `十进制度转 DMS 再配合参考值可无损还原`() {
        listOf(31.2368, -33.8688, 116.3975, 0.0, 89.9999, -179.9999).forEach { v ->
            // EXIF 的 DMS 是无符号的，符号由 Ref（N/S/E/W）承载，必须成对还原
            val dms = GpsCoordinate.decimalToDms(v)
            val ref = GpsCoordinate.refFor(v, isLatitude = true)
            val back = GpsCoordinate.applyRef(GpsCoordinate.dmsToDecimal(dms), ref)
            assertEquals("$v 往返误差过大（ref=$ref）", v, back, 1e-6)
        }
    }

    @Test
    fun `DMS 输出无符号 符号交由参考值承载`() {
        val dms = GpsCoordinate.decimalToDms(-33.8688)
        assertTrue("度应为正数", dms[0].numerator > 0)
        assertEquals("S", GpsCoordinate.refFor(-33.8688, isLatitude = true))
        assertEquals(-33.8688, GpsCoordinate.applyRef(33.8688, "S"), 1e-6)
    }

    @Test
    fun `秒数进位会向上归一`() {
        // 31°59'59.99999" 应进位成 32°0'0"
        val decimal = 31.0 + 59.0 / 60.0 + 59.99999 / 3600.0
        val dms = GpsCoordinate.decimalToDms(decimal)
        assertEquals(32L, dms[0].numerator)
        assertEquals(0L, dms[1].numerator)
        assertEquals(0.0, dms[2].asDouble, 1e-9)
    }

    @Test
    fun `DMS 分秒为零时保持整数`() {
        val dms = GpsCoordinate.decimalToDms(31.0)
        assertEquals(listOf(31L, 0L), listOf(dms[0].numerator, dms[1].numerator))
        assertEquals(0L, dms[2].numerator)
    }

    @Test
    fun `参考值推导`() {
        assertEquals("N", GpsCoordinate.refFor(31.2, true))
        assertEquals("S", GpsCoordinate.refFor(-31.2, true))
        assertEquals("E", GpsCoordinate.refFor(116.4, false))
        assertEquals("W", GpsCoordinate.refFor(-116.4, false))
    }

    @Test
    fun `参考值应用符号`() {
        assertEquals(-31.2, GpsCoordinate.applyRef(31.2, "S"), 1e-9)
        assertEquals(-116.4, GpsCoordinate.applyRef(116.4, "W"), 1e-9)
        assertEquals(31.2, GpsCoordinate.applyRef(31.2, "N"), 1e-9)
        assertEquals(31.2, GpsCoordinate.applyRef(31.2, null), 1e-9)
        assertEquals(-31.2, GpsCoordinate.applyRef(31.2, " s "), 1e-9)
    }

    @Test
    fun `展示格式带度分秒与参考值`() {
        val text = GpsCoordinate.format(31.2368, isLatitude = true)
        assertTrue("实际：$text", text.startsWith("31°14'"))
        assertTrue("实际：$text", text.endsWith("N"))
        assertTrue("实际：$text", text.contains("\""))
    }

    @Test
    fun `南纬西经展示为负号语义`() {
        assertTrue(GpsCoordinate.format(-33.8688, true).endsWith("S"))
        assertTrue(GpsCoordinate.format(-118.2437, false).endsWith("W"))
    }

    // ---------- T1.12：分支与边界补全 ----------

    @Test
    fun `十进制度标签带方向与小数位`() {
        assertEquals("31.2304°N", GpsCoordinate.decimalLabel(31.2304, isLatitude = true))
        assertEquals("33.8688°S", GpsCoordinate.decimalLabel(-33.8688, isLatitude = true))
        assertEquals("118.2437°W", GpsCoordinate.decimalLabel(-118.2437, isLatitude = false))
        assertEquals("31.20°N", GpsCoordinate.decimalLabel(31.2, isLatitude = true, digits = 2))
    }

    @Test
    fun `展示格式支持自定义小数位`() {
        assertEquals("31°14'12\"N", GpsCoordinate.format(31.2368, isLatitude = true, digits = 0))
    }

    @Test
    fun `超出三元素的 DMS 忽略多余项`() {
        val d = GpsCoordinate.dmsToDecimal(
            listOf(Rational(31, 1), Rational(14, 1), Rational(1240, 100), Rational(999, 1)),
        )

        assertEquals(31.2367778, d, 1e-6)
    }

    @Test
    fun `秒进位不足一度时只进分`() {
        val decimal = 31.0 + 14.0 / 60.0 + 59.99999 / 3600.0
        val dms = GpsCoordinate.decimalToDms(decimal)

        assertEquals(31L, dms[0].numerator)
        assertEquals(15L, dms[1].numerator)
        assertEquals(0.0, dms[2].asDouble, 1e-9)
    }

    @Test
    fun `接近 180 度时度会进位`() {
        val dms = GpsCoordinate.decimalToDms(179.99999999)

        assertEquals(180L, dms[0].numerator)
        assertEquals(0L, dms[1].numerator)
        assertEquals(0.0, dms[2].asDouble, 1e-9)
    }

    @Test
    fun `零坐标与未知参考值走默认分支`() {
        assertEquals("N", GpsCoordinate.refFor(0.0, isLatitude = true))
        assertEquals("E", GpsCoordinate.refFor(0.0, isLatitude = false))
        assertEquals(31.2, GpsCoordinate.applyRef(31.2, ""), 1e-9)
        assertEquals(31.2, GpsCoordinate.applyRef(31.2, "X"), 1e-9)
        assertEquals(0.0, GpsCoordinate.dmsToDecimal(listOf(Rational(0, 1), Rational(0, 1))), 1e-9)
    }
}
