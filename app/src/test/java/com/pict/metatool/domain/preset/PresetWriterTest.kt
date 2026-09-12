package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.TagKey
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设 → JSON 的写侧（T3.x 自建预设用）。
 *
 * 最重要的一条是**回环**：写出去的那份必须能被 [PresetParser] 原样读回来。
 * 所以这里先拿仓库里全部内置预设跑一遍「读 → 写 → 再读」，再单独盯几处手写值：
 * 中文与引号的转义、数字字面量（文本字段里的 `1.78` 不能被写成浮点数 1.78）、
 * 以及那些内置预设里没出现过的冷门模式（抖动、时间权重）。
 */
class PresetWriterTest {

    @Test
    fun `内置预设读进来写出去再读回来，字段与约束一个不少`() {
        val presets = PresetTestSupport.builtinPresets()
        assertTrue("仓库里的预设应该不止几个，实际 ${presets.size} 个", presets.size >= 15)

        presets.forEach { original ->
            val again = PresetTestSupport.parse(
                PresetWriter.write(original, PresetWriter.SOURCE_BUILTIN),
                "round-trip.json",
            )

            assertEquals("${original.id} 的字段回环不一致", original.fields, again.fields)
            assertEquals("${original.id} 的约束回环不一致", original.constraints, again.constraints)
            assertEquals(original.name, again.name)
            assertEquals(original.kind, again.kind)
            assertEquals(original.description, again.description)
            assertEquals(original.tags, again.tags)
        }
    }

    @Test
    fun `七种模式都写得出来，读回来还是同一份规则`() {
        val make = TagKey.of("EXIF:Make")
        val model = TagKey.of("EXIF:Model")
        val fnumber = TagKey.of("EXIF:FNumber")
        val iso = TagKey.of("EXIF:ISOSpeedRatings")
        val lat = TagKey.of("GPS:GPSLatitude")
        val date = TagKey.of("EXIF:DateTimeOriginal")
        val digitized = TagKey.of("EXIF:DateTimeDigitized")
        val artist = TagKey.of("EXIF:Artist")
        val lens = TagKey.of("EXIF:LensModel")

        val preset = Preset(
            id = "user.round-trip",
            kind = PresetKind.MIXED,
            name = "回环·测试",
            description = "带 \"引号\"、反斜杠 \\ 与换行的说明\n第二行",
            tags = listOf("测试", "round-trip"),
            fields = linkedMapOf(
                make to FieldRule.Fixed(PresetValue.Text("OnePlus")),
                fnumber to FieldRule.Fixed(PresetValue.Number(1.78, "1.78")),
                model to FieldRule.Pool(
                    listOf(
                        PoolEntry(PresetValue.Text("PJD110")),
                        PoolEntry(PresetValue.Text("CPH2581"), 2.0),
                    ),
                ),
                iso to FieldRule.Range(100.0, 6400.0, 0),
                date to FieldRule.DateTimeRule(
                    start = LocalDateTime.of(2026, 5, 1, 0, 0),
                    end = LocalDateTime.of(2026, 5, 31, 23, 59, 59),
                    hourWeights = List(24) { hour -> if (hour in 9..17) 1.0 else 0.2 },
                ),
                lat to FieldRule.Gps(31.2304, 121.4737, 500.0),
                artist to FieldRule.Jitter(radiusMeters = 800.0, percent = 0.15),
                lens to FieldRule.FromSource(lens),
            ),
            constraints = listOf(
                PresetConstraint.DatetimeOrder(listOf(date, digitized)),
                PresetConstraint.Mutex(listOf(make, model)),
            ),
            origin = PresetOrigin.USER,
        )

        val written = PresetWriter.write(preset, PresetWriter.SOURCE_USER)
        val parsed = PresetTestSupport.parse(written, "user-round-trip.json")

        assertEquals(preset.fields.keys, parsed.fields.keys)
        assertEquals(preset.fields, parsed.fields)
        assertEquals(preset.constraints, parsed.constraints)
        assertEquals("回环·测试", parsed.name)
        assertEquals(preset.description, parsed.description)
        assertEquals(listOf("测试", "round-trip"), parsed.tags)
    }

    @Test
    fun `文本字段里的数字不会被写成浮点数`() {
        val key = TagKey.of("EXIF:LensModel")

        val written = PresetWriter.write(
            Preset(
                id = "user.literal",
                kind = PresetKind.DEVICE,
                name = "字面量",
                fields = mapOf(key to FieldRule.Fixed(PresetValue.Number(1.78, "1.78"))),
                origin = PresetOrigin.USER,
            ),
        )

        assertTrue("应该是裸数字 1.78，不是字符串也不是 1.7800000", written.contains("\"$key\"") || written.contains(key.full))
        assertTrue(written.contains("1.78"))
        assertEquals(
            FieldRule.Fixed(PresetValue.Number(1.78, "1.78")),
            PresetTestSupport.parse(written).rule(key),
        )
    }

    @Test
    fun `写出来的 JSON 带 schemaVersion 与 source，用户预设标 user`() {
        val written = PresetWriter.write(
            Preset(
                id = "user.tags",
                kind = PresetKind.TIME,
                name = "时间",
                fields = mapOf(
                    TagKey.of("EXIF:DateTimeOriginal") to FieldRule.Fixed(
                        PresetValue.Text("2026-01-01T00:00:00"),
                    ),
                ),
                origin = PresetOrigin.USER,
            ),
        )

        assertTrue(written.contains("\"schemaVersion\""))
        assertTrue(written.contains(Preset.SCHEMA_VERSION.toString()))
        assertTrue(written.contains("\"source\": \"user\""))
        assertTrue(written.contains("\"kind\": \"time\""))
    }
}
