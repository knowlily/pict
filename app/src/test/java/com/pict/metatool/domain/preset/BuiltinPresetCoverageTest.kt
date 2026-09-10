package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/03 §7「内置预设清单」的覆盖度锁定（T3.2）。
 *
 * 「解析得过」由 [PresetParserTest] 保证；这里管的是另一件事：
 * 清单上写了的 20 个预设是不是真的都在、机型够不够杂、每个机型预设有没有自觉写 Make/Model。
 * 少一个文件、改错 id、把机型预设写成只有 GPS，都会在这里红掉。
 */
class BuiltinPresetCoverageTest {

    /** docs/03 §7 的清单，逐个钉住。 */
    private val expectedIds = listOf(
        // 设备：手机 6 + 相机 4 + 运动相机 1 + 无人机 1
        "device.iphone-16-pro",
        "device.iphone-13",
        "device.pixel-9-pro",
        "device.galaxy-s24-ultra",
        "device.huawei-mate-60",
        "device.xiaomi-14",
        "device.sony-a7iv",
        "device.canon-r6m2",
        "device.nikon-z6ii",
        "device.fujifilm-x-t5",
        "device.gopro-hero12",
        "device.dji-mini4pro",
        // 位置
        "location.shanghai",
        "location.beijing",
        "location.shenzhen",
        "location.tokyo",
        // 时间
        "time.2024",
        "time.2025",
        "time.2026-h1",
        // 混合
        "mixed.ecommerce",
    )

    @Test
    fun `清单上的内置预设一个都不少`() {
        val ids = PresetTestSupport.builtinPresets().map { it.id }.toSet()
        val missing = expectedIds.filterNot { it in ids }
        assertTrue("presets/ 里缺这些内置预设：$missing", missing.isEmpty())
    }

    @Test
    fun `id 全局唯一且前缀与类别一致`() {
        val presets = PresetTestSupport.builtinPresets()
        val ids = presets.map { it.id }
        assertEquals("id 有重复：${ids.groupingBy { it }.eachCount().filter { it.value > 1 }}", ids.size, ids.toSet().size)
        presets.forEach { preset ->
            assertEquals("${preset.id} 的前缀与 kind 不一致", preset.kind.id, preset.id.substringBefore('.'))
        }
    }

    @Test
    fun `四类预设都在，机型最杂`() {
        val byKind = PresetTestSupport.builtinPresets().groupingBy { it.kind.id }.eachCount()
        assertEquals(12, byKind["device"])
        assertEquals(4, byKind["location"])
        assertEquals(3, byKind["time"])
        assertEquals(1, byKind["mixed"])
    }

    @Test
    fun `每个机型预设都写了 Make 与 Model`() {
        val make = TagKey.of("EXIF:Make")
        val model = TagKey.of("EXIF:Model")
        PresetTestSupport.builtinPresets()
            .filter { it.kind == PresetKind.DEVICE }
            .forEach { preset ->
                assertNotNull("${preset.id} 少了 EXIF:Make", preset.rule(make))
                assertNotNull("${preset.id} 少了 EXIF:Model", preset.rule(model))
            }
    }

    @Test
    fun `每个位置预设都带经纬度与坐标轴前缀`() {
        val lat = TagKey.of("GPS:GPSLatitude")
        val lon = TagKey.of("GPS:GPSLongitude")
        val latRef = TagKey.of("GPS:GPSLatitudeRef")
        val lonRef = TagKey.of("GPS:GPSLongitudeRef")
        PresetTestSupport.builtinPresets()
            .filter { it.kind == PresetKind.LOCATION }
            .forEach { preset ->
                assertNotNull("${preset.id} 少了 GPS:GPSLatitude", preset.rule(lat))
                assertNotNull("${preset.id} 少了 GPS:GPSLongitude", preset.rule(lon))
                assertNotNull("${preset.id} 少了 GPS:GPSLatitudeRef（半球的符号不能靠猜）", preset.rule(latRef))
                assertNotNull("${preset.id} 少了 GPS:GPSLongitudeRef", preset.rule(lonRef))
            }
    }
}
