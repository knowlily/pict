package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.ui.edit.EditFieldInput
import com.pict.metatool.ui.edit.FieldInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T3.1、docs/03 §4.2：预设字面值按字段类型落地。
 *
 * 关键断言是「换算与 UI 输入解析同口径」：用户手输 `1.78` 和预设给 `1.78` 必须落成同一个分数，
 * 否则同一个文件在两条路径下会得到不同的像素级字节。
 */
class PresetValuesTest {

    private fun spec(full: String) = FieldCatalog.spec(PresetTestSupport.key(full))!!

    @Test
    fun `同一个数值按字段类型落到不同形态`() {
        val text = PresetValues.number(1.78, "1.78", spec("EXIF:Make"))
        assertEquals(TagValue.Text("1.78"), text)

        val aperture = PresetValues.number(1.78, "1.78", spec("EXIF:FNumber"))
        assertEquals(TagValue.RationalValue(Rational(178, 100)), aperture)

        val iso = PresetValues.number(50.0, "50", spec("EXIF:ISOSpeedRatings"))
        assertEquals(TagValue.IntList(listOf(50L)), iso)
    }

    @Test
    fun `分数换算与 UI 输入解析同口径`() {
        val fNumber = spec("EXIF:FNumber")
        listOf("1.78", "2.8", "1.6", "4").forEach { text ->
            val fromText = (EditFieldInput.parse(text, fNumber) as FieldInput.Value).value
            val fromPreset = PresetValues.number(text.toDouble(), text, fNumber)!!
            assertEquals("「$text」两条路径应落成同一个值", fromText, fromPreset)
        }
    }

    @Test
    fun `文本形态的分数按原样保留分子分母`() {
        val exposure = spec("EXIF:ExposureTime")
        assertEquals(
            TagValue.RationalValue(Rational(1, 60)),
            PresetValues.text("1/60", exposure),
        )
    }

    @Test
    fun `二进制支持点分十进制`() {
        val version = PresetValues.text("2.3.0.0", spec("GPS:GPSVersionID"))
        assertEquals(TagValue.Binary("02030000", 4), version)

        val hex = PresetValues.text("0x02 03 00 00", spec("GPS:GPSVersionID"))
        assertEquals(TagValue.Binary("02030000", 4), hex)
    }

    @Test
    fun `多语言文本用默认语言标签`() {
        val title = PresetValues.text("外滩", spec("XMP:dc:title"))
        assertEquals(TagValue.LangAlt(mapOf(PresetValues.DEFAULT_LANGUAGE to "外滩")), title)
    }

    @Test
    fun `时间点按字段类型落地`() {
        val moment = PresetValues.moment("2024-05-01T09:12:33")!!
        assertEquals(TagValue.Timestamp(moment), PresetValues.moment(moment, spec("EXIF:DateTimeOriginal")))
        assertEquals(TagValue.DateValue(moment.toLocalDate()), PresetValues.moment(moment, spec("IPTC:2:55")))
        assertEquals(TagValue.TimeValue(moment.toLocalTime()), PresetValues.moment(moment, spec("IPTC:2:60")))
    }

    @Test
    fun `时间文本支持 EXIF 冒号写法`() {
        val moment = PresetValues.moment("2024:05:01 09:12:33")
        assertEquals("2024-05-01T09:12:33", moment.toString())
    }

    @Test
    fun `无法解释的值返回 null 而不是编一个`() {
        assertNull(PresetValues.text("不是时间", spec("EXIF:DateTimeOriginal")))
        assertNull(PresetValues.text("xyz", spec("EXIF:FNumber")))
        // 点分十进制限制在每个字节 0..255：越界即拒绝，而不是截断成另一个版本号
        assertNull(PresetValues.text("2.3.0.256", spec("GPS:GPSVersionID")))
        assertNull(PresetValues.toTagValue(PresetValue.Null, spec("EXIF:Make")))
    }

    @Test
    fun `目录未登记时给最中性的形态`() {
        val value = PresetValues.number(1.5, "1.5", spec = null)
        assertEquals(TagValue.DecimalValue(1.5), value)
        val text = PresetValues.text("abc", spec = null)
        assertEquals(TagValue.Text("abc"), text)
    }

    @Test
    fun `整数形态的字面量不写成小数`() {
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "mixed-int", "kind": "mixed", "name": "整数",
              "fields": { "EXIF:Software": { "mode": "fixed", "value": 50 } }
            }
            """.trimIndent(),
        )
        val rule = preset.rule(PresetTestSupport.key("EXIF:Software")) as FieldRule.Fixed
        assertEquals(PresetValue.Number(50.0, "50"), rule.value)
        assertTrue((rule.value as PresetValue.Number).literal == "50")
    }

    @Test
    fun `空源集合可安全构造`() {
        val source: MetadataSet = PresetTestSupport.emptySource()
        assertEquals(ImageFormatHint.JPEG, source.source.format)
        assertTrue(source.isEmpty)
    }
}
