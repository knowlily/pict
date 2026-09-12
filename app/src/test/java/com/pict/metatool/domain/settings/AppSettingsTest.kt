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
    fun `最近目录：同一目录只留最近那一次`() {
        val trimmed = AppSettings.normalizeRecentFolders(
            listOf(
                RecentFolder("content://tree/a", "A", 100L),
                RecentFolder("content://tree/a", "A", 900L),
            ),
        )
        assertEquals(1, trimmed.size)
        assertEquals(900L, trimmed.first().usedAtMillis)
    }

    @Test
    fun `最近目录：按时间倒序、最多十条`() {
        val many = (1..12).map { RecentFolder("content://tree/$it", "目录$it", it.toLong()) }
        val trimmed = AppSettings.normalizeRecentFolders(many)

        assertEquals(AppSettings.MAX_RECENT_FOLDERS, trimmed.size)
        assertEquals("目录12", trimmed.first().name)
        // 掉下去的是最早的两条，不是最新的两条
        assertFalse(trimmed.any { it.name == "目录1" })
        assertFalse(trimmed.any { it.name == "目录2" })
    }

    @Test
    fun `最近目录：空 URI 或空名字的记录进不来`() {
        val trimmed = AppSettings.normalizeRecentFolders(
            listOf(
                RecentFolder("   ", "没有 URI", 3L),
                RecentFolder("content://tree/a", "   ", 2L),
                RecentFolder("content://tree/b", "B", 1L),
            ),
        )
        assertEquals(listOf("B"), trimmed.map { it.name })
    }

    @Test
    fun `记一次最近目录：新的排最前，重复的只刷新时间`() {
        val settings = AppSettings()
            .withRecentFolder("content://tree/a", "A", 1L)
            .withRecentFolder("content://tree/b", "B", 2L)
            .withRecentFolder("content://tree/a", "A", 3L)

        assertEquals(listOf("A", "B"), settings.recentFolders.map { it.name })
        assertEquals(listOf(3L, 2L), settings.recentFolders.map { it.usedAtMillis })
    }

    @Test
    fun `规范化会把最近目录也一起收干净`() {
        val settings = AppSettings(
            recentFolders = listOf(
                RecentFolder("content://tree/a", "A", 5L),
                RecentFolder("", "没有 URI", 9L),
            ),
        ).normalized()
        assertEquals(listOf("A"), settings.recentFolders.map { it.name })
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

    @Test
    fun `底栏默认悬浮、三个入口全开`() {
        val defaults = AppSettings()
        assertEquals(NavBarStyle.FLOATING, defaults.navBarStyle)
        assertEquals(setOf(NavItem.LIBRARY, NavItem.JOBS, NavItem.SETTINGS), defaults.navItems)
    }

    @Test
    fun `液态玻璃默认开着，关掉之后规范化也不会掀回默认`() {
        assertTrue(AppSettings().liquidGlass)
        // normalized() 只收后缀/列数/入口/最近目录：关玻璃是用户的明确选择，不能被顺手改回来
        assertEquals(false, AppSettings(liquidGlass = false).normalized().liquidGlass)
    }

    @Test
    fun `动态取色默认开着，关掉之后规范化也不会掀回默认`() {
        assertTrue(AppSettings().dynamicColor)
        // 同理：这是「颜色从哪来」的选择，normalized() 管的是后缀/列数/入口/最近目录，别越界
        assertEquals(false, AppSettings(dynamicColor = false).normalized().dynamicColor)
    }

    @Test
    fun `底栏样式认不出来的名字回落到悬浮`() {
        assertEquals(NavBarStyle.DOCKED, NavBarStyle.fromName("DOCKED"))
        assertEquals(NavBarStyle.FLOATING, NavBarStyle.fromName("NO_SUCH_STYLE"))
        assertEquals(NavBarStyle.FLOATING, NavBarStyle.fromName(null))
    }

    @Test
    fun `底栏入口存盘按枚举顺序、读回忽略没见过的名字`() {
        // 勾选顺序（JOBS 在前）不影响存盘结果：编码永远按枚举声明序，两边的值才可比
        assertEquals("LIBRARY,JOBS", NavItem.encode(setOf(NavItem.JOBS, NavItem.LIBRARY)))
        assertEquals(
            setOf(NavItem.LIBRARY, NavItem.JOBS),
            NavItem.decode("LIBRARY,JOBS"),
        )
        // 手改 pref 塞进来的名字（或中间多了空格）不能把整份设置搞崩，认不出来就丢
        assertEquals(setOf(NavItem.SETTINGS), NavItem.decode("SETTINGS,预制, SETTINGS"))
        assertEquals(emptySet<NavItem>(), NavItem.decode(null))
        assertEquals(emptySet<NavItem>(), NavItem.decode(""))
    }

    @Test
    fun `底栏入口一个不剩时回到默认三栏`() {
        // 底栏空了就没法导航了：宁可用回默认，也不留一个空条
        assertEquals(
            AppSettings.DEFAULT_NAV_ITEMS,
            AppSettings(navItems = emptySet()).normalized().navItems,
        )
        // 关掉一个就只少那一个，剩下的顺序仍按枚举
        assertEquals(
            setOf(NavItem.LIBRARY, NavItem.SETTINGS),
            AppSettings(navItems = setOf(NavItem.SETTINGS, NavItem.LIBRARY)).normalized().navItems,
        )
    }

    @Test
    fun `设置入口关不掉：手改 pref 去掉也会被兜回来`() {
        // 真机点验踩到过：设置页关掉「设置」之后，设置页就再也进不去了（pref 里记着，重启也回不来）
        assertEquals(
            setOf(NavItem.JOBS, NavItem.SETTINGS),
            AppSettings(navItems = setOf(NavItem.JOBS)).normalized().navItems,
        )
        // 反过来：只留必需项也算数，不会被「空集合」那条规则顶掉
        assertEquals(
            setOf(NavItem.SETTINGS),
            AppSettings(navItems = setOf(NavItem.SETTINGS)).normalized().navItems,
        )
    }

    @Test
    fun `设置页只列可选入口，必需项不进这一层`() {
        // 必需项摆上去也只能是个灰掉的开关，干脆不列（真机点验踩到过，见 docs/09 R-21）
        assertFalse(AppSettings.OPTIONAL_NAV_ITEMS.contains(NavItem.SETTINGS))
        assertTrue(AppSettings.OPTIONAL_NAV_ITEMS.contains(NavItem.LIBRARY))
        assertTrue(AppSettings.OPTIONAL_NAV_ITEMS.contains(NavItem.JOBS))
        // 可选 + 必需 = 全部入口；别漏项，也别重复
        assertEquals(
            NavItem.entries.toSet(),
            (AppSettings.OPTIONAL_NAV_ITEMS + AppSettings.REQUIRED_NAV_ITEMS).toSet(),
        )
    }
}
