package com.pict.metatool.data.batch

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.4：图库那份属性快照 → 批量目标。
 *
 * 只测纯函数那一半（[SafBatchTargets.targetOf]）——查 `ContentResolver` 的那半要真机，
 * 由 `tools` 里的真机走查覆盖。这里的断言都冲着真机上踩到的那个坑去：
 * 名字不能是 documentId，格式/可写位不能丢，否则预览会把能写的图判成「不支持」。
 */
class SafBatchTargetsTest {

    private fun item(
        name: String = "01-canon-40d.jpg",
        mime: String? = "image/jpeg",
        size: Long? = 157_184L,
        format: ImageFormatHint = ImageFormatHint.JPEG,
        writable: Boolean = true,
        origin: ImageItem.Origin = ImageItem.Origin.FILE_PICKER,
    ) = ImageItem(
        uri = "content://com.android.providers.media.documents/document/$name",
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        lastModified = 1_700_000_000_000L,
        format = format,
        writable = writable,
        origin = origin,
    )

    @Test
    fun `名称与地址照搬图库快照，不是 documentId 末段`() {
        val target = SafBatchTargets.targetOf(item())

        assertEquals("01-canon-40d.jpg", target.displayName)
        assertEquals(
            "content://com.android.providers.media.documents/document/01-canon-40d.jpg",
            target.uri,
        )
    }

    @Test
    fun `格式与大小带过去，预览才判得出能不能原地写`() {
        val target = SafBatchTargets.targetOf(item(mime = "image/heif", format = ImageFormatHint.HEIF, size = 4_096L))

        assertEquals(ImageFormatHint.HEIF, target.format)
        assertEquals(4_096L, target.info.sizeBytes)
        assertEquals("image/heif", target.info.mimeType)
    }

    @Test
    fun `只读快照原样带过去，别把只读来源说成能写`() {
        assertTrue(SafBatchTargets.targetOf(item()).writable)
        assertFalse(SafBatchTargets.targetOf(item(writable = false)).writable)
    }

    @Test
    fun `MIME 与大小取不到也不编：留空，让读取时给准确错误`() {
        val target = SafBatchTargets.targetOf(item(mime = null, size = null, format = ImageFormatHint.UNKNOWN))

        assertNull(target.info.mimeType)
        assertNull(target.info.sizeBytes)
        assertEquals("未知扩展名仍按图片看待，只是格式留未知", ImageFormatHint.UNKNOWN, target.format)
    }

    @Test
    fun `批量目标只认地址与来源，图是怎么进来的不影响它`() {
        val fromFolder = SafBatchTargets.targetOf(item(origin = ImageItem.Origin.FOLDER_SCAN))

        assertEquals(SafBatchTargets.targetOf(item()), fromFolder)
    }
}
