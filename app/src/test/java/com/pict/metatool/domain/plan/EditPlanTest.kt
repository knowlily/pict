package com.pict.metatool.domain.plan

import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/07 T2.1：编辑操作模型与编辑计划。 */
class EditPlanTest {

    private val dateTimeKey = TagKey.of("EXIF:DateTimeOriginal")
    private val makeKey = TagKey.of("EXIF:Make")

    @Test
    fun `SetField 承载任意值类型且按值比较`() {
        val a = EditOperation.SetField(
            dateTimeKey,
            TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 17, 30), ZoneOffset.ofHours(8)),
        )
        val b = EditOperation.SetField(
            dateTimeKey,
            TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 17, 30), ZoneOffset.ofHours(8)),
        )
        val noOffset = EditOperation.SetField(
            dateTimeKey,
            TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 17, 30)),
        )
        val rational = EditOperation.SetField(
            TagKey.of("EXIF:FNumber"),
            TagValue.RationalValue(Rational(28, 10)),
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a as Any, noOffset as Any)
        assertEquals(Rational(28, 10), (rational.value as TagValue.RationalValue).value)
    }

    @Test
    fun `清字段与清分组是两种不同操作`() {
        assertNotEquals(
            EditOperation.ClearField(makeKey) as Any,
            EditOperation.ClearGroup(FieldGroup.CAMERA) as Any,
        )
    }

    @Test
    fun `TimeShift 保留正负偏移`() {
        val forward = EditOperation.TimeShift(3_600_000L)
        val backward = EditOperation.TimeShift(-3_600_000L)

        assertEquals(3_600_000L, forward.deltaMillis)
        assertEquals(-3_600_000L, backward.deltaMillis)
        assertNotEquals(forward as Any, backward as Any)
    }

    @Test
    fun `RandomFill 的种子参与相等性`() {
        val fields = setOf(makeKey, dateTimeKey)

        assertEquals(
            EditOperation.RandomFill(fields, 42L),
            EditOperation.RandomFill(setOf(dateTimeKey, makeKey), 42L),
        )
        assertNotEquals(
            EditOperation.RandomFill(fields, 42L) as Any,
            EditOperation.RandomFill(fields, 43L) as Any,
        )
    }

    @Test
    fun `ApplyPreset 默认不覆盖已有值`() {
        val op = EditOperation.ApplyPreset("portrait-soft")

        assertEquals("portrait-soft", op.presetId)
        assertFalse(op.overwriteExisting)
        assertTrue(op.copy(overwriteExisting = true).overwriteExisting)
    }

    @Test
    fun `EditPlan 默认是空计划且默认先备份`() {
        val plan = EditPlan()

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.size)
        assertFalse(plan.dryRun)
        assertTrue(plan.backupBeforeOverwrite)
    }

    @Test
    fun `EditPlan 保留操作顺序`() {
        val plan = EditPlan(
            operations = listOf(
                EditOperation.SetField(makeKey, TagValue.Text("Pict")),
                EditOperation.ClearGroup(FieldGroup.LOCATION),
                EditOperation.TimeShift(86_400_000L),
            ),
            dryRun = true,
        )

        assertFalse(plan.isEmpty)
        assertEquals(3, plan.size)
        assertEquals(listOf("setField", "clearGroup", "timeShift"), plan.operations.map(::label))
        assertTrue(plan.dryRun)
    }

    /** 穷举测试：新增操作变体时这个 when 会编译失败，提醒同步测试与执行器。 */
    @Test
    fun `十种操作都能被穷举`() {
        val ops = listOf(
            EditOperation.SetField(makeKey, TagValue.Text("Pict")),
            EditOperation.ClearField(makeKey),
            EditOperation.ClearGroup(FieldGroup.XMP),
            EditOperation.ClearTargets(setOf(ClearTarget.GPS, ClearTarget.THUMBNAIL)),
            EditOperation.ClearAll(),
            EditOperation.TimeShift(1L),
            EditOperation.SetGps(31.2304, 121.4737),
            EditOperation.JitterGps(500.0, 1L),
            EditOperation.RandomFill(setOf(makeKey), 1L),
            EditOperation.ApplyPreset("p"),
        )

        assertEquals(
            listOf(
                "setField", "clearField", "clearGroup", "clearTargets", "clearAll", "timeShift",
                "setGps", "jitterGps", "randomFill", "applyPreset",
            ),
            ops.map(::label),
        )
    }

    private fun label(op: EditOperation): String = when (op) {
        is EditOperation.SetField -> "setField"
        is EditOperation.ClearField -> "clearField"
        is EditOperation.ClearGroup -> "clearGroup"
        is EditOperation.ClearTargets -> "clearTargets"
        is EditOperation.ClearAll -> "clearAll"
        is EditOperation.TimeShift -> "timeShift"
        is EditOperation.SetGps -> "setGps"
        is EditOperation.JitterGps -> "jitterGps"
        is EditOperation.RandomFill -> "randomFill"
        is EditOperation.ApplyPreset -> "applyPreset"
    }
}
