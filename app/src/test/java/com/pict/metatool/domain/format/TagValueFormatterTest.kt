package com.pict.metatool.domain.format

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** 值格式化（docs/07 T1.12）。 */
class TagValueFormatterTest {

    @Test
    fun `空值统一显示破折号`() {
        assertEquals("—", TagValueFormatter.format(null))
        assertEquals("—", TagValueFormatter.format(TagValue.Text("")))
    }

    @Test
    fun `有理数保持倒数写法`() {
        assertEquals("1/250", TagValueFormatter.format(TagValue.RationalValue(Rational(1, 250))))
        assertEquals("2.5", TagValueFormatter.format(TagValue.RationalValue(Rational(5, 2))))
        assertEquals("72", TagValueFormatter.format(TagValue.RationalValue(Rational(72, 1))))
        assertEquals("—", TagValueFormatter.format(TagValue.RationalValue(Rational(0, 0))))
    }

    @Test
    fun `小数去掉尾随零`() {
        assertEquals("2", TagValueFormatter.format(TagValue.DecimalValue(2.0)))
        assertEquals("1.78", TagValueFormatter.format(TagValue.DecimalValue(1.78)))
        assertEquals("0.3333", TagValueFormatter.format(TagValue.DecimalValue(1.0 / 3.0)))
    }

    @Test
    fun `枚举字段显示中文标签`() {
        val spec = FieldCatalog.spec("EXIF:ResolutionUnit")!!
        assertEquals("英寸", TagValueFormatter.format(TagValue.IntValue(2), spec))
        assertEquals("厘米", TagValueFormatter.format(TagValue.IntValue(3), spec))
        // 不在选项内时回退到原始数值，不吞数据
        assertEquals("9", TagValueFormatter.format(TagValue.IntValue(9), spec))
        // 没有 spec 时保持数值
        assertEquals("2", TagValueFormatter.format(TagValue.IntValue(2)))
    }

    @Test
    fun `时间字段按可读格式展示`() {
        val ts = TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 17, 27, 29), ZoneOffset.ofHours(8))
        assertEquals("2026-09-09 17:27:29 +08:00", TagValueFormatter.format(ts))
        assertEquals(
            "2026-09-09 17:27:29",
            TagValueFormatter.format(TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 17, 27, 29))),
        )
        assertEquals("2026-09-09", TagValueFormatter.format(TagValue.DateValue(LocalDate.of(2026, 9, 9))))
        assertEquals("17:27:29", TagValueFormatter.format(TagValue.TimeValue(LocalTime.of(17, 27, 29))))
    }

    @Test
    fun `二进制只展示前缀与字节数`() {
        val bin = TagValue.Binary(hex = "0123456789abcdef0123", byteCount = 10)
        val text = TagValueFormatter.format(bin)
        assertEquals("0x0123456789abcdef… (10 字节)", text)
        assertEquals("0x01 (1 字节)", TagValueFormatter.format(TagValue.Binary("01", 1)))
    }

    @Test
    fun `多语言文本优先默认语言`() {
        val alt = TagValue.LangAlt(mapOf("zh-CN" to "你好", "x-default" to "Hello"))
        assertEquals("Hello", TagValueFormatter.format(alt))
        assertEquals("你好", TagValueFormatter.format(TagValue.LangAlt(mapOf("zh-CN" to "你好"))))
        assertEquals("—", TagValueFormatter.format(TagValue.LangAlt(emptyMap())))
    }

    @Test
    fun `列表用顿号或逗号连接`() {
        assertEquals("a、b", TagValueFormatter.format(TagValue.TextList(listOf("a", "b"))))
        assertEquals("1, 2, 3", TagValueFormatter.format(TagValue.IntList(listOf(1, 2, 3))))
        assertEquals("1/250, 1/60", TagValueFormatter.format(
            TagValue.RationalList(listOf(Rational(1, 250), Rational(1, 60))),
        ))
    }

    @Test
    fun `EXIF 专用展示带单位`() {
        assertEquals("1/250 秒", TagValueFormatter.exposureTime(TagValue.RationalValue(Rational(1, 250))))
        assertEquals("2.5 秒", TagValueFormatter.exposureTime(TagValue.RationalValue(Rational(5, 2))))
        assertEquals("f/1.78", TagValueFormatter.aperture(TagValue.RationalValue(Rational(178, 100))))
        assertEquals("4.25 mm", TagValueFormatter.focalLength(TagValue.RationalValue(Rational(425, 100))))
        assertEquals("ISO 100", TagValueFormatter.iso(TagValue.IntList(listOf(100))))
    }

    @Test
    fun `raw 输出不带单位便于写回与复制`() {
        assertEquals("1/250", TagValueFormatter.raw(TagValue.RationalValue(Rational(1, 250))))
        assertEquals("31.2368", TagValueFormatter.raw(TagValue.DecimalValue(31.2368)))
        assertEquals("", TagValueFormatter.raw(null))
    }
}
