package com.pict.metatool.domain.plan

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.getOrThrow
import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/07 T2.2、docs/08：操作序列折叠成目标元数据与变更 diff。 */
class EditPlanExecutorTest {

    private val makeKey = TagKey.of("EXIF:Make")
    private val modelKey = TagKey.of("EXIF:Model")
    private val takenAtKey = TagKey.of("EXIF:DateTimeOriginal")
    private val widthKey = TagKey.of("EXIF:ImageWidth")

    private fun set(vararg pairs: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source = SourceInfo("a.jpg", "image/jpeg", 1024, ImageFormatHint.JPEG),
        entries = pairs.associate { TagKey.of(it.first) to it.second },
    )

    private fun run(plan: EditPlan, source: MetadataSet): EditOutcome =
        EditPlanExecutor.execute(plan, source).getOrThrow()

    @Test
    fun `设置新字段并计入 diff`() {
        val source = set()
        val outcome = run(EditPlan(listOf(EditOperation.SetField(makeKey, TagValue.Text("Pict")))), source)

        assertEquals("Pict", (outcome.target[makeKey] as TagValue.Text).value)
        assertEquals(setOf(makeKey), outcome.changedKeys)
        assertEquals(1, outcome.size)
        assertFalse(outcome.isEmpty)
    }

    @Test
    fun `覆盖已有字段`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))
        val outcome = run(EditPlan(listOf(EditOperation.SetField(makeKey, TagValue.Text("Pict")))), source)

        assertEquals("Pict", (outcome.target[makeKey] as TagValue.Text).value)
        assertEquals(setOf(makeKey), outcome.changedKeys)
    }

    @Test
    fun `清除字段并计入 diff`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))
        val outcome = run(EditPlan(listOf(EditOperation.ClearField(makeKey))), source)

        assertNull(outcome.target[makeKey])
        assertEquals(setOf(makeKey), outcome.changedKeys)
    }

    @Test
    fun `清除本来不存在的字段不产生 diff`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))
        val outcome = run(EditPlan(listOf(EditOperation.ClearField(modelKey))), source)

        assertTrue(outcome.isEmpty)
        assertEquals(source.size, outcome.target.size)
    }

    @Test
    fun `清空分组只清该组字段`() {
        val source = set(
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:Model" to TagValue.Text("iPhone 16 Pro"),
            "EXIF:DateTimeOriginal" to TagValue.Text("2026:09:09 17:30:00"),
        )
        val outcome = run(EditPlan(listOf(EditOperation.ClearGroup(FieldGroup.CAMERA))), source)

        assertNull(outcome.target[makeKey])
        assertNull(outcome.target[modelKey])
        assertEquals("2026:09:09 17:30:00", (outcome.target[takenAtKey] as TagValue.Text).value)
        assertEquals(setOf(makeKey, modelKey), outcome.changedKeys)
    }

    @Test
    fun `清空分组能清掉只读的结构字段`() {
        val source = set(
            "EXIF:ImageWidth" to TagValue.IntValue(4000),
            "EXIF:Make" to TagValue.Text("Apple"),
        )
        val outcome = run(EditPlan(listOf(EditOperation.ClearGroup(FieldGroup.FILE))), source)

        assertNull(outcome.target[widthKey])
        assertEquals(setOf(widthKey), outcome.changedKeys)
    }

    @Test
    fun `先设后清则字段被删除`() {
        val plan = EditPlan(
            listOf(
                EditOperation.SetField(makeKey, TagValue.Text("Pict")),
                EditOperation.ClearField(makeKey),
            ),
        )
        val outcome = run(plan, set())

        assertNull(outcome.target[makeKey])
        assertTrue(outcome.isEmpty)
    }

    @Test
    fun `先清后设则保留后写入的值`() {
        val plan = EditPlan(
            listOf(
                EditOperation.ClearField(makeKey),
                EditOperation.SetField(makeKey, TagValue.Text("Pict")),
            ),
        )
        val outcome = run(plan, set("EXIF:Make" to TagValue.Text("Apple")))

        assertEquals("Pict", (outcome.target[makeKey] as TagValue.Text).value)
        assertEquals(setOf(makeKey), outcome.changedKeys)
    }

    @Test
    fun `空计划不产生 diff`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))
        val outcome = run(EditPlan(), source)

        assertTrue(outcome.isEmpty)
        assertEquals(source.entries, outcome.target.entries)
    }

    @Test
    fun `写入同值不算变化`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))
        val outcome = run(EditPlan(listOf(EditOperation.SetField(makeKey, TagValue.Text("Apple")))), source)

        assertTrue(outcome.isEmpty)
    }

    @Test
    fun `只读字段被拒绝`() {
        val plan = EditPlan(listOf(EditOperation.SetField(widthKey, TagValue.IntValue(100))))
        val result = EditPlanExecutor.execute(plan, set())

        val failure = result.failureOrNull()
        assertEquals(PictError.FIELD_INVALID, failure?.error)
        assertTrue(failure?.detail.orEmpty().contains("EXIF:ImageWidth"))
    }

    @Test
    fun `尚未实现的操作显式失败而不是静默跳过`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))
        val ops = listOf(
            EditOperation.TimeShift(3_600_000L),
            EditOperation.RandomFill(setOf(makeKey), 1L),
            EditOperation.ApplyPreset("portrait-soft"),
        )

        for (op in ops) {
            val result: PictResult<EditOutcome> = EditPlanExecutor.execute(EditPlan(listOf(op)), source)
            val failure = result.failureOrNull()
            assertEquals("$op 应该失败", PictError.META_WRITE, failure?.error)
            assertTrue("$op 的失败信息应指明未实现", failure?.detail.orEmpty().contains("尚未实现"))
        }
    }

    @Test
    fun `dryRun 不影响折叠结果`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))
        val ops = listOf(EditOperation.SetField(modelKey, TagValue.Text("Pict")))
        val live = run(EditPlan(ops, dryRun = false), source)
        val preview = run(EditPlan(ops, dryRun = true), source)

        assertEquals(live.changedKeys, preview.changedKeys)
        assertEquals(live.target.entries, preview.target.entries)
    }
}
