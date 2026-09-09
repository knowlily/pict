package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 领域值 → EXIF 字面量的序列化（docs/07 T2.3）。
 *
 * 重点验证 `ExifValueCodec.parse` 能读回来的形状：写出去再读进来应当等价，
 * 读不回来的变体（二进制）必须返回 null 而不是编一个值。
 */
class ExifValueWriterTest {

    @Test
    fun `文本与 URI 原样写回`() {
        assertEquals("Canon EOS 40D", ExifValueWriter.format(TagValue.Text("Canon EOS 40D")))
        assertEquals("content://media/1", ExifValueWriter.format(TagValue.UriValue("content://media/1")))
    }

    @Test
    fun `整数不带小数点`() {
        assertEquals("400", ExifValueWriter.format(TagValue.IntValue(400)))
    }

    @Test
    fun `小数去掉尾随零`() {
        assertEquals("2.8", ExifValueWriter.format(TagValue.DecimalValue(2.8)))
        assertEquals("3", ExifValueWriter.format(TagValue.DecimalValue(3.0)))
    }

    @Test
    fun `分数写成分子斜杠分母`() {
        assertEquals("1/250", ExifValueWriter.format(TagValue.RationalValue(Rational(1, 250))))
    }

    @Test
    fun `分母为零的分数退化为分母一`() {
        assertEquals("7/1", ExifValueWriter.format(TagValue.RationalValue(Rational(7, 0))))
    }

    @Test
    fun `列表用逗号连接`() {
        assertEquals("1,2,3", ExifValueWriter.format(TagValue.IntList(listOf(1, 2, 3))))
        assertEquals(
            "1/1,2/1",
            ExifValueWriter.format(TagValue.RationalList(listOf(Rational(1, 1), Rational(2, 1)))),
        )
        assertEquals("a,b", ExifValueWriter.format(TagValue.TextList(listOf("a", "b"))))
    }

    @Test
    fun `时间写成 EXIF 冒号格式`() {
        assertEquals(
            "2008:07:31 10:38:11",
            ExifValueWriter.format(TagValue.Timestamp(LocalDateTime.of(2008, 7, 31, 10, 38, 11))),
        )
    }

    @Test
    fun `时间带时区偏移时不拼进字面量`() {
        assertEquals(
            "2008:07:31 10:38:11",
            ExifValueWriter.format(
                TagValue.Timestamp(LocalDateTime.of(2008, 7, 31, 10, 38, 11), ZoneOffset.ofHours(8)),
            ),
        )
    }

    @Test
    fun `日期与时间各自成串`() {
        assertEquals("2008:07:31", ExifValueWriter.format(TagValue.DateValue(LocalDate.of(2008, 7, 31))))
        assertEquals("10:38:11", ExifValueWriter.format(TagValue.TimeValue(LocalTime.of(10, 38, 11))))
    }

    @Test
    fun `多语言文本取默认值`() {
        assertEquals(
            "标题",
            ExifValueWriter.format(TagValue.LangAlt(mapOf("x-default" to "标题", "en" to "Title"))),
        )
        assertEquals("Title", ExifValueWriter.format(TagValue.LangAlt(mapOf("en" to "Title"))))
    }

    @Test
    fun `二进制不写回`() {
        assertNull(ExifValueWriter.format(TagValue.Binary("0A1B", 2)))
    }
}
