package com.pict.metatool.ui.edit

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.preset.MetadataRandomizer
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.domain.preset.PresetParseResult
import com.pict.metatool.domain.preset.PresetParser
import com.pict.metatool.domain.preset.PresetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页的预设 / 随机填充 / 添加字段状态（docs/07 T3.7 / T3.9、docs/06 §3.3）。
 *
 * 这里锁的是三条容易走偏的规则：
 * 1. 「添加字段」列的是**可写且本文件没有**的目录字段，且能按名/键过滤；
 * 2. 填充只把**真正变化**的键并入草稿（N 项不能虚高），跳过与保留都要有交代；
 * 3. 换取值预设时勾选集合跟着换，不留上一个预设的键。
 */
class EditPresetStateTest {

    private val source = SourceInfo("IMG_0001.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG)

    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")
    private val artist = TagKey.of("EXIF:Artist")
    private val width = TagKey.of("EXIF:ImageWidth")

    private val device = preset(
        """
        {
          "schemaVersion": 1, "id": "device-test", "kind": "device", "name": "测试相机",
          "fields": {
            "EXIF:Make": { "mode": "fixed", "value": "Apple" },
            "EXIF:Model": { "mode": "pool", "pool": [ { "value": "iPhone 16 Pro" } ] },
            "EXIF:Artist": { "mode": "fixed", "value": "不该进浮动列表" }
          },
          "constraints": [ { "type": "requires", "fields": ["EXIF:Make", "EXIF:Model"] } ]
        }
        """.trimIndent(),
    )

    private val location = preset(
        """
        {
          "schemaVersion": 1, "id": "location-test", "kind": "location", "name": "测试位置",
          "fields": {
            "GPS:GPSLatitude": { "mode": "gps", "lat": 31.2304, "lon": 121.4737, "radiusMeters": 1000 },
            "GPS:GPSLongitude": { "mode": "gps", "lat": 31.2304, "lon": 121.4737, "radiusMeters": 1000 }
          }
        }
        """.trimIndent(),
    )

    private fun preset(json: String): Preset =
        when (val parsed = PresetParser.parse(json, PresetOrigin.BUILTIN, "inline.json")) {
            is PresetParseResult.Success -> parsed.preset
            is PresetParseResult.Invalid -> error("内联预设解析失败：${parsed.issues.joinToString()}")
        }

    private fun loaded(vararg entries: Pair<TagKey, TagValue>, presets: List<Preset> = emptyList()): EditUiState =
        EditUiState()
            .withLoading("content://media/1")
            .withLoaded(source, MetadataSet(source, entries.toMap()))
            .withPresets(presets)

    // ---------- 添加字段 ----------

    @Test
    fun `添加字段只列可写且本文件没有的目录字段`() {
        val state = loaded(make to TagValue.Text("Apple"))

        val keys = state.addableFields.map { it.key }
        assertFalse("已有字段不该再出现", make in keys)
        assertFalse("只读字段不该出现", width in keys)
        assertTrue("没登记过的可写字段应在列表里", artist in keys)
        assertTrue("列表该有内容", keys.isNotEmpty())
    }

    @Test
    fun `添加字段支持按中文名与键过滤`() {
        val byLabel = loaded().withAddFieldQuery("作者").addableFields.map { it.key }
        assertTrue("按中文名过滤应命中作者字段", artist in byLabel)
        assertTrue("中文名可能命中多个同义字段", byLabel.size >= 1)

        assertEquals(
            "按键过滤只应命中那一个",
            listOf(artist),
            loaded().withAddFieldQuery("EXIF:Artist").addableFields.map { it.key },
        )
        assertTrue("过滤词无关时列表为空", loaded().withAddFieldQuery("不存在的字段名").addableFields.isEmpty())
    }

    @Test
    fun `关掉添加字段弹层会清空过滤词`() {
        val state = loaded().withAddFieldQuery("作者").closeAddField()
        assertFalse(state.showAddField)
        assertEquals("", state.addFieldQuery)
        assertTrue(state.addableFields.size > 1)
    }

    // ---------- 随机填充 ----------

    @Test
    fun `打开随机填充默认勾上浮动字段`() {
        val state = loaded(presets = listOf(device)).openRandomFill(device)

        assertTrue(state.showRandomFill)
        assertEquals(device.id, state.randomFillPresetId)
        assertEquals("只勾会浮动的字段（Make 是固定值，不参与）", setOf(model), state.randomFillKeys)
        assertTrue("默认种子要可复现", state.randomFillSeed != 0L)
        assertTrue(state.randomFillReady)
    }

    @Test
    fun `浮动字段列表里没有固定值字段之外的意外`() {
        val state = loaded(presets = listOf(device)).openRandomFill(device)
        val candidates = state.randomFillCandidates.map { it.key }

        assertTrue(candidates.contains(model))
        assertFalse("固定值字段不该出现在勾选列表", candidates.contains(make))
        assertTrue(candidates.isNotEmpty())
    }

    @Test
    fun `切换勾选与换预设都会重设字段集合`() {
        val opened = loaded(presets = listOf(device, location)).openRandomFill(device)

        val toggled = opened.toggleRandomFillKey(model)
        assertFalse(model in toggled.randomFillKeys)
        assertTrue("取消唯一勾选后就该是空集", toggled.randomFillKeys.isEmpty())
        assertFalse("没勾任何字段时不允许填充", toggled.randomFillReady)

        val switched = toggled.withRandomFillPreset(location)
        assertEquals(location.id, switched.randomFillPresetId)
        assertEquals(location.randomKeys, switched.randomFillKeys)
        assertFalse("上一个预设的键不该留下", model in switched.randomFillKeys)
    }

    @Test
    fun `换一批只换种子`() {
        val state = loaded(presets = listOf(device)).openRandomFill(device)
        val rerolled = state.withRandomFillSeed(777L)
        assertEquals(777L, rerolled.randomFillSeed)
        assertEquals(state.randomFillKeys, rerolled.randomFillKeys)
    }

    // ---------- 填充结果并入草稿 ----------

    @Test
    fun `填充只把变化的键并入草稿并汇报保留项`() {
        val state = loaded(make to TagValue.Text("Sony"), presets = listOf(device))
        val fill = PresetResolver.fill(device, state.metadata!!, onlyMissing = true, seed = 1L).getOrNull()!!

        val filled = state.withPresetFill(device.name, fill)

        assertTrue("源里已有 Make，只填空缺时保留", fill.keptKeys.contains(make))
        assertFalse("保留的键不该进草稿（草稿只装变化）", filled.draft.contains(make))
        assertEquals(
            "保留的字段仍显示源文件里的值",
            "Sony",
            filled.sections.flatMap { it.rows }.single { it.key == make }.display,
        )
        // 进草稿的是两项：Model（池抽样）与 Artist（fixed 照写）；Make 被保留所以不在其中
        assertEquals(setOf(model, artist), fill.changedKeys)
        assertEquals(2, filled.dirtyCount)
        assertTrue(filled.message!!.text.contains("2 项进草稿"))
        assertTrue(filled.message!!.text.contains("保留"))
        assertFalse(filled.message!!.isError)
        assertFalse("填充后弹层该关掉", filled.showPresets)
    }

    @Test
    fun `一次都填不到时给出错误提示而不是静默`() {
        val state = loaded(presets = listOf(device))
        val empty = PresetResolver.Fill(
            target = state.metadata!!,
            changedKeys = emptySet(),
            keptKeys = emptySet(),
            skipped = listOf(MetadataRandomizer.Skip(artist, "字段目录标记为只读")),
        )

        val filled = state.withPresetFill(device.name, empty)

        assertTrue(filled.message!!.isError)
        assertTrue(filled.message!!.text.contains("没有可填的字段"))
        assertTrue(filled.message!!.text.contains("跳过 1 项"))
        assertTrue(filled.draft.isEmpty)
    }

    @Test
    fun `填充后再折叠能算出与源相比的变化`() {
        val state = loaded(presets = listOf(device))
        val fill = PresetResolver.fill(device, state.metadata!!, seed = 3L).getOrNull()!!
        val filled = state.withPresetFill(device.name, fill)

        assertTrue(filled.dirtyCount > 0)
        assertTrue(filled.planned!!.isSuccess)
        assertTrue("diff 预览与草稿同源", filled.diffRows.isNotEmpty())
    }

    // ---------- 列表与分组 ----------

    @Test
    fun `预设按类别分组且保持声明顺序`() {
        val state = loaded(presets = listOf(location, device)).withPresets(listOf(location, device))

        val kinds = state.presetsByKind.map { it.first.id }
        assertEquals(listOf("device", "location"), kinds)
        assertEquals(device.id, state.presetsByKind.first().second.single().id)
    }

    @Test
    fun `没有预设时随机填充无法就绪`() {
        val state = loaded().openRandomFill(null)
        assertNull(state.randomFillPreset)
        assertFalse(state.randomFillReady)
        assertTrue(state.randomFillCandidates.isEmpty())
    }

    @Test
    fun `目录里只读字段永远不可添加`() {
        val readOnly = FieldCatalog.all.filter { !it.canEdit }
        assertTrue("目录里应有只读字段，用于本用例", readOnly.isNotEmpty())
        val addable = loaded().addableFields.map { it.key }.toSet()
        assertTrue(readOnly.none { it.key in addable })
    }
}
