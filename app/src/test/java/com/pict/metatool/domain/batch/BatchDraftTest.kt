package com.pict.metatool.domain.batch

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.preset.PresetTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量草稿（T5.4 的「选什么」）与批量种子口径（[seededFor]）。
 *
 * 这里只碰 domain：界面怎么画不重要，重要的是「选了预设 → 算出正确的计划」
 * 和「一批图不要被重掷成同一张」这两条。
 */
class BatchDraftTest {

    private val device = PresetTestSupport.preset("device.iphone-16-pro")
    private val catalog = PresetTestSupport.catalog(device)

    private val target = BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.JPEG)

    private fun source(name: String): MetadataSet = PresetTestSupport.emptySource()

    // ---------- 草稿 → 计划 ----------

    @Test
    fun `套用预设：没选预设不算填完，也不给计划`() {
        val draft = BatchDraft(mode = BatchMode.PRESET)

        assertFalse(draft.isReady)
        assertEquals(PictError.FIELD_INVALID, draft.toPlan(catalog).failureOrNull()?.error)
    }

    @Test
    fun `套用预设：计划里带覆盖开关与种子`() {
        val draft = BatchDraft(
            mode = BatchMode.PRESET,
            presetIds = listOf(device.id),
            overwriteExisting = true,
            seed = 42L,
        )

        assertTrue(draft.isReady)
        val operation = draft.toPlan(catalog).getOrNull()!!.operations.single() as EditOperation.ApplyPreset
        assertEquals(device.id, operation.presetId)
        assertTrue(operation.overwriteExisting)
        assertEquals(42L, operation.seed)
    }

    @Test
    fun `套用预设：目录里没有这个预设时报字段非法`() {
        val draft = BatchDraft(mode = BatchMode.PRESET, presetIds = listOf("hello.kitty"))

        assertEquals(PictError.FIELD_INVALID, draft.toPlan(catalog).failureOrNull()?.error)
    }

    @Test
    fun `随机填充：没给预设时讲清楚为什么要预设`() {
        val draft = BatchDraft(mode = BatchMode.RANDOM)

        assertFalse(draft.isReady)
        val detail = draft.toPlan(catalog).failureOrNull()?.detail.orEmpty()
        assertTrue("失败理由要指向取值来源：$detail", detail.contains("预设"))
    }

    @Test
    fun `随机填充：字段取自预设声明的全部字段`() {
        val draft = BatchDraft(mode = BatchMode.RANDOM, presetIds = listOf(device.id), seed = 7L)

        val operation = draft.toPlan(catalog).getOrNull()!!.operations.single() as EditOperation.RandomFill
        assertEquals(device.fields.keys, operation.fields)
        assertEquals(device.id, operation.presetId)
        assertEquals(7L, operation.seed)
    }

    @Test
    fun `清除：一组都没选时不放行`() {
        val draft = BatchDraft(mode = BatchMode.CLEAR)

        assertFalse(draft.isReady)
        assertEquals(PictError.FIELD_INVALID, draft.toPlan(catalog).failureOrNull()?.error)
    }

    @Test
    fun `清除：选几组就带几组，且不需要预设`() {
        val draft = BatchDraft(mode = BatchMode.CLEAR, clearTargets = setOf(ClearTarget.GPS, ClearTarget.ICC))

        assertTrue(draft.isReady)
        assertFalse(draft.needsPreset)
        val operation = draft.toPlan(catalog).getOrNull()!!.operations.single() as EditOperation.ClearTargets
        assertEquals(setOf(ClearTarget.GPS, ClearTarget.ICC), operation.targets)
    }

    @Test
    fun `覆盖已有值只在套用预设时可用`() {
        assertTrue(BatchDraft(mode = BatchMode.PRESET).supportsOverwrite)
        assertFalse(BatchDraft(mode = BatchMode.RANDOM).supportsOverwrite)
        assertFalse(BatchDraft(mode = BatchMode.CLEAR).supportsOverwrite)
    }

    @Test
    fun `计划默认是 dry-run：拿去做预览不会被人误当成执行`() {
        val plan = BatchDraft(mode = BatchMode.CLEAR, clearTargets = setOf(ClearTarget.GPS))
            .toPlan(catalog).getOrNull()!!

        assertTrue(plan.dryRun)
        assertTrue(plan.backupBeforeOverwrite)
    }

    // ---------- 批量种子口径 ----------

    @Test
    fun `序号 0 不动种子：第一张与单张编辑口径一致`() {
        val plan = EditPlan(listOf(EditOperation.RandomFill(fields = setOf(device.fields.keys.first()), seed = 5L)))

        assertSame(plan, plan.seededFor(0))
    }

    @Test
    fun `随机填充与套用预设按序号错开种子`() {
        val plan = EditPlan(
            listOf(
                EditOperation.RandomFill(fields = setOf(device.fields.keys.first()), seed = 0L, presetId = device.id),
                EditOperation.ApplyPreset(presetId = device.id, seed = 0L),
                EditOperation.JitterGps(seed = 0L),
            ),
        )

        val third = plan.seededFor(3)
        assertEquals(3L, (third.operations[0] as EditOperation.RandomFill).seed)
        assertEquals(3L, (third.operations[1] as EditOperation.ApplyPreset).seed)
        assertEquals(3L, (third.operations[2] as EditOperation.JitterGps).seed)
    }

    @Test
    fun `不带种子的操作原样保留`() {
        val clear = EditOperation.ClearTargets(setOf(ClearTarget.GPS))
        val plan = EditPlan(listOf(clear))

        assertSame(clear, plan.seededFor(9).operations.single())
    }

    @Test
    fun `同一批里两张同源图不会被重掷成同一个值`() = runBlocking {
        val draft = BatchDraft(mode = BatchMode.RANDOM, presetIds = listOf(device.id), seed = 0L)
        val plan = draft.toPlan(catalog).getOrNull()!!

        val targets = (1..5).map { BatchTarget.of("content://pict/$it.jpg", "$it.jpg", ImageFormatHint.JPEG) }
        val reader = InMemorySourceReader(targets.associate { it.uri to source(it.displayName) })
        val preview = BatchPreviewer(reader, catalog).preview(plan, targets)

        val distinct = preview.items.map { item -> item.changes }.distinct()
        assertTrue("五张图的随机结果不该完全一样，实际只有 ${distinct.size} 种", distinct.size >= 2)
    }

    @Test
    fun `换个批次（同种子）结果可复现`() = runBlocking {
        val draft = BatchDraft(mode = BatchMode.RANDOM, presetIds = listOf(device.id), seed = 11L)
        val plan = draft.toPlan(catalog).getOrNull()!!
        val targets = (1..3).map { BatchTarget.of("content://pict/$it.jpg", "$it.jpg", ImageFormatHint.JPEG) }
        val reader = InMemorySourceReader(targets.associate { it.uri to source(it.displayName) })

        val first = BatchPreviewer(reader, catalog).preview(plan, targets)
        val second = BatchPreviewer(reader, catalog).preview(plan, targets)

        assertEquals(first.items.map { it.changes }, second.items.map { it.changes })
    }

    @Test
    fun `随机填充给已有值的字段也会被重掷（不像套用预设那样只填缺失）`() = runBlocking {
        val key = device.fields.keys.first()
        val existing = source("a.jpg").copy(entries = mapOf(key to TagValue.Text("旧值")))
        val draft = BatchDraft(mode = BatchMode.RANDOM, presetIds = listOf(device.id), seed = 3L)
        val plan = draft.toPlan(catalog).getOrNull()!!

        val item = BatchPreviewer(InMemorySourceReader(mapOf(target.uri to existing)), catalog)
            .previewOne(plan, target)

        assertTrue("随机填充应当动到已有字段", item.changes.any { it.key == key })
        assertNotEquals(null, item.changes.firstOrNull { it.key == key }?.after)
    }
}
