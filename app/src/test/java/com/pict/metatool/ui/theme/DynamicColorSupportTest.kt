package com.pict.metatool.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态取色的版本闸门（FR-38）。
 *
 * 这条规则在两处生效（主题的取色分支、设置页那一行的亮/灰），所以拿参数化的 SDK 号把它钉住：
 * 免得哪天在一处改成 `>= 30`，冒出「开关亮着但颜色没变」这种最难查的偏差。
 */
class DynamicColorSupportTest {

    @Test
    fun `Android 12 及以上才支持动态取色`() {
        assertFalse(supportsDynamicColor(21))
        assertFalse(supportsDynamicColor(30)) // Android 11：差一版
        assertTrue(supportsDynamicColor(31)) // Android 12：API 31 起系统才给壁纸调色板
        assertTrue(supportsDynamicColor(35)) // Android 15：真机跑的就是这一版
    }
}
