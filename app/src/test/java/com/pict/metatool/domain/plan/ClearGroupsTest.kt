package com.pict.metatool.domain.plan

import com.pict.metatool.core.result.getOrThrow
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T2.9、docs/01 FR-16 / FR-17、docs/08 验收点：按类别清空与整表清空。
 *
 * 覆盖两层：`ClearGroups` 的目标口径（清哪些、留哪些），以及执行器折叠后
 * 段级意图（缩略图 / ICC）是否如实带出来。
 */
class ClearGroupsTest {

    private val gpsLat = TagKey.of("GPS:GPSLatitude")
    private val gpsAlt = TagKey.of("GPS:GPSAltitude")
    private val xmpGpsLat = TagKey.of("XMP:exif:GPSLatitude")
    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")
    private val soft = TagKey.of("EXIF:Software")
    private val makerNote = TagKey.of("EXIF:MakerNote")
    private val takenAt = TagKey.of("EXIF:DateTimeOriginal")
    private val xmpTakenAt = TagKey.of("XMP:exif:DateTimeOriginal")
    private val xmpCreateTool = TagKey.of("XMP:xmp:CreatorTool")
    private val xmpSerial = TagKey.of("XMP:aux:SerialNumber")
    private val orientation = TagKey.of("EXIF:Orientation")
    private val colorSpace = TagKey.of("EXIF:ColorSpace")
    private val width = TagKey.of("EXIF:ImageWidth")
    private val height = TagKey.of("EXIF:ImageLength")
    private val dcTitle = TagKey.of("XMP:dc:title")
    private val iptcKeywords = TagKey.of("IPTC:2:25")
    private val iptcTime = TagKey.of("IPTC:2:60")
    private val thumbnail = TagKey.of("THUMBNAIL:JPEGInterchangeFormat")

    /** 未登记键：`XMP:crs:*` 在目录里是通配命名空间、不逐条枚举（FieldCatalog 顶部说明）。 */
    private val unregisteredXmp = TagKey.of("XMP:crs:Temperature")

    /** 未登记键：EXIF 命名空间里的厂商私有键，目录里查不到。 */
    private val unregisteredExif = TagKey.of("EXIF:VendorPrivateTag")

    private fun set(vararg pairs: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = SourceInfo("a.jpg", "image/jpeg", 2048, ImageFormatHint.JPEG),
        entries = pairs.associate { TagKey.of(it.first) to it.second },
    )

    /** 一个内容齐全的源文件：每个类别都有货，方便断言「只清了这一类」。 */
    private fun fullSet(): MetadataSet = set(
        "EXIF:Make" to TagValue.Text("Apple"),
        "EXIF:Model" to TagValue.Text("iPhone 15"),
        "EXIF:Software" to TagValue.Text("17.4.1"),
        "EXIF:MakerNote" to TagValue.Text("<bytes>"),
        "XMP:xmp:CreatorTool" to TagValue.Text("Lightroom 13.2"),
        "XMP:aux:SerialNumber" to TagValue.Text("SN-0001"),
        "EXIF:DateTimeOriginal" to TagValue.Text("2026:09:10 08:00:00"),
        "XMP:exif:DateTimeOriginal" to TagValue.Text("2026-09-10T08:00:00"),
        "IPTC:2:60" to TagValue.Text("080000+0800"),
        "GPS:GPSLatitude" to TagValue.Text("31,14,12.4N"),
        "GPS:GPSAltitude" to TagValue.Text("12/1"),
        "XMP:exif:GPSLatitude" to TagValue.Text("31.2304"),
        "XMP:dc:title" to TagValue.Text("外滩"),
        "IPTC:2:25" to TagValue.Text("上海"),
        "THUMBNAIL:JPEGInterchangeFormat" to TagValue.Text("160x120"),
        "EXIF:VendorPrivateTag" to TagValue.Text("private"),
    )

    private fun run(plan: EditPlan, source: MetadataSet): EditOutcome =
        EditPlanExecutor.execute(plan, source).getOrThrow()

    // ---------- 位置 / 时间：FR-17 的验收口径 ----------

    @Test
    fun `清位置只动位置组并保留时间与方向`() {
        val source = set(
            "GPS:GPSLatitude" to TagValue.Text("31,14,12.4N"),
            "XMP:exif:GPSLatitude" to TagValue.Text("31.2304"),
            "EXIF:DateTimeOriginal" to TagValue.Text("2026:09:10 08:00:00"),
            "EXIF:Orientation" to TagValue.Text("1"),
        )

        val cleared = ClearGroups.clear(source, ClearTarget.GPS)

        assertNull(cleared[gpsLat])
        assertNull(cleared[xmpGpsLat])
        assertNotNull(cleared[takenAt])
        assertNotNull(cleared[orientation])
    }

    @Test
    fun `定位组里不留任何键`() {
        val cleared = ClearGroups.clear(fullSet(), ClearTarget.GPS)

        val leftover = cleared.entries.keys.filter { ClearGroups.matches(it, ClearTarget.GPS) }
        assertEquals(emptyList<TagKey>(), leftover)
    }

    @Test
    fun `未登记的 GPS 命名空间键也一并清`() {
        val source = set("GPS:GPSProcessingMethod" to TagValue.Text("GPS"))

        val cleared = ClearGroups.clear(source, ClearTarget.GPS)

        assertTrue(cleared.isEmpty)
    }

    @Test
    fun `清时间含 XMP 时间字段但不动 IPTC 时间`() {
        val cleared = ClearGroups.clear(fullSet(), ClearTarget.TIME)

        assertNull(cleared[takenAt])
        assertNull(cleared[xmpTakenAt])
        // IPTC 的 2:60 是 IPTC 组，不属于时间类别
        assertNotNull(cleared[iptcTime])
    }

    // ---------- 设备信息 vs 厂商注释 ----------

    @Test
    fun `清设备信息保留 MakerNote`() {
        val cleared = ClearGroups.clear(fullSet(), ClearTarget.DEVICE)

        assertNull(cleared[make])
        assertNull(cleared[model])
        assertNull(cleared[soft])
        assertNull(cleared[xmpSerial])
        assertNull(cleared[xmpCreateTool])
        assertNotNull("MakerNote 是厂商私有块，清除风险不同，单列一类", cleared[makerNote])
    }

    @Test
    fun `清厂商注释只删 MakerNote`() {
        val cleared = ClearGroups.clear(fullSet(), ClearTarget.MAKER_NOTE)

        assertNull(cleared[makerNote])
        assertNotNull(cleared[make])
        assertNotNull(cleared[model])
        assertEquals(1, fullSet().changedKeys(cleared).size)
    }

    // ---------- XMP / IPTC：未登记键的口径 ----------

    @Test
    fun `清 XMP 不动已归入其它组的 XMP 键`() {
        val source = set(
            "XMP:dc:title" to TagValue.Text("外滩"),
            "XMP:crs:Temperature" to TagValue.Text("5500"),
            "XMP:xmp:CreatorTool" to TagValue.Text("Lightroom"),
            "XMP:xmp:CreateDate" to TagValue.Text("2026-09-10T08:00:00"),
        )

        val cleared = ClearGroups.clear(source, ClearTarget.XMP)

        assertNull(cleared[dcTitle])
        assertNull("未登记的 XMP 键按命名空间归入 XMP", cleared[unregisteredXmp])
        assertNotNull("创建工具归相机组", cleared[xmpCreateTool])
        assertNotNull("XMP 时间键归时间组", cleared[TagKey.of("XMP:xmp:CreateDate")])
    }

    @Test
    fun `清 IPTC 只动 IPTC 组`() {
        val cleared = ClearGroups.clear(fullSet(), ClearTarget.IPTC)

        assertNull(cleared[iptcKeywords])
        assertNotNull(cleared[dcTitle])
        assertNotNull(cleared[takenAt])
    }

    @Test
    fun `未登记的 EXIF 键一律保守保留`() {
        val source = fullSet()

        val cleared = ClearGroups.clear(
            source,
            setOf(ClearTarget.GPS, ClearTarget.TIME, ClearTarget.DEVICE, ClearTarget.XMP),
        )

        assertNotNull(
            "EXIF 未登记键分不清属于设备还是曝光，宁可不删",
            cleared[unregisteredExif],
        )
        assertFalse(ClearGroups.keysOf(source, ClearTarget.DEVICE).contains(unregisteredExif))
    }

    // ---------- 段级目标 ----------

    @Test
    fun `缩略图与 ICC 标记为段级载体`() {
        assertTrue(ClearTarget.THUMBNAIL.isSegmentLevel)
        assertTrue(ClearTarget.ICC.isSegmentLevel)
        assertFalse(ClearTarget.GPS.isSegmentLevel)
        assertFalse(ClearTarget.MAKER_NOTE.isSegmentLevel)
    }

    @Test
    fun `ICC 没有字段键`() {
        assertTrue(ClearGroups.keysOf(fullSet(), ClearTarget.ICC).isEmpty())
        assertEquals(fullSet().entries, ClearGroups.clear(fullSet(), ClearTarget.ICC).entries)
    }

    @Test
    fun `缩略图能清掉伪字段键`() {
        val cleared = ClearGroups.clear(fullSet(), ClearTarget.THUMBNAIL)

        assertNull(cleared[thumbnail])
        assertNotNull(cleared[dcTitle])
    }

    @Test
    fun `段级意图去重且只取段级目标`() {
        assertEquals(
            setOf(ClearSegment.THUMBNAIL, ClearSegment.ICC_PROFILE),
            ClearGroups.segmentsOf(setOf(ClearTarget.THUMBNAIL, ClearTarget.ICC, ClearTarget.GPS)),
        )
        assertTrue(ClearGroups.segmentsOf(setOf(ClearTarget.GPS, ClearTarget.TIME)).isEmpty())
        assertTrue(ClearGroups.segmentsOf(emptySet()).isEmpty())
    }

    @Test
    fun `FR-17 的八个类别都在`() {
        assertEquals(
            listOf("GPS", "DEVICE", "TIME", "MAKER_NOTE", "THUMBNAIL", "XMP", "IPTC", "ICC"),
            ClearGroups.targets.map { it.name },
        )
    }

    // ---------- 整表清空 ----------

    @Test
    fun `清全部保留结构字段与方向色彩空间`() {
        val source = set(
            "EXIF:ImageWidth" to TagValue.IntValue(4032),
            "EXIF:ImageLength" to TagValue.IntValue(3024),
            "EXIF:XResolution" to TagValue.RationalValue(Rational(72, 1)),
            "EXIF:Orientation" to TagValue.Text("1"),
            "EXIF:ColorSpace" to TagValue.Text("1"),
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:DateTimeOriginal" to TagValue.Text("2026:09:10 08:00:00"),
            "GPS:GPSLatitude" to TagValue.Text("31,14,12.4N"),
            "XMP:dc:title" to TagValue.Text("外滩"),
            "IPTC:2:25" to TagValue.Text("上海"),
            "EXIF:VendorPrivateTag" to TagValue.Text("private"),
        )

        val cleared = ClearGroups.clearAll(source)

        assertEquals(
            "只剩结构字段与方向/色彩空间",
            setOf(width, height, orientation, colorSpace),
            cleared.entries.keys,
        )
    }

    @Test
    fun `清全部可以连结构字段一起清`() {
        val cleared = ClearGroups.clearAll(fullSet(), protectStructural = false)

        assertTrue(cleared.isEmpty)
        assertTrue(ClearGroups.keysToClearAll(set(), protectStructural = false).isEmpty())
    }

    @Test
    fun `保护范围就是结构字段加方向色彩空间`() {
        assertTrue(ClearGroups.isProtected(width))
        assertTrue(ClearGroups.isProtected(orientation))
        assertTrue(ClearGroups.isProtected(colorSpace))
        assertFalse(ClearGroups.isProtected(make))
        assertFalse(ClearGroups.isProtected(unregisteredExif))
    }

    @Test
    fun `清空不修改入参`() {
        val source = fullSet()
        val before = source.entries

        ClearGroups.clear(source, ClearTarget.GPS)
        ClearGroups.clearAll(source)

        assertEquals(before, source.entries)
    }

    // ---------- 执行器折叠 ----------

    @Test
    fun `ClearTargets 折叠后计入 diff`() {
        val source = set(
            "GPS:GPSLatitude" to TagValue.Text("31,14,12.4N"),
            "EXIF:Make" to TagValue.Text("Apple"),
        )

        val outcome = run(EditPlan(listOf(EditOperation.ClearTargets(setOf(ClearTarget.GPS)))), source)

        assertEquals(setOf(gpsLat), outcome.changedKeys)
        assertTrue(outcome.segmentClears.isEmpty())
        assertFalse(outcome.isEmpty)
    }

    @Test
    fun `只清 ICC 时字段 diff 为空但不算空操作`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))

        val outcome = run(EditPlan(listOf(EditOperation.ClearTargets(setOf(ClearTarget.ICC)))), source)

        assertEquals(emptySet<TagKey>(), outcome.changedKeys)
        assertEquals(setOf(ClearSegment.ICC_PROFILE), outcome.segmentClears)
        assertFalse("段级活儿没干完就不能算空", outcome.isEmpty)
    }

    @Test
    fun `ClearAll 带出缩略图与 ICC 的段级意图`() {
        val outcome = run(EditPlan(listOf(EditOperation.ClearAll())), fullSet())

        assertEquals(ClearGroups.ALL_SEGMENTS, outcome.segmentClears)
        assertNull(outcome.target[gpsLat])
        assertNull(outcome.target[dcTitle])
        assertNull(outcome.target[iptcKeywords])
    }

    @Test
    fun `ClearAll 走执行器时保护口径一致`() {
        val source = set(
            "EXIF:ImageWidth" to TagValue.IntValue(4032),
            "EXIF:Orientation" to TagValue.Text("1"),
            "EXIF:Make" to TagValue.Text("Apple"),
        )

        val outcome = run(EditPlan(listOf(EditOperation.ClearAll())), source)

        assertEquals(setOf(width, orientation), outcome.target.entries.keys)
        assertEquals(setOf(make), outcome.changedKeys)
    }

    @Test
    fun `先清空再设置仍是后者胜`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))

        val outcome = run(
            EditPlan(
                listOf(
                    EditOperation.ClearAll(),
                    EditOperation.SetField(make, TagValue.Text("Pict")),
                ),
            ),
            source,
        )

        assertEquals("Pict", (outcome.target[make] as TagValue.Text).value)
    }

    @Test
    fun `先设置再清空会删掉值`() {
        val outcome = run(
            EditPlan(
                listOf(
                    EditOperation.SetField(make, TagValue.Text("Pict")),
                    EditOperation.ClearAll(),
                ),
            ),
            set("EXIF:Make" to TagValue.Text("Apple")),
        )

        assertNull(outcome.target[make])
        assertTrue(outcome.changedKeys.contains(make))
    }

    @Test
    fun `段级意图按操作顺序累计去重`() {
        val outcome = run(
            EditPlan(
                listOf(
                    EditOperation.ClearTargets(setOf(ClearTarget.THUMBNAIL)),
                    EditOperation.ClearTargets(setOf(ClearTarget.THUMBNAIL, ClearTarget.ICC)),
                ),
            ),
            fullSet(),
        )

        assertEquals(
            setOf(ClearSegment.THUMBNAIL, ClearSegment.ICC_PROFILE),
            outcome.segmentClears,
        )
    }
}
