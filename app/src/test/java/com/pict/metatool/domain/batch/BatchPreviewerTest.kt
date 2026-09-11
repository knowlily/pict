package com.pict.metatool.domain.batch

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan
import com.pict.metatool.domain.preset.PresetTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.5 / docs/01 FR-32：批量 dry-run 预览引擎。
 *
 * 用 [InMemorySourceReader] 把「怎么读文件」挡在外面——预览的正确性全在
 * 「读到的源 + 计划 → 逐项变更」这条纯计算链上，真文件与真 ContentResolver 留给 androidTest。
 */
class BatchPreviewerTest {

    private val make = PresetTestSupport.key("EXIF:Make")
    private val model = PresetTestSupport.key("EXIF:Model")
    private val artist = PresetTestSupport.key("EXIF:Artist")
    private val rating = PresetTestSupport.key("EXIF:Rating")

    private val device = PresetTestSupport.preset("device.iphone-16-pro")
    private val catalog = PresetTestSupport.catalog(device)

    private val jpeg = BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.JPEG)
    private val heic = BatchTarget.of("content://pict/b.heic", "b.heic", ImageFormatHint.HEIF)

    private fun sourceOf(name: String, vararg entries: Pair<TagKey, TagValue>): MetadataSet =
        MetadataSet(
            source = SourceInfo(name, "image/jpeg", 2_048L, ImageFormatHint.JPEG),
            entries = entries.toMap(),
        )

    private fun reader(
        vararg sources: Pair<BatchTarget, MetadataSet>,
        writable: Set<String> = emptySet(),
    ): InMemorySourceReader =
        InMemorySourceReader(sources.associate { it.first.uri to it.second }, writable)

    private fun presetPlan(seed: Long = 7L, overwrite: Boolean = false) =
        EditPlan(listOf(EditOperation.ApplyPreset(device.id, overwriteExisting = overwrite, seed = seed)))

    // ---------- 逐项结果 ----------

    @Test
    fun `预览逐项给出变更，且顺序与传入目标一致`() = runBlocking {
        val previewer = BatchPreviewer(
            reader(jpeg to sourceOf("a.jpg"), heic to sourceOf("b.heic"), writable = setOf(jpeg.uri, heic.uri)),
            catalog,
        )

        val preview = previewer.preview(presetPlan(), listOf(jpeg, heic))

        assertEquals("顺序必须与传入一致", listOf(jpeg.uri, heic.uri), preview.items.map { it.target.uri })
        assertEquals(2, preview.changedFiles)
        assertTrue("预设填充应至少写到制造商", preview.items.first().changes.any { it.key == make })
    }

    @Test
    fun `变更按附录 A 的字段顺序排列`() = runBlocking {
        val previewer = BatchPreviewer(reader(jpeg to sourceOf("a.jpg"), writable = setOf(jpeg.uri)), catalog)
        // 故意倒着写：先 Rating（靠后）后 Artist（靠前）
        val plan = EditPlan(
            listOf(
                EditOperation.SetField(rating, TagValue.IntValue(5L)),
                EditOperation.SetField(artist, TagValue.Text("张三")),
            ),
        )

        val item = previewer.previewOne(plan, jpeg)

        assertTrue(FieldCatalog.order(artist) < FieldCatalog.order(rating))
        assertEquals(listOf(artist, rating), item.changes.map { it.key })
    }

    @Test
    fun `执行后什么都没变的目标既不算变化也不算失败`() = runBlocking {
        val source = sourceOf("a.jpg", make to TagValue.Text("Apple"))
        val previewer = BatchPreviewer(reader(jpeg to source, writable = setOf(jpeg.uri)), catalog)
        val plan = EditPlan(listOf(EditOperation.SetField(make, TagValue.Text("Apple"))))

        val item = previewer.previewOne(plan, jpeg)

        assertFalse(item.isBlocked)
        assertTrue(item.isNoop)
        assertEquals(0, item.changeCount)
    }

    @Test
    fun `清除的字段在预览里是清除而不是修改`() = runBlocking {
        val source = sourceOf("a.jpg", make to TagValue.Text("Apple"))
        val previewer = BatchPreviewer(reader(jpeg to source, writable = setOf(jpeg.uri)), catalog)

        val item = previewer.previewOne(EditPlan(listOf(EditOperation.ClearField(make))), jpeg)
        val change = item.changes.single()

        assertEquals(ChangeKind.REMOVED, change.kind)
        assertNotNull("清除要能看到原值", change.before)
        assertNull(change.after)
    }

    @Test
    fun `新增的字段在预览里是新增`() = runBlocking {
        val previewer = BatchPreviewer(reader(jpeg to sourceOf("a.jpg"), writable = setOf(jpeg.uri)), catalog)

        val item = previewer.previewOne(
            EditPlan(listOf(EditOperation.SetField(artist, TagValue.Text("张三")))),
            jpeg,
        )

        assertEquals(ChangeKind.ADDED, item.changes.single().kind)
        assertNull(item.changes.single().before)
    }

    @Test
    fun `只填缺失时被保留的键点名，且不计入变化`() = runBlocking {
        val source = sourceOf("a.jpg", model to TagValue.Text("我的机器"))
        val previewer = BatchPreviewer(reader(jpeg to source, writable = setOf(jpeg.uri)), catalog)

        val item = previewer.previewOne(presetPlan(), jpeg)

        assertTrue("已有机型应被保留", model in item.keptKeys)
        assertFalse("保留的键不该出现在变更里", item.changes.any { it.key == model })
        assertTrue(item.changes.any { it.key == make })
    }

    @Test
    fun `段级清除单独计数，不混进字段变更数`() = runBlocking {
        val source = sourceOf("a.jpg", make to TagValue.Text("Apple"))
        val previewer = BatchPreviewer(reader(jpeg to source, writable = setOf(jpeg.uri)), catalog)
        val plan = EditPlan(listOf(EditOperation.ClearTargets(setOf(ClearTarget.THUMBNAIL))))

        val preview = previewer.preview(plan, listOf(jpeg))

        assertTrue("段级意图要带出来", preview.items.single().segmentClears.isNotEmpty())
        assertEquals("段不是字段", 0, preview.totalChanges)
        assertEquals(1, preview.segmentClearCount)
    }

    @Test
    fun `同一种子两次预览的变更完全一致`() = runBlocking {
        val previewer = BatchPreviewer(reader(jpeg to sourceOf("a.jpg"), writable = setOf(jpeg.uri)), catalog)

        val first = previewer.preview(presetPlan(seed = 42L), listOf(jpeg))
        val second = previewer.preview(presetPlan(seed = 42L), listOf(jpeg))

        assertEquals(first.items.single().changes, second.items.single().changes)
    }

    // ---------- 被拦下的项 ----------

    @Test
    fun `不支持原地写的格式被标成不支持，而不是执行失败`() = runBlocking {
        val previewer = BatchPreviewer(reader(heic to sourceOf("b.heic"), writable = setOf(jpeg.uri)), catalog)

        val item = previewer.previewOne(presetPlan(), heic)

        assertEquals(PictError.ENCODE_UNSUPPORTED, item.blocked)
        assertTrue("格式不支持 ≠ 读不了", item.readable)
        assertTrue(item.blockedDetail!!.contains("不支持原地写"))
        assertTrue(item.changes.isEmpty())
    }

    @Test
    fun `可写判定先于读取：不支持的项连一次 I-O 都不做`() = runBlocking {
        val reader = reader(heic to sourceOf("b.heic"), writable = setOf(jpeg.uri))

        BatchPreviewer(reader, catalog).previewOne(presetPlan(), heic)

        assertTrue("不支持的项不该被读取", reader.readCounts.isEmpty())
    }

    @Test
    fun `读不出来的目标被标成不可读`() = runBlocking {
        // 预置里没有任何内容：读 URI 必然失败
        val previewer = BatchPreviewer(reader(writable = setOf(jpeg.uri)), catalog)

        val item = previewer.previewOne(presetPlan(), jpeg)

        assertEquals(PictError.IO_OPEN, item.blocked)
        assertFalse("连源元数据都没读到", item.readable)
    }

    @Test
    fun `计划里的预设不存在时整项被拦下并带上错误码`() = runBlocking {
        val previewer = BatchPreviewer(reader(jpeg to sourceOf("a.jpg"), writable = setOf(jpeg.uri)), catalog)
        val plan = EditPlan(listOf(EditOperation.ApplyPreset("no.such.preset", seed = 1L)))

        val item = previewer.previewOne(plan, jpeg)

        assertEquals(PictError.FIELD_INVALID, item.blocked)
        assertTrue(item.readable)
    }

    @Test
    fun `随机填充没给预设时被拦下，而不是随便编值`() = runBlocking {
        val previewer = BatchPreviewer(reader(jpeg to sourceOf("a.jpg"), writable = setOf(jpeg.uri)), catalog)
        val plan = EditPlan(listOf(EditOperation.RandomFill(fields = setOf(make), seed = 1L)))

        val item = previewer.previewOne(plan, jpeg)

        assertEquals(PictError.FIELD_INVALID, item.blocked)
    }

    // ---------- 进度与只读保证 ----------

    @Test
    fun `进度回调每项只报一次且总数正确`() = runBlocking {
        val previewer = BatchPreviewer(
            reader(jpeg to sourceOf("a.jpg"), heic to sourceOf("b.heic"), writable = setOf(jpeg.uri, heic.uri)),
            catalog,
        )
        val calls = mutableListOf<Pair<Int, Int>>()

        previewer.preview(presetPlan(), listOf(jpeg, heic), onItem = { done, total, _ ->
            calls += done to total
        })

        assertEquals(listOf(1 to 2, 2 to 2), calls)
    }

    @Test
    fun `预览每个目标只读一次，且不写回任何东西`() = runBlocking {
        val source = sourceOf("a.jpg", make to TagValue.Text("Apple"))
        val before = source.entries
        val reader = reader(jpeg to source, writable = setOf(jpeg.uri))

        BatchPreviewer(reader, catalog).preview(presetPlan(), listOf(jpeg))

        assertEquals(mapOf(jpeg.uri to 1), reader.readCounts)
        assertEquals("源集合不该被改动", before, source.entries)
    }

    @Test
    fun `预览结果带上真实读到的来源信息`() = runBlocking {
        // 选择器给的 MIME / 大小常缺失，预览要以读到的为准
        val declared = BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.UNKNOWN)
        val previewer = BatchPreviewer(reader(declared to sourceOf("a.jpg"), writable = setOf(declared.uri)), catalog)

        val item = previewer.previewOne(presetPlan(), declared)

        assertEquals("a.jpg", item.target.displayName)
        assertEquals(ImageFormatHint.JPEG, item.target.format)
    }
}
