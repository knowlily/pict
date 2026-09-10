package com.pict.metatool.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/06 §3.3「导出」：SAF 建议文件名的规则。
 *
 * 规则错在这里的代价是上设备才发现（描述符不合法、跟源文件撞名、扩展名被截掉），
 * 所以逐条钉住。
 */
class ExportNamingTest {

    @Test
    fun `保留扩展名并在主名后加后缀`() {
        assertEquals("IMG_1234-edited.JPG", ExportNaming.suggest("IMG_1234.JPG"))
        assertEquals("photo-edited.jpg", ExportNaming.suggest("photo.jpg"))
    }

    @Test
    fun `没有扩展名时不硬塞一个`() {
        assertEquals("photo-edited", ExportNaming.suggest("photo"))
    }

    @Test
    fun `多个点只认最后一个作扩展名`() {
        assertEquals("a.b.c-edited.jpg", ExportNaming.suggest("a.b.c.jpg"))
    }

    @Test
    fun `前导点不算扩展名`() {
        assertEquals(".nomedia-edited", ExportNaming.suggest(".nomedia"))
    }

    @Test
    fun `结尾点不算扩展名`() {
        assertEquals("photo-edited", ExportNaming.suggest("photo."))
    }

    @Test
    fun `已经带过后缀的不再叠一层`() {
        assertEquals("photo-edited.jpg", ExportNaming.suggest("photo-edited.jpg"))
        assertEquals("photo-edited", ExportNaming.suggest("photo-edited"))
    }

    @Test
    fun `拿不到原名时给兜底名`() {
        assertEquals("image-edited.jpg", ExportNaming.suggest(null))
        assertEquals("image-edited.jpg", ExportNaming.suggest(""))
        assertEquals("image-edited.jpg", ExportNaming.suggest("   "))
    }

    @Test
    fun `超长名先截主名保住扩展名`() {
        val long = "x".repeat(200) + ".jpeg"
        val result = ExportNaming.suggest(long)

        assertEquals(ExportNaming.MAX_LENGTH, result.length)
        assertTrue(result.endsWith("-edited.jpeg"))
        assertTrue(result.startsWith("x"))
    }
}
