package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设分栏多选的选择语义（T3.x）：设备 / 位置 / 时间 / 混合四栏并列。
 *
 * 钉住两条容易走偏的规则：
 * 1. **每栏至多一个**——同一栏点第二个是「换人」，不是「叠加」；
 * 2. **套用顺序固定**——不论用户先点哪一栏，套用时都按 设备 → 位置 → 时间 → 混合，
 *    否则同一组选择两次套用结果可能不同（「只填缺失」时后来者看不到前面刚落下的值）。
 */
class PresetSelectionTest {

    private val make = TagKey.of("EXIF:Make")
    private val lat = TagKey.of("GPS:GPSLatitude")
    private val date = TagKey.of("EXIF:DateTimeOriginal")

    private fun preset(id: String, kind: PresetKind, name: String, key: TagKey) =
        Preset(
            id = id,
            kind = kind,
            name = name,
            fields = mapOf(key to FieldRule.Fixed(PresetValue.Text(name))),
        )

    private val deviceA = preset("device.aaa", PresetKind.DEVICE, "机型 A", make)
    private val deviceB = preset("device.bbb", PresetKind.DEVICE, "机型 B", make)
    private val location = preset("location.ccc", PresetKind.LOCATION, "位置 C", lat)
    private val time = preset("time.ddd", PresetKind.TIME, "时间 D", date)
    private val all = listOf(deviceA, deviceB, location, time)

    @Test
    fun `点一次选中，再点一次取消该栏，别的栏不受影响`() {
        var selection = emptyMap<PresetKind, String>()

        selection = PresetSelection.toggle(selection, deviceA)
        selection = PresetSelection.toggle(selection, location)
        assertEquals(listOf(deviceA.id, location.id), PresetSelection.orderedIds(selection, all))

        selection = PresetSelection.toggle(selection, deviceA)
        assertEquals(listOf(location.id), PresetSelection.orderedIds(selection, all))
    }

    @Test
    fun `同一栏点第二个是换人，不会一栏俩`() {
        var selection = PresetSelection.toggle(emptyMap(), deviceA)

        selection = PresetSelection.toggle(selection, deviceB)

        assertEquals(1, selection.size)
        assertEquals(listOf(deviceB.id), PresetSelection.orderedIds(selection, all))
    }

    @Test
    fun `跨栏同时选，套用顺序固定为设备 位置 时间 混合`() {
        // 故意倒着点：时间 → 位置 → 设备
        var selection = emptyMap<PresetKind, String>()
        selection = PresetSelection.toggle(selection, time)
        selection = PresetSelection.toggle(selection, location)
        selection = PresetSelection.toggle(selection, deviceA)

        assertEquals(
            listOf(deviceA.id, location.id, time.id),
            PresetSelection.orderedPresets(selection, all).map { it.id },
        )
        assertEquals("机型 A + 位置 C + 时间 D", PresetSelection.label(selection, all))
    }

    @Test
    fun `空选给提示语，不是空串`() {
        assertEquals(PresetSelection.EMPTY_HINT, PresetSelection.label(emptyMap(), all))
        assertEquals(PresetSelection.EMPTY_HINT, PresetSelection.labelOf(emptyList(), all))
    }

    @Test
    fun `分栏四栏都在，空栏也留着`() {
        val columns = PresetSelection.byKind(listOf(location, deviceA))

        assertEquals(listOf("device", "location", "time", "mixed"), columns.map { it.first.id })
        assertEquals(listOf(deviceA.id), columns.first().second.map { it.id })
        assertTrue(columns.first { it.first == PresetKind.TIME }.second.isEmpty())
    }

    @Test
    fun `从 id 列表还原选择，同类出现多个时后者胜`() {
        val selection = PresetSelection.of(listOf(deviceA.id, deviceB.id, location.id), all)

        assertEquals(mapOf(PresetKind.DEVICE to deviceB.id, PresetKind.LOCATION to location.id), selection)
    }

    @Test
    fun `查不到的 id 自动跳过（自建预设被删掉之后）`() {
        val selection = PresetSelection.of(listOf("user.删掉了", location.id), all)

        assertEquals(listOf(location.id), PresetSelection.orderedIds(selection, all))
        assertEquals("位置 C", PresetSelection.labelOf(listOf("user.删掉了", location.id), all))
    }
}
