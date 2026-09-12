package com.pict.metatool.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态玻璃的承载色不透明度（docs/06 §2、设置里的「液态玻璃」开关）。
 *
 * 三档都得钉住：关掉是纯实心（1.0），真模糊时透光（0.52），糊不动（API < 31）时压回 0.9
 * 保住文字可读。顺序错了要么文字糊在图片上、要么「关掉」还透着光——开关就成了摆设。
 */
class FloatingNavBarGlassTest {

    @Test
    fun `关掉液态玻璃就是一层实心`() {
        assertEquals(1f, glassTintAlpha(blurSupported = true, glass = false), 0f)
        assertEquals(1f, glassTintAlpha(blurSupported = false, glass = false), 0f)
    }

    @Test
    fun `开着且糊得动时留更多透光`() {
        assertEquals(0.52f, glassTintAlpha(blurSupported = true, glass = true), 0f)
    }

    @Test
    fun `开着但糊不动时压回去保住文字可读`() {
        val degraded = glassTintAlpha(blurSupported = false, glass = true)
        assertEquals(0.9f, degraded, 0f)
        assertTrue(degraded > glassTintAlpha(blurSupported = true, glass = true))
    }
}
