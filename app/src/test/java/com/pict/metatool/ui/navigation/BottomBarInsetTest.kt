package com.pict.metatool.ui.navigation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pict.metatool.ui.theme.PictSpacing

/**
 * 悬浮底栏的尺寸约定（docs/06 §2）。
 *
 * 悬浮样式下底栏浮在内容之上，页面用它预留底部空间；这个数一旦比胶囊本身矮，
 * 页面最后一项目就会被玻璃压住——所以在这里钉住，别让它随留白调整悄悄漂走。
 */
class BottomBarInsetTest {

    @Test
    fun `预留高度跟胶囊的构成对得上`() {
        val capsule = PictSpacing.xs * 2 + NavBarItemMinHeight
        assertEquals(PictSpacing.md * 2 + capsule, FloatingNavBarReservedHeight)
        assertEquals(88.dp, FloatingNavBarReservedHeight)
    }

    @Test
    fun `预留高度不小于胶囊本身`() {
        assertTrue(FloatingNavBarReservedHeight >= PictSpacing.xs * 2 + NavBarItemMinHeight)
    }
}
