package com.pict.metatool.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置值的规范化规则（docs/06 §3.7）。
 *
 * 这些值直接改行为（导出文件名、要不要读回校验、图库列数），所以坏值必须在「用之前」
 * 被夹回合法范围；夹的规则放在纯函数里，不靠设备验。
 */
class AppSettingsTest {

    @Test
    fun `默认值就是文档里承诺的那一套`() {
        val defaults = AppSettings()
        assertEquals("-edited", defaults.exportSuffix)
        assertTrue(defaults.verifyAfterExport)
        assertFalse(defaults.presetOverwriteDefault)
        assertEquals(20260101L, defaults.randomSeedDefault)
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertEquals(3, defaults.gridColumns)
    }

    @Test
    fun `两头的空白不留`() {
        assertEquals("已编辑", AppSettings.normalizeSuffix("  已编辑  "))
    }

    @Test
    fun `文件名里的非法字符被清掉而不是原样写进去`() {
        assertEquals("abcdefghij", AppSettings.normalizeSuffix("a/b\\c:d*e?f\"g<h>i|j"))
    }

    @Test
    fun `超长后缀被截断`() {
        val long = "-" + "x".repeat(200)
        val normalized = AppSettings.normalizeSuffix(long)
        assertEquals(AppSettings.MAX_SUFFIX_LENGTH, normalized.length)
        assertTrue(normalized.startsWith("-xxx"))
    }

    @Test
    fun `空后缀是合法的：就是不加上后缀`() {
        assertEquals("", AppSettings.normalizeSuffix("   "))
        assertEquals("", AppSettings.normalizeSuffix("\n\t"))
    }

    @Test
    fun `图库列数被夹在 2 到 4 之间`() {
        assertEquals(2, AppSettings(gridColumns = 1).normalized().gridColumns)
        assertEquals(2, AppSettings(gridColumns = 0).normalized().gridColumns)
        assertEquals(3, AppSettings(gridColumns = 3).normalized().gridColumns)
        assertEquals(4, AppSettings(gridColumns = 9).normalized().gridColumns)
    }

    @Test
    fun `normalized 顺手把后缀也清一遍`() {
        assertEquals("clean", AppSettings(exportSuffix = " cle/an ").normalized().exportSuffix)
    }

    @Test
    fun `主题模式认不出来的名字回落到跟随系统`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("NO_SUCH_MODE"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
    }

    @Test
    fun `主题模式与系统深浅色的四种组合`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun `种子只认整数，认不出来的返回空`() {
        assertEquals(-7L, AppSettings.parseSeed("-7"))
        assertEquals(20260101L, AppSettings.parseSeed(" 20260101 "))
        assertNull(AppSettings.parseSeed(""))
        assertNull(AppSettings.parseSeed("一百"))
        assertNull(AppSettings.parseSeed("9".repeat(19)))
    }
}
