package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 写入计划的纯数据测试。
 *
 * 真正落盘（`setAttribute` + `saveAttributes`）只能在设备上验证：ExifInterface 内部用
 * `android.util.Pair` 传值，JVM 单测里那个 Pair 是空实现。设备侧见 androidTest 下的
 * ExifMetadataStoreWriteInstrumentedTest。
 */
class ExifWritePlanTest {

    private val store = ExifMetadataStore()
    private val info = SourceInfo("a.jpg", "image/jpeg", 1000L, ImageFormatHint.JPEG)

    private fun target(vararg entries: Pair<TagKey, TagValue>) =
        MetadataSet(info, linkedMapOf(*entries))

    @Test
    fun `改值生成赋值项`() {
        val plan = store.planWrites(emptyMap(), target(MAKE to TagValue.Text("PictTool")))

        assertEquals("PictTool", plan.assignments[MAKE])
        assertTrue(plan.removals.isEmpty())
        assertTrue(plan.dropped.isEmpty())
    }

    @Test
    fun `目标里没有的键进入删除集合`() {
        val current = mapOf(
            MAKE to TagValue.Text("Canon"),
            MODEL to TagValue.Text("EOS 40D"),
        )

        val plan = store.planWrites(current, target(MODEL to TagValue.Text("EOS 40D")))

        assertEquals(setOf(MAKE), plan.removals)
        assertEquals("EOS 40D", plan.assignments[MODEL])
    }

    @Test
    fun `目录外的键写不了记入丢弃`() {
        val xmpKey = TagKey.of("XMP:dc:title")

        val plan = store.planWrites(emptyMap(), target(xmpKey to TagValue.Text("标题")))

        assertEquals(setOf(xmpKey), plan.dropped)
        assertTrue(plan.assignments.isEmpty())
    }

    @Test
    fun `二进制值不写回记入丢弃`() {
        val plan = store.planWrites(emptyMap(), target(MAKER_NOTE to TagValue.Binary("0A1B", 2)))

        assertEquals(setOf(MAKER_NOTE), plan.dropped)
        assertTrue(plan.assignments.isEmpty())
    }

    @Test
    fun `丢弃的键与删除集合互不干扰`() {
        val current = mapOf(MAKER_NOTE to TagValue.Text("原始"))
        val xmpKey = TagKey.of("XMP:dc:title")

        val plan = store.planWrites(current, target(xmpKey to TagValue.Text("标题")))

        // 目标即全量：文件里有而目标里没有的 MakerNote 会被删掉
        assertEquals(setOf(MAKER_NOTE), plan.removals)
        assertEquals(setOf(xmpKey), plan.dropped)
    }

    @Test
    fun `分数与时间按 EXIF 字面量序列化`() {
        val fNumber = TagKey.of("EXIF:FNumber")
        val dateTime = TagKey.of("EXIF:DateTime")

        val plan = store.planWrites(
            emptyMap(),
            target(
                fNumber to TagValue.RationalValue(Rational(28, 10)),
                dateTime to TagValue.Timestamp(LocalDateTime.of(2026, 9, 9, 21, 30, 0)),
            ),
        )

        assertEquals("28/10", plan.assignments[fNumber])
        assertEquals("2026:09:09 21:30:00", plan.assignments[dateTime])
    }

    @Test
    fun `写入能力只覆盖 JPEG PNG WebP`() {
        fun infoOf(format: ImageFormatHint) = SourceInfo("x", null, null, format)

        assertTrue(store.canWrite(infoOf(ImageFormatHint.JPEG)))
        assertTrue(store.canWrite(infoOf(ImageFormatHint.PNG)))
        assertTrue(store.canWrite(infoOf(ImageFormatHint.WEBP)))
        assertFalse(store.canWrite(infoOf(ImageFormatHint.HEIF)))
        assertFalse(store.canWrite(infoOf(ImageFormatHint.TIFF)))
        // 读能力比写能力宽
        assertTrue(store.supports(infoOf(ImageFormatHint.HEIF)))
    }

    private companion object {
        val MAKE = TagKey.of("EXIF:Make")
        val MODEL = TagKey.of("EXIF:Model")
        val MAKER_NOTE = TagKey.of("EXIF:MakerNote")
    }
}
