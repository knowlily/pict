package com.pict.metatool.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏玻璃的分档与承载色（FR-35、docs/06 §2、设置里的「液态玻璃」开关）。
 *
 * 用 Kyant0 的 Backdrop 库之后，「能画到哪一步」由系统能力决定：AGSL 边沿折射要 API 33（`lens`），
 * `RenderEffect` 模糊要 31（`blur`），本机 minSdk 是 26。档位错了只在老机器上暴露——
 * 要么白录一份永远糊不了的背板，要么把折射当模糊使，正好是最难当场发现的那类偏差。
 */
class FloatingNavBarGlassTest {

    @Test
    fun `关掉玻璃就是 OFF，连背板都不该录`() {
        assertEquals(GlassEffectPlan.OFF, glassEffectPlan(sdkInt = 35, enabled = false))
        assertEquals(GlassEffectPlan.OFF, glassEffectPlan(sdkInt = 26, enabled = false))
        assertFalse(glassNeedsBackdrop(GlassEffectPlan.OFF))
        assertEquals(1f, glassTintAlpha(GlassEffectPlan.OFF), 0f)
    }

    @Test
    fun `Android 13 起才有边沿折射`() {
        assertEquals(GlassEffectPlan.LENS, glassEffectPlan(sdkInt = 33, enabled = true)) // 刚好这一版
        assertEquals(GlassEffectPlan.LENS, glassEffectPlan(sdkInt = 35, enabled = true)) // 真机跑的就是这一版
    }

    @Test
    fun `Android 12 上只有模糊，没有折射`() {
        assertEquals(GlassEffectPlan.BLUR, glassEffectPlan(sdkInt = 31, enabled = true)) // Android 12
        assertEquals(GlassEffectPlan.BLUR, glassEffectPlan(sdkInt = 32, enabled = true)) // 差一版
    }

    @Test
    fun `Android 11 及以下糊不动，只剩半透明`() {
        assertEquals(GlassEffectPlan.TINT_ONLY, glassEffectPlan(sdkInt = 30, enabled = true))
        assertEquals(GlassEffectPlan.TINT_ONLY, glassEffectPlan(sdkInt = 26, enabled = true)) // minSdk
    }

    @Test
    fun `糊得动才录背板`() {
        assertFalse(glassNeedsBackdrop(GlassEffectPlan.TINT_ONLY))
        assertTrue(glassNeedsBackdrop(GlassEffectPlan.BLUR))
        assertTrue(glassNeedsBackdrop(GlassEffectPlan.LENS))
    }

    @Test
    fun `承载色：能糊就透光，糊不动压回去保住文字可读`() {
        assertEquals(0.62f, glassTintAlpha(GlassEffectPlan.LENS), 0f)
        assertEquals(0.62f, glassTintAlpha(GlassEffectPlan.BLUR), 0f)
        val degraded = glassTintAlpha(GlassEffectPlan.TINT_ONLY)
        assertEquals(0.9f, degraded, 0f)
        assertTrue(degraded > glassTintAlpha(GlassEffectPlan.LENS))
    }
}
