package com.pict.metatool.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自选底色（FR-38 续）。
 *
 * 规则全在纯函数里（不碰 Android、不碰盘），深浅两种主题、认不出的色号这些边界都能在 JVM 上
 * 钉住——「深色下够不够暗」靠肉眼看截图是钉不住的。
 */
class BackdropTest {

    @Test
    fun `自动不给颜色，交给主题自己`() {
        assertNull(backdropArgbFor(AppSettings.BACKGROUND_AUTO, dark = false))
        assertNull(backdropArgbFor(AppSettings.BACKGROUND_AUTO, dark = true))
    }

    @Test
    fun `浅色主题原样用，并且一律不透明`() {
        val mint = Backdrop.MINT.argb

        assertEquals(mint, backdropArgbFor(mint, dark = false))
        // 半透明的色号补成不透明：底色透出壁纸比纯色更难看
        assertEquals(mint, backdropArgbFor(mint and 0x00FFFFFFL, dark = false))
    }

    @Test
    fun `深色主题下压暗，还留着一点色相`() {
        val light = backdropArgbFor(Backdrop.MINT.argb, dark = false)!!
        val dark = backdropArgbFor(Backdrop.MINT.argb, dark = true)!!

        assertTrue("深色下要暗得多", channelSum(dark) < channelSum(light) / 3)
        assertEquals("不透明", 0xFFL, (dark shr 24) and 0xFF)
        assertTrue("别压成纯黑（卡片还要浮得起来）", channelSum(dark) > 0)
        assertTrue("同色相：仍带一点点彩", channelSpread(dark) >= 6)
    }

    @Test
    fun `调色板每一项在深色下都还能用`() {
        Backdrop.entries.filter { it != Backdrop.AUTO }.forEach { option ->
            val dark = backdropArgbFor(option.argb, dark = true)!!

            assertEquals("${option.name} 应该不透明", 0xFFL, (dark shr 24) and 0xFF)
            assertTrue("${option.name} 深色下要够暗", channelSum(dark) < 200)
            assertTrue("${option.name} 深色下别是纯黑", channelSum(dark) > 0)
        }
    }

    @Test
    fun `认不出来的色号当自动`() {
        assertEquals(Backdrop.AUTO, Backdrop.fromArgb(0xFF123456L))
        assertEquals(Backdrop.AUTO, Backdrop.fromArgb(AppSettings.BACKGROUND_AUTO))
        assertEquals(Backdrop.SKY, Backdrop.fromArgb(Backdrop.SKY.argb))
    }
}

private fun channelSum(argb: Long): Int =
    (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)).toInt()

private fun channelSpread(argb: Long): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (maxOf(r, g, b) - minOf(r, g, b)).toInt()
}
