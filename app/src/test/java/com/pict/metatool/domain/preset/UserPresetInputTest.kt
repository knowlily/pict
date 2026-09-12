package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.TagKey
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自己加一个预设」表单的值解析（T3.x）。
 *
 * 用户只填一段文本，要落成 preset-v1 的四种规则之一：单个值 → 固定值、逗号分开 → 取值池、
 * 两端时间/数字用 `~` 相连 → 区间。这里逐条钉住，顺便钉住「看不懂就别存」
 * （[UserPresetInput.validate] 会拦下），免得存进去一份读不回来的预设。
 */
class UserPresetInputTest {

    private val make = TagKey.of("EXIF:Make")
    private val lat = TagKey.of("GPS:GPSLatitude")
    private val date = TagKey.of("EXIF:DateTimeOriginal")
    private val iso = TagKey.of("EXIF:ISOSpeedRatings")

    @Test
    fun `单个值落成固定值，数字保留字面量`() {
        assertEquals(FieldRule.Fixed(PresetValue.Text("OnePlus")), UserPresetInput.ruleOf("OnePlus"))
        assertEquals(FieldRule.Fixed(PresetValue.Number(4032.0, "4032")), UserPresetInput.ruleOf("4032"))
        assertEquals(FieldRule.Fixed(PresetValue.Number(1.78, "1.78")), UserPresetInput.ruleOf("1.78"))
    }

    @Test
    fun `逗号顿号分号都能分开，去空白去重保序`() {
        val rule = UserPresetInput.ruleOf("iPhone 16 Pro, iPhone 16 Pro Max、iPhone 16 Pro；Pixel 9 Pro") as FieldRule.Pool

        assertEquals(
            listOf("iPhone 16 Pro", "iPhone 16 Pro Max", "Pixel 9 Pro"),
            rule.candidates.map { (it.value as PresetValue.Text).text },
        )
        assertTrue("默认权重一样", rule.candidates.all { it.weight == 1.0 })
    }

    @Test
    fun `两端数字用波浪线连成区间，精度看小数位多的那头`() {
        assertEquals(FieldRule.Range(31.23, 31.25, 2), UserPresetInput.ruleOf("31.23~31.25"))
        assertEquals(FieldRule.Range(100.0, 6400.0, 0), UserPresetInput.ruleOf("100 ~ 6400"))
        // 反着写也认，自动摆正
        assertEquals(FieldRule.Range(31.23, 31.25, 2), UserPresetInput.ruleOf("31.25~31.23"))
    }

    @Test
    fun `两端是时刻就落成时间区间，只写日期按当天零点算`() {
        assertEquals(
            FieldRule.DateTimeRule(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 12, 31, 23, 59, 59),
            ),
            UserPresetInput.ruleOf("2026-01-01T00:00:00~2026-12-31T23:59:59"),
        )
        assertEquals(
            FieldRule.DateTimeRule(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 0, 0)),
            UserPresetInput.ruleOf("2026-01-01~2026-06-30"),
        )
    }

    @Test
    fun `波浪线两端既不是时间也不是数字时整段当一个固定值，不当区间`() {
        // 「甲~乙」拆不出合法的区间两端，就当普通文本原样写下去——别把它偷偷变成两个候选值
        assertEquals(FieldRule.Fixed(PresetValue.Text("甲~乙")), UserPresetInput.ruleOf("甲~乙"))
    }

    @Test
    fun `空值给 null，validate 会拦下保存`() {
        assertNull(UserPresetInput.ruleOf("   "))

        val input = UserPresetInput(name = "没填值", rows = listOf(UserFieldInput(make, "  ")))
        assertEquals("至少填一个字段（字段 + 值）", input.validate())
    }

    @Test
    fun `没名字 没字段 都存不了，填了才能存`() {
        assertTrue(UserPresetInput(rows = listOf(UserFieldInput(make, "OnePlus"))).validate()!!.contains("名字"))

        val unnamedField = UserPresetInput(name = "只有值", rows = listOf(UserFieldInput(null, "OnePlus")))
        assertTrue(unnamedField.validate()!!.contains("至少填一个字段"))

        val ok = UserPresetInput(
            name = "OnePlus 12 备用",
            kind = PresetKind.DEVICE,
            rows = listOf(UserFieldInput(make, "OnePlus"), UserFieldInput(iso, "100~6400")),
        )
        assertNull(ok.validate())
    }

    @Test
    fun `只填一半的行静默丢掉，不算错也不算字段`() {
        val input = UserPresetInput(
            name = "半行",
            rows = listOf(
                UserFieldInput(make, "OnePlus"),
                UserFieldInput(null, "没选字段"),
                UserFieldInput(lat, ""),
            ),
        )

        assertNull(input.validate())
        assertEquals(1, input.filledRows().size)
        assertEquals(setOf(make), input.toPreset("user.half").fields.keys)
    }

    @Test
    fun `转成预设时带上类别与说明，来源标成自建`() {
        val preset = UserPresetInput(
            name = "  我的机型  ",
            kind = PresetKind.DEVICE,
            description = "  随手加的  ",
            rows = listOf(UserFieldInput(make, "OnePlus")),
        ).toPreset("user.mine")

        assertEquals("user.mine", preset.id)
        assertEquals("我的机型", preset.name)
        assertEquals("随手加的", preset.description)
        assertEquals(PresetOrigin.USER, preset.origin)
        assertEquals(listOf(PresetKind.DEVICE.label), preset.tags)
    }

    @Test
    fun `编辑已有预设：能编的模式回到表单，编不了的进 preserved 原样保留`() {
        val gps = FieldRule.Gps(31.2304, 121.4737, 500.0)
        val original = Preset(
            id = "user.mix",
            kind = PresetKind.LOCATION,
            name = "混着来",
            fields = mapOf(
                make to FieldRule.Fixed(PresetValue.Text("OnePlus")),
                iso to FieldRule.Range(100.0, 6400.0, 0),
                date to FieldRule.DateTimeRule(
                    LocalDateTime.of(2026, 1, 1, 0, 0),
                    LocalDateTime.of(2026, 12, 31, 0, 0),
                ),
                lat to gps,
            ),
            origin = PresetOrigin.USER,
        )

        val form = UserPresetInput.from(original)

        assertEquals("user.mix", form.id)
        assertEquals(3, form.rows.size)
        assertEquals(mapOf(lat to gps), form.preserved)
        assertTrue("坐标行不该出现在表单里", form.rows.none { it.key == lat })
        // 原样存回去：坐标规则还在，别的也一致
        assertEquals(original.fields, form.toPreset("user.mix").fields)
    }
}
