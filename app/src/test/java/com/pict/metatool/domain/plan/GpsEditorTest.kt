package com.pict.metatool.domain.plan

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.getOrThrow
import com.pict.metatool.domain.format.GpsCoordinate
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** docs/07 T2.8、docs/01 FR-15：GPS 编辑的写入、解析、范围校验与抖动。 */
class GpsEditorTest {

    private val latitude = TagKey.of("GPS:GPSLatitude")
    private val latitudeRef = TagKey.of("GPS:GPSLatitudeRef")
    private val longitude = TagKey.of("GPS:GPSLongitude")
    private val longitudeRef = TagKey.of("GPS:GPSLongitudeRef")
    private val altitude = TagKey.of("GPS:GPSAltitude")
    private val altitudeRef = TagKey.of("GPS:GPSAltitudeRef")

    /** 上海人民广场附近，用于抖动用例。 */
    private val shanghaiLat = 31.2304
    private val shanghaiLon = 121.4737

    private fun set(vararg pairs: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = SourceInfo("a.jpg", "image/jpeg", 1024, ImageFormatHint.JPEG),
        entries = pairs.associate { TagKey.of(it.first) to it.second },
    )

    private fun withGps(latitude: Double, longitude: Double): MetadataSet =
        set(
            "EXIF:Make" to TagValue.Text("Pict"),
            "GPS:GPSLatitudeRef" to TagValue.Text(GpsCoordinate.refFor(latitude, isLatitude = true)),
            "GPS:GPSLatitude" to TagValue.RationalList(GpsCoordinate.decimalToDms(latitude)),
            "GPS:GPSLongitudeRef" to TagValue.Text(GpsCoordinate.refFor(longitude, isLatitude = false)),
            "GPS:GPSLongitude" to TagValue.RationalList(GpsCoordinate.decimalToDms(longitude)),
        )

    private fun failure(result: PictResult<*>): PictResult.Failure = result as PictResult.Failure

    private fun positionOf(set: MetadataSet): GpsEditor.Position =
        requireNotNull(GpsEditor.position(set)) { "期望能读出坐标" }

    // ---------- 写入 ----------

    @Test
    fun `写入坐标时同步 Ref`() {
        val written = GpsEditor.set(set("EXIF:Make" to TagValue.Text("Pict")), shanghaiLat, shanghaiLon)
            .getOrThrow()

        assertEquals(TagValue.Text("N"), written[latitudeRef])
        assertEquals(TagValue.Text("E"), written[longitudeRef])
        assertEquals(TagValue.RationalList(GpsCoordinate.decimalToDms(shanghaiLat)), written[latitude])
        assertEquals(TagValue.RationalList(GpsCoordinate.decimalToDms(shanghaiLon)), written[longitude])
    }

    @Test
    fun `南纬西经写 S 与 W`() {
        val written = GpsEditor.set(set(), -33.8688, -70.6693).getOrThrow()

        assertEquals(TagValue.Text("S"), written[latitudeRef])
        assertEquals(TagValue.Text("W"), written[longitudeRef])
        val position = positionOf(written)
        assertEquals(-33.8688, position.latitude, 1e-6)
        assertEquals(-70.6693, position.longitude, 1e-6)
    }

    @Test
    fun `覆盖已有坐标时旧 Ref 一并更新`() {
        val source = withGps(shanghaiLat, shanghaiLon)
        val written = GpsEditor.set(source, -1.5, -2.5).getOrThrow()

        assertEquals(TagValue.Text("S"), written[latitudeRef])
        assertEquals(TagValue.Text("W"), written[longitudeRef])
    }

    @Test
    fun `范围边界值可写，越界失败`() {
        assertTrue(GpsEditor.set(set(), 90.0, 180.0).isSuccess)
        assertTrue(GpsEditor.set(set(), -90.0, -180.0).isSuccess)

        val latFailure = failure(GpsEditor.set(set(), 90.0001, 0.0))
        assertEquals(PictError.FIELD_INVALID, latFailure.error)
        assertTrue(latFailure.failure.detail!!.contains("纬度"))

        val lonFailure = failure(GpsEditor.set(set(), 0.0, -180.5))
        assertTrue(lonFailure.failure.detail!!.contains("经度"))
    }

    @Test
    fun `非有限坐标被拒绝`() {
        val result = failure(GpsEditor.set(set(), Double.NaN, 0.0))

        assertEquals(PictError.FIELD_INVALID, result.error)
        assertTrue(result.failure.detail!!.contains("不是有效数值"))
    }

    @Test
    fun `海拔写入带方向 Ref`() {
        val above = GpsEditor.set(set(), 10.0, 20.0, 12.5).getOrThrow()
        assertEquals(TagValue.IntValue(0), above[altitudeRef])
        assertEquals(TagValue.RationalValue(Rational(12_500, 1_000)), above[altitude])
        assertEquals(12.5, positionOf(above).altitudeMeters!!, 1e-9)

        val below = GpsEditor.set(set(), 10.0, 20.0, -3.5).getOrThrow()
        assertEquals(TagValue.IntValue(1), below[altitudeRef])
        assertEquals(-3.5, positionOf(below).altitudeMeters!!, 1e-9)
    }

    @Test
    fun `不给海拔时保留源值`() {
        val source = withGps(shanghaiLat, shanghaiLon)
            .with(altitudeRef, TagValue.IntValue(0))
            .with(altitude, TagValue.RationalValue(Rational(42_000, 1_000)))

        val written = GpsEditor.set(source, 30.0, 120.0).getOrThrow()

        assertEquals(42.0, positionOf(written).altitudeMeters!!, 1e-9)
        assertNull(GpsEditor.set(set(), 30.0, 120.0).getOrThrow()[altitude])
    }

    // ---------- 文本解析 ----------

    @Test
    fun `解析十进制度`() {
        assertEquals(31.2304, GpsEditor.parseDegrees("31.2304")!!, 1e-9)
        assertEquals(-31.2304, GpsEditor.parseDegrees("-31.2304")!!, 1e-9)
        assertEquals(31.2304, GpsEditor.parseDegrees(" 31.2304N ")!!, 1e-9)
        assertEquals(-31.2304, GpsEditor.parseDegrees("S31.2304")!!, 1e-9)
        assertEquals(-120.5, GpsEditor.parseDegrees("120.5W")!!, 1e-9)
    }

    @Test
    fun `解析度分秒`() {
        val expected = 31.0 + 14.0 / 60.0 + 12.4 / 3600.0

        assertEquals(expected, GpsEditor.parseDegrees("31°14'12.4\"N")!!, 1e-9)
        assertEquals(-expected, GpsEditor.parseDegrees("31 14 12.4 S")!!, 1e-9)
        assertEquals(31.0 + 14.0 / 60.0, GpsEditor.parseDegrees("31:14")!!, 1e-9)
        assertEquals(31.0 + 14.0 / 60.0, GpsEditor.parseDegrees("31°14'N")!!, 1e-9)
    }

    @Test
    fun `无法识别的输入返回 null`() {
        assertNull(GpsEditor.parseDegrees(""))
        assertNull(GpsEditor.parseDegrees("   "))
        assertNull(GpsEditor.parseDegrees("abc"))
        assertNull(GpsEditor.parseDegrees("N-31.2"))
        assertNull(GpsEditor.parseDegrees("31 90 0"))
        assertNull(GpsEditor.parseDegrees("31 14 12.4 X"))
        assertNull(GpsEditor.parseDegrees("S"))
    }

    @Test
    fun `setFromText 端到端写入`() {
        val written = GpsEditor
            .setFromText(set(), "31°14'12.4\"N", "121°28'25.3\"E", "10.5")
            .getOrThrow()

        val position = positionOf(written)
        assertEquals(31.0 + 14.0 / 60.0 + 12.4 / 3600.0, position.latitude, 1e-6)
        assertEquals(121.0 + 28.0 / 60.0 + 25.3 / 3600.0, position.longitude, 1e-6)
        assertEquals(10.5, position.altitudeMeters!!, 1e-9)
        assertEquals(TagValue.Text("N"), written[latitudeRef])
        assertEquals(TagValue.Text("E"), written[longitudeRef])
    }

    @Test
    fun `setFromText 对无法识别的文本显式失败`() {
        val result = failure(GpsEditor.setFromText(set(), "纬度", "121.4737"))

        assertEquals(PictError.FIELD_INVALID, result.error)
        assertTrue(result.failure.detail!!.contains("无法识别的纬度输入"))
    }

    @Test
    fun `setFromText 沿用范围校验`() {
        val result = failure(GpsEditor.setFromText(set(), "95", "121.4737"))

        assertTrue(result.failure.detail!!.contains("超出范围"))
    }

    // ---------- 抖动 ----------

    @Test
    fun `抖动落在半径内`() {
        val source = withGps(shanghaiLat, shanghaiLon)

        for (seed in 1L..20L) {
            val jittered = GpsEditor.jitter(source, 500.0, seed).getOrThrow()
            val position = positionOf(jittered)
            val distance = GpsEditor.sphericalDistanceMeters(
                shanghaiLat, shanghaiLon, position.latitude, position.longitude,
            )
            assertTrue("seed=$seed 距离 $distance 超出 500 m", distance <= 500.0)
        }
    }

    @Test
    fun `抖动确实移动了坐标`() {
        val jittered = GpsEditor.jitter(withGps(shanghaiLat, shanghaiLon), 500.0, 12345L).getOrThrow()
        val position = positionOf(jittered)

        assertTrue(
            GpsEditor.sphericalDistanceMeters(shanghaiLat, shanghaiLon, position.latitude, position.longitude) > 0.0,
        )
    }

    @Test
    fun `同种子结果可复现，不同种子结果不同`() {
        val source = withGps(shanghaiLat, shanghaiLon)
        val first = GpsEditor.jitter(source, 500.0, 12345L).getOrThrow()
        val second = GpsEditor.jitter(source, 500.0, 12345L).getOrThrow()
        val other = GpsEditor.jitter(source, 500.0, 54321L).getOrThrow()

        assertEquals(first, second)
        assertTrue(first[latitude] != other[latitude] || first[longitude] != other[longitude])
    }

    @Test
    fun `抖动半径越界显式失败`() {
        val source = withGps(shanghaiLat, shanghaiLon)

        val tooSmall = failure(GpsEditor.jitter(source, 10.0, 1L))
        assertEquals(PictError.FIELD_INVALID, tooSmall.error)
        assertTrue(tooSmall.failure.detail!!.contains("抖动半径"))

        assertTrue(GpsEditor.jitter(source, 5_001.0, 1L) is PictResult.Failure)
        assertTrue(GpsEditor.jitter(source, 50.0, 1L).isSuccess)
        assertTrue(GpsEditor.jitter(source, 5_000.0, 1L).isSuccess)
    }

    @Test
    fun `缺 GPS 时抖动显式失败`() {
        val result = failure(GpsEditor.jitter(set("EXIF:Make" to TagValue.Text("Pict")), 500.0, 1L))

        assertEquals(PictError.FIELD_INVALID, result.error)
        assertTrue(result.failure.detail!!.contains("没有完整的 GPS 坐标"))
    }

    @Test
    fun `抖动保留其余字段与海拔`() {
        val source = withGps(shanghaiLat, shanghaiLon)
            .with(altitudeRef, TagValue.IntValue(0))
            .with(altitude, TagValue.RationalValue(Rational(15_000, 1_000)))

        val jittered = GpsEditor.jitter(source, 500.0, 7L).getOrThrow()

        assertEquals(TagValue.Text("Pict"), jittered[TagKey.of("EXIF:Make")])
        assertEquals(15.0, positionOf(jittered).altitudeMeters!!, 1e-9)
    }

    @Test
    fun `抖动后 Ref 与符号保持一致`() {
        // 赤道以南、本初子午线以西，抖动半径拉满，符号不得翻转
        val source = withGps(-33.8688, -70.6693)
        val jittered = GpsEditor.jitter(source, 5_000.0, 99L).getOrThrow()

        assertEquals(TagValue.Text("S"), jittered[latitudeRef])
        assertEquals(TagValue.Text("W"), jittered[longitudeRef])
        assertTrue(positionOf(jittered).latitude < 0)
        assertTrue(positionOf(jittered).longitude < 0)
    }

    @Test
    fun `跨正负 180 度时经度归一化`() {
        val source = withGps(0.5, 179.99)

        for (seed in 1L..10L) {
            val position = positionOf(GpsEditor.jitter(source, 5_000.0, seed).getOrThrow())
            assertTrue("经度越界：${position.longitude}", abs(position.longitude) <= 180.0)
            assertTrue(
                GpsEditor.sphericalDistanceMeters(0.5, 179.99, position.latitude, position.longitude) <= 5_000.0,
            )
        }
    }

    @Test
    fun `读出坐标：缺一个方向返回 null`() {
        val partial = set(
            "GPS:GPSLatitude" to TagValue.RationalList(GpsCoordinate.decimalToDms(shanghaiLat)),
            "GPS:GPSLatitudeRef" to TagValue.Text("N"),
        )

        assertNull(GpsEditor.position(partial))
        assertNull(GpsEditor.position(set()))
    }

    @Test
    fun `没有 Ref 时按正方向读出`() {
        val noRef = set(
            "GPS:GPSLatitude" to TagValue.RationalList(GpsCoordinate.decimalToDms(shanghaiLat)),
            "GPS:GPSLongitude" to TagValue.RationalList(GpsCoordinate.decimalToDms(shanghaiLon)),
        )

        val position = GpsEditor.position(noRef)
        assertTrue(position != null)
        assertEquals(shanghaiLat, position!!.latitude, 1e-6)
        assertEquals(shanghaiLon, position.longitude, 1e-6)
    }

    // ---------- 与编辑计划集成 ----------

    @Test
    fun `SetGps 经 EditPlanExecutor 折叠后计入 diff`() {
        val source = set("EXIF:Make" to TagValue.Text("Pict"))
        val outcome = EditPlanExecutor
            .execute(EditPlan(listOf(EditOperation.SetGps(10.0, 20.0))), source)
            .getOrThrow()

        assertEquals(
            setOf(latitude, latitudeRef, longitude, longitudeRef),
            outcome.changedKeys,
        )
        assertEquals(10.0, positionOf(outcome.target).latitude, 1e-6)
        assertEquals(TagValue.Text("Pict"), outcome.target[TagKey.of("EXIF:Make")])
    }

    @Test
    fun `JitterGps 经 EditPlanExecutor 折叠后可复现`() {
        val source = withGps(shanghaiLat, shanghaiLon)
        val plan = EditPlan(listOf(EditOperation.JitterGps(500.0, 2024L)))

        val first = EditPlanExecutor.execute(plan, source).getOrThrow()
        val second = EditPlanExecutor.execute(plan, source).getOrThrow()

        assertEquals(first.target, second.target)
        assertTrue(first.changedKeys.contains(latitude))
        assertTrue(first.changedKeys.contains(longitude))
    }

    @Test
    fun `先设后抖动的顺序生效`() {
        val source = withGps(shanghaiLat, shanghaiLon)
        val outcome = EditPlanExecutor
            .execute(
                EditPlan(
                    listOf(
                        EditOperation.SetGps(-33.8688, -70.6693),
                        EditOperation.JitterGps(50.0, 11L),
                    ),
                ),
                source,
            )
            .getOrThrow()

        val position = positionOf(outcome.target)
        assertTrue(
            GpsEditor.sphericalDistanceMeters(-33.8688, -70.6693, position.latitude, position.longitude) <= 50.0,
        )
        assertEquals(TagValue.Text("S"), outcome.target[latitudeRef])
    }
}
