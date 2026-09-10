package com.pict.metatool.ui.edit

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑草稿与折叠（docs/07 T2.10）。
 *
 * 「改了又改回来不算未保存」是编辑页的核心规则，所以 [EditDraft.effectiveKeys]
 * 的比较规则被单独钉住；折叠顺序则要保证同一份草稿每次结果一致。
 */
class EditDraftTest {

    private val source = SourceInfo("IMG_0001.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG)

    private val makeKey = TagKey.of("EXIF:Make")
    private val modelKey = TagKey.of("EXIF:Model")
    private val vendorKey = TagKey.of("VENDOR:Custom")

    private fun set(vararg entries: Pair<String, TagValue>): MetadataSet = MetadataSet(
        source,
        entries.associate { (key, value) -> TagKey.of(key) to value },
    )

    @Test
    fun `草稿同时记得改值和清除`() {
        val draft = EditDraft()
            .set(makeKey, TagValue.Text("Apple"))
            .clear(modelKey)

        assertEquals(2, draft.size)
        assertTrue(draft.contains(makeKey))
        assertEquals(DraftValue.Value(TagValue.Text("Apple")), draft.of(makeKey))
        assertEquals(TagValue.Text("Apple"), draft.valueOf(makeKey))
        assertEquals(DraftValue.Cleared, draft.of(modelKey))
        assertNull(draft.valueOf(modelKey))
    }

    @Test
    fun `改了又改回来不算未保存`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))

        assertTrue(EditDraft().set(makeKey, TagValue.Text("Apple")).effectiveKeys(source).isEmpty())
        assertEquals(setOf(makeKey), EditDraft().set(makeKey, TagValue.Text("Pear")).effectiveKeys(source))
        assertEquals(1, EditDraft().set(makeKey, TagValue.Text("Pear")).effectiveCount(source))
    }

    @Test
    fun `清除源里本来就有的字段才算未保存`() {
        val source = set("EXIF:Make" to TagValue.Text("Apple"))

        assertEquals(setOf(makeKey), EditDraft().clear(makeKey).effectiveKeys(source))
        assertTrue(EditDraft().clear(modelKey).effectiveKeys(source).isEmpty())
    }

    @Test
    fun `撤销按键清、撤销全部清空`() {
        val draft = EditDraft()
            .set(makeKey, TagValue.Text("Pear"))
            .set(modelKey, TagValue.Text("M1"))

        assertFalse(draft.revert(makeKey).contains(makeKey))
        assertEquals(1, draft.revert(makeKey).size)
        assertTrue(draft.revertAll().isEmpty)
        assertEquals(0, draft.revertAll().size)
    }

    @Test
    fun `折叠成操作序列并按目录顺序排列`() {
        val draft = EditDraft()
            .set(modelKey, TagValue.Text("b"))
            .set(makeKey, TagValue.Text("a"))
            .clear(vendorKey)

        val operations = draft.toOperations()

        assertEquals(3, operations.size)
        assertEquals(EditOperation.SetField(makeKey, TagValue.Text("a")), operations[0])
        assertEquals(EditOperation.SetField(modelKey, TagValue.Text("b")), operations[1])
        assertEquals(EditOperation.ClearField(vendorKey), operations[2])
    }

    @Test
    fun `清除只折叠成 ClearField`() {
        assertEquals(listOf(EditOperation.ClearField(makeKey)), EditDraft().clear(makeKey).toOperations())
    }

    @Test
    fun `同一份草稿折叠结果稳定`() {
        val draft = EditDraft()
            .set(makeKey, TagValue.Text("a"))
            .set(modelKey, TagValue.Text("b"))

        assertEquals(draft.toOperations(), draft.toOperations())
        assertEquals(draft.toOperations(), draft.toOperations())
    }

    @Test
    fun `折叠成计划默认干跑并先备份`() {
        val plan = EditDraft().set(makeKey, TagValue.Text("a")).toPlan()

        assertTrue(plan.dryRun)
        assertTrue(plan.backupBeforeOverwrite)
        assertEquals(1, plan.operations.size)
        assertEquals(EditOperation.SetField(makeKey, TagValue.Text("a")), plan.operations.single())
        assertFalse(EditDraft().set(makeKey, TagValue.Text("a")).toPlan(dryRun = false).dryRun)
    }
}
