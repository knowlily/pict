package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.plan.EditPlanExecutor
import com.pict.metatool.domain.plan.GpsEditor
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T3.4/T3.6、docs/03 §5/§6：抽样正确性、种子可复现、约束收敛、跳过要带原因。
 *
 * 断言口径说明：随机结果不做「值等于某个常量」的断言——那种断言只能证明「这次跑出来的
 * 恰好是这个」，换个实现就红。这里断言的是**不变量**：区间、池成员、半径、递增顺序、
 * 同种子一致、跳过的原因。
 */
class MetadataRandomizerTest {

    private val make = PresetTestSupport.key("EXIF:Make")
    private val model = PresetTestSupport.key("EXIF:Model")
    private val fNumber = PresetTestSupport.key("EXIF:FNumber")
    private val iso = PresetTestSupport.key("EXIF:ISOSpeedRatings")
    private val software = PresetTestSupport.key("EXIF:Software")
    private val dateTimeOriginal = PresetTestSupport.key("EXIF:DateTimeOriginal")
    private val dateTimeDigitized = PresetTestSupport.key("EXIF:DateTimeDigitized")

    private val deviceJson = """
        {
          "schemaVersion": 1,
          "id": "device-test",
          "kind": "device",
          "name": "测试相机",
          "fields": {
            "EXIF:Make": { "mode": "fixed", "value": "Apple" },
            "EXIF:Model": { "mode": "pool", "pool": [
              { "value": "iPhone 16 Pro", "weight": 3 },
              { "value": "iPhone 16", "weight": 1 }
            ] },
            "EXIF:FNumber": { "mode": "range", "min": 1.6, "max": 2.8, "precision": 1 },
            "EXIF:ISOSpeedRatings": { "mode": "range", "min": 50, "max": 3200, "precision": 0 },
            "EXIF:Software": { "mode": "fromSource", "sourceKey": "EXIF:Make" },
            "EXIF:DateTimeOriginal": { "mode": "datetime", "start": "2024-01-01T09:00:00", "end": "2026-01-01T18:00:00" },
            "EXIF:DateTimeDigitized": { "mode": "datetime", "start": "2024-01-01T09:00:00", "end": "2026-01-01T18:00:00" }
          },
          "constraints": [
            { "type": "datetimeOrder", "fields": ["EXIF:DateTimeOriginal", "EXIF:DateTimeDigitized"] }
          ]
        }
    """.trimIndent()

    private val locationJson = """
        {
          "schemaVersion": 1,
          "id": "location-test",
          "kind": "location",
          "name": "测试位置",
          "fields": {
            "GPS:GPSLatitude": { "mode": "gps", "latitude": 31.2304, "longitude": 121.4737, "radiusMeters": 3000 },
            "GPS:GPSLongitude": { "mode": "gps", "latitude": 31.2304, "longitude": 121.4737, "radiusMeters": 3000 },
            "GPS:GPSLatitudeRef": { "mode": "fixed", "value": "N" },
            "GPS:GPSLongitudeRef": { "mode": "fixed", "value": "E" },
            "GPS:GPSAltitude": { "mode": "range", "min": 5, "max": 30, "precision": 1 }
          },
          "constraints": [
            { "type": "requires", "fields": ["GPS:GPSLatitudeRef", "GPS:GPSLatitude"] }
          ]
        }
    """.trimIndent()

    private val device = PresetTestSupport.parse(deviceJson)
    private val location = PresetTestSupport.parse(locationJson)

    private fun randomizer(seed: Long) = MetadataRandomizer(Random(seed))

    private fun numeric(value: TagValue?): Double? = when (value) {
        is TagValue.IntValue -> value.value.toDouble()
        is TagValue.DecimalValue -> value.value
        is TagValue.RationalValue -> value.value.asDouble
        is TagValue.IntList -> value.values.firstOrNull()?.toDouble()
        is TagValue.DecimalList -> value.values.firstOrNull()
        is TagValue.RationalList -> value.values.firstOrNull()?.asDouble
        else -> null
    }

    // ---------- 可复现 ----------

    @Test
    fun `同一种子给出逐字段一致的结果`() {
        val first = randomizer(42L).fill(device, PresetTestSupport.emptySource())
        val second = randomizer(42L).fill(device, PresetTestSupport.emptySource())
        assertEquals(first.values, second.values)
        assertEquals(first.gpsOperations, second.gpsOperations)
        assertEquals(first.skipped, second.skipped)
    }

    @Test
    fun `不同种子给出不同取值`() {
        val first = randomizer(1L).fill(device, PresetTestSupport.emptySource())
        val second = randomizer(2L).fill(device, PresetTestSupport.emptySource())
        assertNotEquals(first.values, second.values)
    }

    // ---------- 各模式的不变量 ----------

    @Test
    fun `fixed 字段不受种子影响`() {
        val values = listOf(1L, 7L, 99L).map { seed ->
            randomizer(seed).fill(device, PresetTestSupport.emptySource()).values
        }
        values.forEach { assertEquals(TagValue.Text("Apple"), it[make]) }
    }

    @Test
    fun `pool 只产出声明过的取值`() {
        val allowed = setOf("iPhone 16 Pro", "iPhone 16")
        repeat(20) { seed ->
            val value = randomizer(seed.toLong()).fill(device, PresetTestSupport.emptySource()).values[model]
            assertTrue("池外取值：$value", (value as TagValue.Text).value in allowed)
        }
    }

    @Test
    fun `range 取值落在区间内并按精度取整`() {
        repeat(30) { seed ->
            val values = randomizer(seed.toLong()).fill(device, PresetTestSupport.emptySource()).values
            val aperture = numeric(values[fNumber])!!
            assertTrue("光圈越界：$aperture", aperture in 1.6..2.8)
            assertEquals("小数位应为 1 位", 1, aperture.toString().substringAfter('.', "").length)

            val isoSpeed = numeric(values[iso])!!
            assertTrue("ISO 越界：$isoSpeed", isoSpeed in 50.0..3200.0)
            assertEquals("ISO 应为整数", isoSpeed, isoSpeed.toLong().toDouble(), 0.0)
        }
    }

    @Test
    fun `datetime 取值落在区间内`() {
        val start = PresetValues.moment("2024-01-01T09:00:00")!!
        val end = PresetValues.moment("2026-01-01T18:00:00")!!
        repeat(20) { seed ->
            val moment = (randomizer(seed.toLong()).fill(device, PresetTestSupport.emptySource())
                .values[dateTimeOriginal] as TagValue.Timestamp).value
            assertFalse("早于区间起点：$moment", moment.isBefore(start))
            assertFalse("晚于区间终点：$moment", !moment.isBefore(end))
        }
    }

    @Test
    fun `fromSource 沿用源里的同名值`() {
        val base = PresetTestSupport.emptySource().with(make, TagValue.Text("Sony"))
        val values = randomizer(5L).fill(device, base).values
        assertEquals(TagValue.Text("Sony"), values[software])
    }

    @Test
    fun `fromSource 在源里没有值时记跳过并给原因`() {
        val result = randomizer(5L).fill(device, PresetTestSupport.emptySource())
        val skip = result.skipped.single { it.key == software }
        assertTrue("原因应说明源里没有：$skip", skip.reason.contains("无法沿用"))
        assertEquals(null, result.values[software])
    }

    @Test
    fun `数值抖动幅度不超过声明比例`() {
        val jitterPreset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "device-jitter", "kind": "device", "name": "抖动",
              "fields": { "EXIF:FNumber": { "mode": "jitter", "percent": 0.5 } }
            }
            """.trimIndent(),
        )
        val base = PresetTestSupport.emptySource()
            .with(fNumber, TagValue.RationalValue(Rational(2, 1)))
        repeat(20) { seed ->
            val value = numeric(randomizer(seed.toLong()).fill(jitterPreset, base).values[fNumber])!!
            assertTrue("抖动越界：$value", value in 1.0..3.0)
        }
    }

    // ---------- 坐标 ----------

    @Test
    fun `GPS 采样落在圆面内且 Ref 与坐标一致`() {
        repeat(10) { seed ->
            val result = randomizer(seed.toLong()).fill(location, PresetTestSupport.emptySource())
            val operation = result.gpsOperations.single() as EditOperation.SetGps
            val distance = GpsEditor.sphericalDistanceMeters(31.2304, 121.4737, operation.latitude, operation.longitude)
            assertTrue("超出圆面半径：$distance m", distance <= 3000.0 + 1.0)

            // 纬度/经度 Ref 由 GpsEditor 一并写，且上海在北半球、东经
            val folded = fold(result.toOperations())
            assertEquals(TagValue.Text("N"), folded[GpsEditor.LATITUDE_REF])
            assertEquals(TagValue.Text("E"), folded[GpsEditor.LONGITUDE_REF])
            val altitude = operation.altitudeMeters!!
            assertTrue("海拔越界：$altitude", altitude in 5.0..30.0)
        }
    }

    @Test
    fun `GPS 抖动落在源坐标附近`() {
        val jitterPreset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "location-jitter", "kind": "location", "name": "抖动",
              "fields": {
                "GPS:GPSLatitude": { "mode": "jitter", "radiusMeters": 500 },
                "GPS:GPSLongitude": { "mode": "jitter", "radiusMeters": 500 }
              }
            }
            """.trimIndent(),
        )
        val base = GpsEditor.set(PresetTestSupport.emptySource(), 31.2304, 121.4737).getOrNull()!!
        val operation = randomizer(3L).fill(jitterPreset, base).gpsOperations.single() as EditOperation.SetGps
        val distance = GpsEditor.sphericalDistanceMeters(31.2304, 121.4737, operation.latitude, operation.longitude)
        assertTrue("抖动超过 500 m：$distance", distance <= 500.0 + 1.0)
        assertEquals("抖动不该动海拔", null, operation.altitudeMeters)
    }

    @Test
    fun `源里没有坐标时抖动记跳过`() {
        val jitterPreset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "location-jitter2", "kind": "location", "name": "抖动",
              "fields": { "GPS:GPSLatitude": { "mode": "jitter" }, "GPS:GPSLongitude": { "mode": "jitter" } }
            }
            """.trimIndent(),
        )
        val result = randomizer(3L).fill(jitterPreset, PresetTestSupport.emptySource())
        assertTrue(result.gpsOperations.isEmpty())
        assertTrue(result.skipped.any { it.reason.contains("没有坐标") })
    }

    // ---------- 只填空缺 / 跳过 ----------

    @Test
    fun `只填空缺时保留已有值并记进 keptKeys`() {
        val base = PresetTestSupport.emptySource().with(make, TagValue.Text("Sony"))
        val result = randomizer(11L).fill(device, base, onlyMissing = true)
        assertTrue("Make 应被保留", make in result.keptKeys)
        assertFalse("Make 不该出现在待写值里", result.values.containsKey(make))
        assertTrue("Model 应被填上", result.values.containsKey(model))
    }

    @Test
    fun `隐私字段默认跳过，显式选择才写`() {
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "device-privacy", "kind": "device", "name": "隐私",
              "fields": {
                "EXIF:Make": { "mode": "fixed", "value": "Apple" },
                "EXIF:BodySerialNumber": { "mode": "fixed", "value": "SN-0001" }
              }
            }
            """.trimIndent(),
        )
        val serial = PresetTestSupport.key("EXIF:BodySerialNumber")
        val base = PresetTestSupport.emptySource()

        val kept = randomizer(1L).fill(preset, base)
        assertEquals(null, kept.values[serial])
        assertTrue(kept.skipped.any { it.key == serial && it.reason.contains("隐私") })

        val explicit = randomizer(1L).fill(preset, base, keys = setOf(serial))
        assertEquals(TagValue.Text("SN-0001"), explicit.values[serial])
    }

    @Test
    fun `只读字段被拒绝并给出原因`() {
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "device-readonly", "kind": "device", "name": "只读",
              "fields": { "EXIF:ImageWidth": { "mode": "fixed", "value": 4000 } }
            }
            """.trimIndent(),
        )
        val result = randomizer(1L).fill(preset, PresetTestSupport.emptySource())
        assertTrue(result.values.isEmpty())
        assertTrue(result.skipped.single().reason.contains("不允许写入"))
    }

    @Test
    fun `未登记字段被跳过并说明写入通道不认识`() {
        val unknown = PresetTestSupport.key("EXIF:NotRegisteredAtAll")
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "device-unknown", "kind": "device", "name": "未登记",
              "fields": { "EXIF:NotRegisteredAtAll": { "mode": "fixed", "value": "x" } }
            }
            """.trimIndent(),
        )
        val result = randomizer(1L).fill(preset, PresetTestSupport.emptySource())
        assertEquals(null, result.values[unknown])
        assertTrue(result.skipped.single { it.key == unknown }.reason.contains("未登记"))
    }

    @Test
    fun `请求不在预设里的字段会被点名`() {
        val missing = PresetTestSupport.key("EXIF:Artist")
        val result = randomizer(1L).fill(device, PresetTestSupport.emptySource(), keys = setOf(make, missing))
        assertTrue(result.skipped.any { it.key == missing && it.reason.contains("没有为这个字段定义规则") })
    }

    // ---------- 约束 ----------

    @Test
    fun `datetimeOrder 保证时间递增`() {
        repeat(30) { seed ->
            val values = randomizer(seed.toLong()).fill(device, PresetTestSupport.emptySource()).values
            val original = (values[dateTimeOriginal] as TagValue.Timestamp).value
            val digitized = (values[dateTimeDigitized] as TagValue.Timestamp).value
            assertFalse("先后颠倒：$original / $digitized", digitized.isBefore(original))
        }
    }

    @Test
    fun `requires 把成组字段补齐`() {
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "device-requires", "kind": "device", "name": "成组",
              "fields": {
                "EXIF:Make": { "mode": "fixed", "value": "Apple" },
                "EXIF:Model": { "mode": "pool", "pool": [ { "value": "iPhone 16 Pro" } ] }
              },
              "constraints": [ { "type": "requires", "fields": ["EXIF:Make", "EXIF:Model"] } ]
            }
            """.trimIndent(),
        )
        // 只要求填 Make，约束应把 Model 一起带上（成组出现优先于「只填勾选项」）
        val values = randomizer(1L).fill(preset, PresetTestSupport.emptySource(), keys = setOf(make)).values
        assertEquals(TagValue.Text("iPhone 16 Pro"), values[model])
    }

    @Test
    fun `mutex 只保留一个字段`() {
        val lensMake = PresetTestSupport.key("EXIF:LensMake")
        val lensModel = PresetTestSupport.key("EXIF:LensModel")
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "device-mutex", "kind": "device", "name": "互斥",
              "fields": {
                "EXIF:LensMake": { "mode": "fixed", "value": "Apple" },
                "EXIF:LensModel": { "mode": "fixed", "value": "iPhone 镜头" }
              },
              "constraints": [ { "type": "mutex", "fields": ["EXIF:LensMake", "EXIF:LensModel"] } ]
            }
            """.trimIndent(),
        )
        val values = randomizer(1L).fill(preset, PresetTestSupport.emptySource()).values
        assertNotNull(values[lensMake])
        assertEquals(null, values[lensModel])
    }

    @Test
    fun `clamp 不改变区间内取值也不替没有区间的字段编上限`() {
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "device-clamp", "kind": "device", "name": "收敛",
              "fields": {
                "EXIF:FNumber": { "mode": "range", "min": 1.6, "max": 2.8, "precision": 1 },
                "EXIF:Model": { "mode": "pool", "pool": [ { "value": "iPhone 16 Pro" } ] }
              },
              "constraints": [ { "type": "clamp", "fields": ["EXIF:FNumber", "EXIF:Model"] } ]
            }
            """.trimIndent(),
        )
        repeat(10) { seed ->
            val values = randomizer(seed.toLong()).fill(preset, PresetTestSupport.emptySource()).values
            assertTrue(numeric(values[fNumber])!! in 1.6..2.8)
            assertEquals(TagValue.Text("iPhone 16 Pro"), values[model])
        }
    }

    // ---------- 值类型落地 ----------

    @Test
    fun `同一个数值在不同类型字段上落到不同形态`() {
        val preset = PresetTestSupport.parse(
            """
            {
              "schemaVersion": 1, "id": "mixed-values", "kind": "mixed", "name": "类型",
              "fields": {
                "EXIF:Make": { "mode": "fixed", "value": 1.78 },
                "EXIF:FNumber": { "mode": "fixed", "value": 1.78 },
                "EXIF:ISOSpeedRatings": { "mode": "fixed", "value": 50 }
              }
            }
            """.trimIndent(),
        )
        val values = randomizer(1L).fill(preset, PresetTestSupport.emptySource()).values
        assertEquals(ValueType.TEXT, FieldCatalog.spec(make)!!.type)
        assertEquals(TagValue.Text("1.78"), values[make])
        assertEquals(TagValue.RationalValue(Rational(178, 100)), values[fNumber])
        assertEquals(TagValue.IntList(listOf(50L)), values[iso])
    }

    /** 折叠辅助：把操作序列交给执行器算成集合（测试内部用，避免依赖 UI 层）。 */
    private fun fold(operations: List<EditOperation>): MetadataSet =
        EditPlanExecutor.execute(EditPlan(operations, dryRun = true), PresetTestSupport.emptySource())
            .getOrNull()!!
            .target
}
