package com.pict.metatool.domain.batch

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.ClearSegment
import com.pict.metatool.domain.preset.MetadataRandomizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.5：预览结果之上的统计（表格的过滤与统计区都吃这几个派生量）。
 *
 * 这里手工拼 [ItemPreview]——引擎怎么产出它们由 [BatchPreviewerTest] 覆盖，
 * 本文件只问一件事：从逐项结果算出来的数对不对。
 */
class BatchPreviewTest {

    private fun change(
        key: String,
        before: TagValue? = null,
        after: TagValue? = TagValue.Text("x"),
    ) = FieldChange(TagKey.of(key), before, after)

    private fun item(
        name: String,
        changes: List<FieldChange> = emptyList(),
        blocked: PictError? = null,
        skipped: List<MetadataRandomizer.Skip> = emptyList(),
        segments: Set<ClearSegment> = emptySet(),
    ) = ItemPreview(
        target = BatchTarget.of("content://pict/$name", name, ImageFormatHint.JPEG),
        changes = changes,
        skipped = skipped,
        segmentClears = segments,
        blocked = blocked,
    )

    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")

    @Test
    fun `changedFiles 与 totalChanges 只统计真正有变化的项`() {
        val preview = BatchPreview(
            listOf(
                item("a.jpg", changes = listOf(change("EXIF:Make"), change("EXIF:Model"))),
                item("b.jpg"),
                item("c.jpg", blocked = PictError.IO_OPEN),
            ),
        )

        assertEquals(1, preview.changedFiles)
        assertEquals(2, preview.totalChanges)
        assertEquals(1, preview.blockedFiles)
        assertEquals("没变化又没被拦下的才是空操作", 1, preview.noopItems.size)
    }

    @Test
    fun `过滤视图按「有变化 被拦下 空操作」三划分，互不重叠`() {
        val preview = BatchPreview(
            listOf(
                item("a.jpg", changes = listOf(change("EXIF:Make"))),
                item("b.jpg"),
                item("c.jpg", blocked = PictError.ENCODE_UNSUPPORTED),
            ),
        )

        assertEquals(3, preview.items.size)
        assertEquals(
            preview.items.size,
            preview.changedItems.size + preview.blockedItems.size + preview.noopItems.size,
        )
        assertTrue(preview.blockedItems.all { it.isBlocked })
        assertTrue(preview.changedItems.none { it.isBlocked })
    }

    @Test
    fun `字段命中次数按次数降序，同次数按字段名`() {
        val preview = BatchPreview(
            listOf(
                item("a.jpg", changes = listOf(change("EXIF:Model"), change("EXIF:Make"))),
                item("b.jpg", changes = listOf(change("EXIF:Make"))),
            ),
        )

        assertEquals(listOf(make to 2, model to 1), preview.keyHistogram())
    }

    @Test
    fun `被拦下的错误码与被跳过的理由一起归类`() {
        val preview = BatchPreview(
            listOf(
                item("a.jpg", blocked = PictError.IO_OPEN),
                item("b.jpg", blocked = PictError.IO_OPEN),
                item(
                    "c.jpg",
                    skipped = listOf(MetadataRandomizer.Skip(make, "预设里没有这个字段")),
                ),
            ),
        )

        assertEquals(
            listOf(PictError.IO_OPEN.code to 2, "预设里没有这个字段" to 1),
            preview.skipReasons(),
        )
    }

    @Test
    fun `变化类型由前后值推导`() {
        assertEquals(ChangeKind.ADDED, change("EXIF:Make", before = null, after = TagValue.Text("x")).kind)
        assertEquals(
            ChangeKind.MODIFIED,
            change("EXIF:Make", before = TagValue.Text("a"), after = TagValue.Text("b")).kind,
        )
        assertEquals(ChangeKind.REMOVED, change("EXIF:Make", before = TagValue.Text("a"), after = null).kind)
    }

    @Test
    fun `段级清除另算一格，不进字段命中统计`() {
        val preview = BatchPreview(
            listOf(
                item("a.jpg", changes = listOf(change("EXIF:Make")), segments = setOf(ClearSegment.THUMBNAIL)),
                item("b.jpg", segments = setOf(ClearSegment.ICC_PROFILE)),
            ),
        )

        assertEquals(1, preview.totalChanges)
        assertEquals(2, preview.segmentClearCount)
        assertEquals(listOf(make to 1), preview.keyHistogram())
    }

    @Test
    fun `空批次不是错误，只是什么都还没选`() {
        val preview = BatchPreview(emptyList())

        assertEquals(0, preview.items.size)
        assertEquals(0, preview.changedFiles)
        assertTrue(preview.keyHistogram().isEmpty())
        assertTrue(preview.skipReasons().isEmpty())
        assertFalse(preview.hasChanges)
    }

    @Test
    fun `字段顺序表把未收录的键排在最后`() {
        assertTrue(FieldCatalog.order(make) < FieldCatalog.order(TagKey.of("FOO:Bar")))
        assertEquals(Int.MAX_VALUE, FieldCatalog.order(TagKey.of("FOO:Bar")))
    }
}
