package com.pict.metatool.ui.edit

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页状态迁移（docs/07 T2.10、docs/06 §3.3）。
 *
 * 「应用 N」上的 N 必须是**与源相比真正变化的项数**，不是草稿条数；只读字段照样
 * 列出来但不可编辑；diff 预览与落盘共用同一份折叠结果。这几条都在这里锁住。
 */
class EditUiStateTest {

    private val source = SourceInfo("IMG_0001.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG)

    private val makeKey = TagKey.of("EXIF:Make")
    private val modelKey = TagKey.of("EXIF:Model")
    private val widthKey = TagKey.of("EXIF:ImageWidth")
    private val dateTimeKey = TagKey.of("EXIF:DateTime")
    private val vendorKey = TagKey.of("VENDOR:Custom")

    private fun loaded(vararg entries: Pair<String, TagValue>): EditUiState = EditUiState()
        .withLoading("content://media/1")
        .withLoaded(
            source,
            MetadataSet(source, entries.associate { (key, value) -> TagKey.of(key) to value }),
        )

    private fun rowsOf(state: EditUiState, key: TagKey) =
        state.sections.flatMap { it.rows }.single { it.key == key }

    @Test
    fun `载入后按分组列出字段`() {
        val state = loaded(
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:ImageWidth" to TagValue.IntValue(4000),
            "EXIF:DateTime" to TagValue.Timestamp(LocalDateTime.of(2026, 9, 10, 15, 30)),
        )

        assertFalse(state.isLoading)
        assertEquals("IMG_0001.jpg", state.fileName)
        assertTrue(state.hasMetadata)
        assertEquals(3, state.sections.size)
        assertTrue(state.sections.map { it.title }.contains("相机"))

        val order = state.sections.mapNotNull { it.group }.map { FieldGroup.entries.indexOf(it) }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `未登记字段排最后且只看不改`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"), "VENDOR:Custom" to TagValue.Text("x"))

        val last = state.sections.last()
        assertEquals(EditUiState.UNREGISTERED_TITLE, last.title)

        val row = last.rows.single()
        assertEquals(vendorKey, row.key)
        assertFalse(row.editable)
        assertEquals(EditUiState.UNREGISTERED_NOTE, row.note)
    }

    @Test
    fun `只读字段照样列出来但不可编辑`() {
        val row = rowsOf(loaded("EXIF:ImageWidth" to TagValue.IntValue(4000)), widthKey)

        assertFalse(row.editable)
        assertTrue(row.display.contains("4000"))
        assertEquals(FieldCatalog.spec(widthKey)?.writability?.label, row.note)
    }

    @Test
    fun `改了值才计入未保存`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .commitEditor()

        assertNull(state.editing)
        assertEquals(1, state.dirtyCount)
        assertTrue(state.canApply)

        val row = rowsOf(state, makeKey)
        assertTrue(row.dirty)
        assertTrue(row.display.contains("Pear"))
    }

    @Test
    fun `改回原值就不算未保存`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Apple")
            .commitEditor()

        assertEquals(0, state.dirtyCount)
        assertFalse(state.isDirty)
        assertFalse(state.canApply)
    }

    @Test
    fun `留空等于清除该字段`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("   ")
            .commitEditor()

        assertEquals(1, state.dirtyCount)
        assertEquals(DraftValue.Cleared, state.draft.of(makeKey))
        assertEquals(EditUiState.CLEARED_DISPLAY, rowsOf(state, makeKey).display)
    }

    @Test
    fun `输入不合法时弹层不关并给出原因`() {
        val state = loaded("EXIF:DateTime" to TagValue.Timestamp(LocalDateTime.of(2026, 9, 10, 15, 30)))
            .openEditor(dateTimeKey)
            .withInput("昨天晚上")
            .commitEditor()

        assertEquals(dateTimeKey, state.editing)
        assertNotNull(state.inputError)
        assertEquals(0, state.dirtyCount)
    }

    @Test
    fun `弹层预填的是原始字面量`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple")).openEditor(makeKey)

        assertEquals(makeKey, state.editing)
        assertEquals("Apple", state.input)
        assertEquals(InputKind.TEXT, state.editingKind)
        assertEquals(FieldCatalog.spec(makeKey)?.label, state.editingLabel)
        assertTrue(state.editingHint.isNotEmpty())
        assertFalse(state.editingChanged)
    }

    @Test
    fun `关掉弹层会丢掉没提交的输入`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .closeEditor()

        assertNull(state.editing)
        assertEquals("", state.input)
        assertEquals(0, state.dirtyCount)
    }

    @Test
    fun `撤销单键与全部撤销`() {
        val dirty = loaded(
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:Model" to TagValue.Text("M1"),
        ).openEditor(makeKey).withInput("Pear").commitEditor()

        assertEquals(1, dirty.dirtyCount)
        assertEquals(0, dirty.revertField(makeKey).dirtyCount)
        assertEquals(0, dirty.revertAll().dirtyCount)
        assertFalse(dirty.revertAll().isDirty)
        assertFalse(dirty.revertAll().showPreview)
    }

    @Test
    fun `搜索过滤分组`() {
        val filtered = loaded("EXIF:Make" to TagValue.Text("Apple"), "EXIF:Model" to TagValue.Text("M1"))
            .withQuery("EXIF:Make")

        val section = filtered.visibleSections.single()
        assertEquals(makeKey, section.rows.single().key)
        assertTrue(filtered.sections.any { section -> section.rows.any { it.key == modelKey } })
    }

    @Test
    fun `搜索时给出本文件还没有的可编辑字段`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple")).withQuery("版权")

        assertTrue(state.additions.isNotEmpty())
        assertTrue(state.additions.all { it.canEdit })
        assertTrue(state.additions.none { it.key == makeKey })
    }

    @Test
    fun `没搜索时不给可添加字段`() {
        assertTrue(loaded("EXIF:Make" to TagValue.Text("Apple")).additions.isEmpty())
    }

    @Test
    fun `diff 行与折叠结果同源`() {
        val changed = loaded(
            "EXIF:Make" to TagValue.Text("Apple"),
            "EXIF:Model" to TagValue.Text("M1"),
        ).openEditor(makeKey).withInput("Pear").commitEditor()

        val row = changed.diffRows.single()
        assertEquals(makeKey, row.key)
        assertEquals(EditDiffKind.CHANGED, row.kind)
        assertTrue(row.before!!.contains("Apple"))
        assertTrue(row.after!!.contains("Pear"))
        assertTrue(row.summary.contains("Pear"))
        assertEquals(TagValue.Text("Pear"), changed.proposed?.get(makeKey))
        assertNull(changed.planFailure)

        val cleared = changed.openEditor(modelKey).withInput("").commitEditor()
            .diffRows.single { it.key == modelKey }
        assertEquals(EditDiffKind.CLEARED, cleared.kind)
        assertNull(cleared.after)
        assertTrue(cleared.summary.contains("清除"))
    }

    @Test
    fun `不能原地写的格式不给应用`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .commitEditor()
            .copy(hasInPlaceWriter = false)

        assertTrue(state.isDirty)
        assertFalse(state.canApply)
    }

    @Test
    fun `源只读仍可应用但改走另存`() {
        // 相册选择器给的 URI 只有读权限：原图写不了，但**不是不能改**——按 docs/05 §6 降级另存。
        // 这里把设置开着（inPlaceAllowed = true），单独把「来源只读」这一关拎出来测。
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .commitEditor()
            .copy(sourceWritable = false, inPlaceAllowed = true)

        assertTrue(state.isDirty)
        assertFalse(state.canWriteInPlace)
        assertTrue(state.appliesBySaveAs)
        assertTrue(state.canApply)
        assertTrue(state.canExport)
        assertTrue(state.showsSaveAsReason)
        assertTrue(state.saveAsReasonIsReadOnly)
    }

    @Test
    fun `关着不直接改原文件时可写来源也走另存`() {
        // 设置里「直接改动原文件」默认关：来源明明可写（文件管理器那种），也不写它。
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .commitEditor()
            .copy(sourceWritable = true, inPlaceAllowed = false)

        assertTrue(state.isDirty)
        assertFalse(state.canWriteInPlace)
        assertTrue(state.appliesBySaveAs)
        // 照样能落盘：落点换成新文件而已
        assertTrue(state.canApply)
        assertTrue(state.showsSaveAsReason)
        // 原因不是权限，别把横幅写成「这张图只有读权限」
        assertFalse(state.saveAsReasonIsReadOnly)
    }

    @Test
    fun `打开直接改动后可写来源照旧原地应用`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .commitEditor()
            .copy(sourceWritable = true, inPlaceAllowed = true)

        assertTrue(state.canWriteInPlace)
        assertFalse(state.appliesBySaveAs)
        assertTrue(state.canApply)
        assertFalse(state.showsSaveAsReason)
    }

    @Test
    fun `格式不能原地写时不给横幅`() {
        // HEIF 那条路已经有一块「先另存成 JPEG/PNG」的提示，横幅再来一条是重复解释。
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .copy(hasInPlaceWriter = false, sourceWritable = true, inPlaceAllowed = false)

        assertFalse(state.showsSaveAsReason)
        assertFalse(state.canWriteInPlace)
    }

    @Test
    fun `另存信号一次性消费`() {
        assertFalse(EditUiState().saveAsFallback)
        val pending = EditUiState().withSaveAsFallback()
        assertTrue(pending.saveAsFallback)
        assertFalse(pending.consumeSaveAsFallback().saveAsFallback)
    }

    @Test
    fun `另存成功后草稿清空且基线换成副本里的值`() {
        // 源只读时「另存」就是保存本身：存成了就不该还挂着「有 N 项未保存」。
        // 基线也得跟着副本走——只清草稿的话，行上会回落成源值，看着像改动被丢了。
        val edited = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .commitEditor()
            .copy(sourceWritable = false)

        assertTrue(edited.isDirty)
        assertTrue(rowsOf(edited, makeKey).dirty)

        val saved = edited.withSavedAsCopy(
            MetadataSet(source, mapOf(makeKey to TagValue.Text("Pear"))),
            emptyMap(),
            "写入 1 项；匹配 1 项",
        )

        assertFalse(saved.isDirty)
        assertEquals(0, saved.dirtyCount)
        assertFalse(saved.saveAsFallback)
        assertFalse(saved.showPreview)
        assertEquals(TagValue.Text("Pear"), saved.metadata?.get(makeKey))
        assertEquals("写入 1 项；匹配 1 项", saved.verifySummary)
        assertFalse(rowsOf(saved, makeKey).dirty)
        assertTrue(rowsOf(saved, makeKey).display.contains("Pear"))
    }

    @Test
    fun `读盘失败后可重试且不可应用`() {
        val state = EditUiState()
            .withLoading("content://media/1")
            .withError(PictError.UNKNOWN, "读不出来")

        assertFalse(state.isLoading)
        assertEquals(PictError.UNKNOWN, state.error)
        assertEquals("读不出来", state.errorDetail)
        assertFalse(state.canApply)
        assertEquals("content://media/1", state.uri)
    }

    @Test
    fun `写完草稿清空并留下校验摘要`() {
        val state = loaded("EXIF:Make" to TagValue.Text("Apple"))
            .openEditor(makeKey)
            .withInput("Pear")
            .commitEditor()
            .withApplying()
            .withApplied("已写入 1 项，校验通过")

        assertFalse(state.isApplying)
        assertEquals(0, state.dirtyCount)
        assertFalse(state.showPreview)
        assertEquals("已写入 1 项，校验通过", state.verifySummary)
    }

    @Test
    fun `提示消息一次性消费`() {
        val shown = EditUiState().withMessage("写入失败：磁盘满", isError = true)

        assertEquals("写入失败：磁盘满", shown.message?.text)
        assertTrue(shown.message!!.isError)
        assertNull(shown.consumeMessage().message)
    }

    @Test
    fun `预览开关与放弃确认`() {
        assertTrue(EditUiState().openPreview().showPreview)
        assertFalse(EditUiState().openPreview().closePreview().showPreview)
        assertTrue(EditUiState().requestDiscard().confirmDiscard)
        assertFalse(EditUiState().requestDiscard().cancelDiscard().confirmDiscard)
    }

    @Test
    fun `未登记键也能作为字段写进草稿`() {
        val state = loaded().openEditor(vendorKey)

        assertEquals(InputKind.TEXT, state.editingKind)
        assertEquals("文本", state.editingHint)
        assertEquals("VENDOR:Custom", state.editingLabel)

        val committed = state.withInput("hello").commitEditor()

        assertEquals(TagValue.Text("hello"), committed.draft.valueOf(vendorKey))
        assertEquals(1, committed.dirtyCount)
        assertTrue(committed.canApply)
    }
}
