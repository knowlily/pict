package com.pict.metatool.domain.plan

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.getOrThrow
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** docs/07 T2.7、docs/04 §6.8：时间偏移按本地时间算术，`OffsetTime*` / `SubSecTime*` 原样保留。 */
class TimeShiftTest {

    private val dateTime = TagKey.of("EXIF:DateTime")
    private val original = TagKey.of("EXIF:DateTimeOriginal")
    private val digitized = TagKey.of("EXIF:DateTimeDigitized")
    private val offsetOriginal = TagKey.of("EXIF:OffsetTimeOriginal")
    private val subSecOriginal = TagKey.of("EXIF:SubSecTimeOriginal")

    private val base = LocalDateTime.of(2024, 5, 1, 12, 0, 0)

    private fun set(vararg pairs: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = SourceInfo("a.jpg", "image/jpeg", 1024, ImageFormatHint.JPEG),
        entries = pairs.associate { TagKey.of(it.first) to it.second },
    )

    private fun shift(set: MetadataSet, delta: Long): MetadataSet =
        TimeShift.apply(set, delta).getOrThrow()

    private fun failure(set: MetadataSet, delta: Long): PictResult.Failure =
        TimeShift.apply(set, delta) as PictResult.Failure

    private fun timestampOf(set: MetadataSet, key: TagKey): LocalDateTime =
        (set[key] as TagValue.Timestamp).value

    @Test
    fun `三个日期字段同步平移`() {
        val source = set(
            "EXIF:DateTime" to TagValue.Timestamp(base),
            "EXIF:DateTimeOriginal" to TagValue.Timestamp(base),
            "EXIF:DateTimeDigitized" to TagValue.Timestamp(base),
        )
        val shifted = shift(source, 3_600_000L)

        assertEquals(base.plusHours(1), timestampOf(shifted, dateTime))
        assertEquals(base.plusHours(1), timestampOf(shifted, original))
        assertEquals(base.plusHours(1), timestampOf(shifted, digitized))
    }

    @Test
    fun `负偏移向过去`() {
        val source = set("EXIF:DateTimeOriginal" to TagValue.Timestamp(base))
        val shifted = shift(source, -86_400_000L)

        assertEquals(base.minusDays(1), timestampOf(shifted, original))
    }

    @Test
    fun `缺失的字段跳过，不凭空造值`() {
        val source = set("EXIF:DateTimeOriginal" to TagValue.Timestamp(base))
        val shifted = shift(source, 60_000L)

        assertEquals(base.plusMinutes(1), timestampOf(shifted, original))
        assertNull(shifted[dateTime])
        assertNull(shifted[digitized])
    }

    @Test
    fun `三个字段全缺时显式失败`() {
        val result = failure(set("EXIF:Make" to TagValue.Text("Pict")), 60_000L)

        assertEquals(PictError.FIELD_INVALID, result.failure.error)
        assertTrue(result.failure.detail!!.contains("没有可平移的日期字段"))
    }

    @Test
    fun `溢出返回失败而不是截断`() {
        val result = failure(set("EXIF:DateTimeOriginal" to TagValue.Timestamp(LocalDateTime.MAX)), 1L)

        assertEquals(PictError.FIELD_INVALID, result.failure.error)
        assertTrue(result.failure.detail!!.contains("超出可表示范围"))
    }

    @Test
    fun `OffsetTime 与 SubSecTime 原样保留`() {
        val source = set(
            "EXIF:DateTimeOriginal" to TagValue.Timestamp(base),
            "EXIF:OffsetTimeOriginal" to TagValue.Text("+08:00"),
            "EXIF:SubSecTimeOriginal" to TagValue.Text("123"),
        )
        val shifted = shift(source, 3_600_000L)

        assertEquals(base.plusHours(1), timestampOf(shifted, original))
        assertEquals("+08:00", (shifted[offsetOriginal] as TagValue.Text).value)
        assertEquals("123", (shifted[subSecOriginal] as TagValue.Text).value)
    }

    @Test
    fun `Timestamp 自带的 offset 保留`() {
        val source = set(
            "EXIF:DateTimeOriginal" to TagValue.Timestamp(base, ZoneOffset.ofHours(8)),
        )
        val shifted = shift(source, 3_600_000L)
        val value = shifted[original] as TagValue.Timestamp

        assertEquals(base.plusHours(1), value.value)
        assertEquals(ZoneOffset.ofHours(8), value.offset)
    }

    @Test
    fun `零偏移是空操作`() {
        val source = set("EXIF:DateTimeOriginal" to TagValue.Timestamp(base))
        val shifted = shift(source, 0L)

        assertEquals(source, shifted)
    }

    @Test
    fun `非时间类型的值跳过`() {
        val source = set(
            "EXIF:DateTimeOriginal" to TagValue.Timestamp(base),
            "EXIF:DateTime" to TagValue.Text("不是时间"),
        )
        val shifted = shift(source, 60_000L)

        assertEquals(base.plusMinutes(1), timestampOf(shifted, original))
        assertEquals("不是时间", (shifted[dateTime] as TagValue.Text).value)
    }

    @Test
    fun `经 EditPlanExecutor 折叠后计入 diff`() {
        val source = set(
            "EXIF:DateTimeOriginal" to TagValue.Timestamp(base),
            "EXIF:Make" to TagValue.Text("Pict"),
        )
        val outcome = EditPlanExecutor
            .execute(EditPlan(listOf(EditOperation.TimeShift(3_600_000L))), source)
            .getOrThrow()

        assertEquals(base.plusHours(1), timestampOf(outcome.target, original))
        assertEquals(setOf(original), outcome.changedKeys)
    }
}
