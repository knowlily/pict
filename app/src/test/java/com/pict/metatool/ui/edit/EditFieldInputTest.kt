package com.pict.metatool.ui.edit

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单字段输入的解析（docs/07 T2.10、docs/06 §3.3）。
 *
 * 这一层不碰 Android API，规则全靠断言钉住：空输入=清除、只读字段拒绝、
 * 分数原样保留、数组分隔符宽容、十六进制偶数位。
 */
class EditFieldInputTest {

    private fun specOf(full: String) = FieldCatalog.spec(TagKey.of(full))

    private fun parse(text: String, full: String): FieldInput = EditFieldInput.parse(text, specOf(full))

    /** 取不合法时的原因；合法/清除时直接让测试失败。 */
    private fun reasonOf(text: String, full: String): String =
        parse(text, full).errorOrNull() ?: error("期望「$text」解析失败，实际成功了")

    @Test
    fun `空输入表示清除字段而不是不合法`() {
        assertEquals(FieldInput.Clear, parse("", "EXIF:Make"))
        assertEquals(FieldInput.Clear, parse("   ", "EXIF:Make"))
        assertNull(parse("", "EXIF:Make").errorOrNull())
    }

    @Test
    fun `文本去掉首尾空白后原样保留`() {
        assertEquals(FieldInput.Value(TagValue.Text("Apple")), parse("  Apple ", "EXIF:Make"))
    }

    @Test
    fun `只读字段直接拒绝并说清原因`() {
        assertTrue(reasonOf("4000", "EXIF:ImageWidth").contains("只读"))
    }

    @Test
    fun `整数按整型解析`() {
        assertEquals(FieldInput.Value(TagValue.IntValue(1)), parse("1", "EXIF:Orientation"))
    }

    @Test
    fun `整数不接受非数字`() {
        assertTrue(reasonOf("横着", "EXIF:Orientation").contains("整数"))
    }

    @Test
    fun `枚举字段可以直接填中文选项`() {
        val spec = FieldCatalog.all.first { it.options.isNotEmpty() }
        val (value, label) = spec.options.first()

        assertEquals(InputKind.ENUM, EditFieldInput.kindOf(spec))
        assertEquals(FieldInput.Value(TagValue.IntValue(value)), EditFieldInput.parse(label, spec))
        assertTrue(EditFieldInput.hint(spec).startsWith("可选："))
    }

    @Test
    fun `分数保留用户写的分子分母`() {
        assertEquals(
            FieldInput.Value(TagValue.RationalValue(Rational(1, 250))),
            parse("1/250", "EXIF:XResolution"),
        )
    }

    @Test
    fun `小数按小数位折成分数`() {
        assertEquals(
            FieldInput.Value(TagValue.RationalValue(Rational(28, 10))),
            parse("2.8", "EXIF:XResolution"),
        )
    }

    @Test
    fun `分母为零不接受`() {
        assertTrue(reasonOf("1/0", "EXIF:XResolution").contains("分数"))
    }

    @Test
    fun `分数数组认得中文顿号和空格`() {
        assertEquals(
            FieldInput.Value(TagValue.RationalList(listOf(Rational(1, 3), Rational(2, 3)))),
            parse(" 1/3 、 2/3 ", "EXIF:WhitePoint"),
        )
    }

    @Test
    fun `日期时间接受带偏移的写法`() {
        assertEquals(
            FieldInput.Value(
                TagValue.Timestamp(LocalDateTime.of(2026, 9, 10, 15, 30, 0), ZoneOffset.ofHours(8)),
            ),
            parse("2026-09-10 15:30:00+08:00", "EXIF:DateTime"),
        )
    }

    @Test
    fun `日期时间也接受 EXIF 的冒号分隔写法`() {
        assertEquals(
            FieldInput.Value(
                TagValue.Timestamp(LocalDateTime.of(2026, 9, 10, 15, 30, 0)),
            ),
            parse("2026:09:10 15:30:00", "EXIF:DateTime"),
        )
    }

    @Test
    fun `日期时间认不出来时给出原因`() {
        assertTrue(reasonOf("昨天晚上", "EXIF:DateTime").contains("日期时间"))
    }

    @Test
    fun `十六进制按偶数位收下并转小写`() {
        assertEquals(
            FieldInput.Value(TagValue.Binary("49492a00", 4)),
            parse("0x49492A00", "EXIF:XPTitle"),
        )
    }

    @Test
    fun `十六进制奇数位不接受`() {
        assertTrue(reasonOf("0x494", "EXIF:XPTitle").contains("十六进制"))
    }

    @Test
    fun `未登记的厂商字段按文本处理`() {
        assertNull(FieldCatalog.spec(TagKey.of("VENDOR:Custom")))
        assertEquals(FieldInput.Value(TagValue.Text("hello")), EditFieldInput.parse("hello", null))
        assertEquals(InputKind.TEXT, EditFieldInput.kindOf(null))
        assertEquals("文本", EditFieldInput.hint(null))
    }

    @Test
    fun `键盘类型跟着字段类型走`() {
        assertEquals(InputKind.TEXT, EditFieldInput.kindOf(specOf("EXIF:Make")))
        assertEquals(InputKind.INTEGER, EditFieldInput.kindOf(specOf("EXIF:Orientation")))
        assertEquals(InputKind.FRACTION, EditFieldInput.kindOf(specOf("EXIF:XResolution")))
        assertEquals(InputKind.LIST_NUMBER, EditFieldInput.kindOf(specOf("EXIF:WhitePoint")))
        assertEquals(InputKind.DATETIME, EditFieldInput.kindOf(specOf("EXIF:DateTime")))
        assertEquals(InputKind.HEX, EditFieldInput.kindOf(specOf("EXIF:XPTitle")))
    }

    @Test
    fun `目录声明为文本的字段按文本给提示`() {
        val spec = FieldCatalog.all.first { it.type == ValueType.TEXT && it.canEdit }
        assertTrue(EditFieldInput.hint(spec).isNotEmpty())
    }

    /**
     * 预填与解析必须互为逆运算：输入框里放的就是**原始字面量**，
     * 展示格式（`f/2.8`）回填会把值改掉。
     */
    @Test
    fun `预填的原始字面量回填后还是同一个值`() {
        val cases = listOf(
            TagKey.of("EXIF:Make") to TagValue.Text("Apple"),
            TagKey.of("EXIF:Orientation") to TagValue.IntValue(1),
            TagKey.of("EXIF:XResolution") to TagValue.RationalValue(Rational(1, 250)),
            TagKey.of("EXIF:WhitePoint") to TagValue.RationalList(listOf(Rational(1, 3), Rational(2, 3))),
            TagKey.of("EXIF:DateTime") to TagValue.Timestamp(LocalDateTime.of(2026, 9, 10, 15, 30, 0)),
            TagKey.of("EXIF:DateTime") to TagValue.Timestamp(
                LocalDateTime.of(2026, 9, 10, 15, 30, 0),
                ZoneOffset.ofHours(8),
            ),
        )

        cases.forEach { (key, value) ->
            val text = EditFieldInput.prefill(value)
            assertEquals(
                "「$text」回填后应还原 $value",
                FieldInput.Value(value),
                EditFieldInput.parse(text, FieldCatalog.spec(key)),
            )
        }
    }

    @Test
    fun `分数预填不落成小数`() {
        assertEquals("2/3", EditFieldInput.prefill(TagValue.RationalValue(Rational(2, 3))))
        assertEquals(
            "1/3,2/3",
            EditFieldInput.prefill(TagValue.RationalList(listOf(Rational(1, 3), Rational(2, 3)))),
        )
    }

    @Test
    fun `清除与不合法分得清`() {
        assertNull(FieldInput.Value(TagValue.Text("a")).errorOrNull())
        assertNull(FieldInput.Clear.errorOrNull())
        assertEquals("原因", FieldInput.Invalid("原因").errorOrNull())
    }

    @Test
    fun `小数按小数位折成分数且不约分`() {
        assertEquals(Rational(178, 100), EditFieldInput.fromDecimal(1.78))
        assertEquals(Rational(1, 1), EditFieldInput.fromDecimal(1.0))
        // 1.5 → 15/10：分母跟着小数位走，不约分
        assertEquals(Rational(15, 10), EditFieldInput.fromDecimal(1.5))
        // 除不尽时按 maxScale 截断，2/3 在四位小数下是 6667/10000
        assertEquals(Rational(6667, 10000), EditFieldInput.fromDecimal(2.0 / 3.0, maxScale = 4))
    }
}
