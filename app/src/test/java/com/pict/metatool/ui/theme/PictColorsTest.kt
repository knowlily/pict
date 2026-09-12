package com.pict.metatool.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 取色来源铺到容器上（FR-38 续，docs/06 §2）。
 *
 * 这些用例守的是一句用户话：「动态取色之后，不止背景变色，要全面一点」。
 * 起因是实测——同一个页面只翻那个开关，整屏只有 0.46% 的像素在变，
 * 因为容器角色是中性的灰、底栏玻璃又写死了白与黑，换成来源也看不出来。
 *
 * 用例分三类：①真的跟着来源走了；②幅度是「一点色相」而不是换了个底色；
 * ③偏色没把正文压到看不清。
 */
class PictColorsTest {

    /** 两套来源：一个偏蓝、一个偏橙，模拟换壁纸。 */
    private val blue = Color(0xFF1B5CD6)
    private val orange = Color(0xFFE07000)

    private fun scheme(accent: Color, dark: Boolean) =
        if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)

    private fun channels(color: Color): Triple<Int, Int, Int> {
        val argb = color.toArgb()
        return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    /** 两个颜色各通道差的最大值（0-255）。 */
    private fun spread(a: Color, b: Color): Int {
        val (ar, ag, ab) = channels(a)
        val (br, bg, bb) = channels(b)
        return maxOf(abs(ar - br), abs(ag - bg), abs(ab - bb))
    }

    /** WCAG 对比度。 */
    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.luminance().toDouble()
        val b = bg.luminance().toDouble()
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test
    fun `偏移的两端就是两个端点`() {
        val base = Color(0xFF102030)
        val accent = Color(0xFFCC3300)
        assertEquals(base, tintToward(base, accent, 0f))
        assertEquals(accent, tintToward(base, accent, 1f))
        // 中间值落在两端之间——但**不是「各通道取平均」**：Compose 的 `lerp` 走 Oklab
        // （感知均匀的混色），黑到红的一半不等于 R=128。所以断言只钉两条不随色彩空间变的事。
        val half = tintToward(Color(0xFF000000), Color(0xFFFF0000), 0.5f)
        assertTrue("一半既不是起点也不是终点", half != Color(0xFF000000) && half != Color(0xFFFF0000))
        val (halfRed, _, _) = channels(half)
        assertTrue("红通道 $halfRed 跑到端点上了", halfRed in 1..254)
        assertTrue(
            "一半的亮度应当落在两端之间",
            half.luminance() > Color(0xFF000000).luminance() &&
                half.luminance() < Color(0xFFFF0000).luminance(),
        )
    }

    @Test
    fun `所有容器角色都带上来源的色相`() {
        val base = scheme(blue, dark = false)
        val tinted = tintedContainers(base)
        listOf(
            Triple("surfaceVariant", base.surfaceVariant, tinted.surfaceVariant),
            Triple("surfaceContainerLowest", base.surfaceContainerLowest, tinted.surfaceContainerLowest),
            Triple("surfaceContainerLow", base.surfaceContainerLow, tinted.surfaceContainerLow),
            Triple("surfaceContainer", base.surfaceContainer, tinted.surfaceContainer),
            Triple("surfaceContainerHigh", base.surfaceContainerHigh, tinted.surfaceContainerHigh),
            Triple("surfaceContainerHighest", base.surfaceContainerHighest, tinted.surfaceContainerHighest),
        ).forEach { (name, before, after) ->
            assertTrue("$name 没跟着来源偏", before != after)
        }
    }

    @Test
    fun `没碰文字与背景`() {
        val base = scheme(blue, dark = false)
        val tinted = tintedContainers(base)
        // 文字、强调色、背景原样：偏色只管「底」，不管「字」——
        // 顺手把这些也偏一下，最先坏掉的是对比度，而对比度坏了是没法靠调参数救的
        assertEquals(base.background, tinted.background)
        assertEquals(base.onSurface, tinted.onSurface)
        assertEquals(base.onSurfaceVariant, tinted.onSurfaceVariant)
        assertEquals(base.primary, tinted.primary)
        assertEquals(base.outlineVariant, tinted.outlineVariant)
    }

    @Test
    fun `换来源就换容器——这就是别再只有背景变色`() {
        val fromBlue = tintedContainers(scheme(blue, dark = false))
        val fromOrange = tintedContainers(scheme(orange, dark = false))
        // 每通道至少差 8 级：低于这个数在屏幕上就是「说不变也看不出来」
        assertTrue(
            "两个来源的区块底差 ${spread(fromBlue.surfaceVariant, fromOrange.surfaceVariant)} 级，太小",
            spread(fromBlue.surfaceVariant, fromOrange.surfaceVariant) >= 8,
        )
        assertTrue(
            "两个来源的卡片底差得太小",
            spread(fromBlue.surfaceContainerLow, fromOrange.surfaceContainerLow) >= 4,
        )
    }

    @Test
    fun `幅度是一点色相而不是换了个底色`() {
        listOf(false, true).forEach { dark ->
            val base = scheme(blue, dark = dark)
            val tinted = tintedContainers(base)
            listOf(
                Triple("surfaceVariant", base.surfaceVariant, tinted.surfaceVariant),
                Triple("surfaceContainerLow", base.surfaceContainerLow, tinted.surfaceContainerLow),
                Triple("surfaceContainerHighest", base.surfaceContainerHighest, tinted.surfaceContainerHighest),
            ).forEach { (name, before, after) ->
                val d = spread(before, after)
                // 36 级是「手滑」的分界线：14% / 0.13 这种正常偏移实测在 20-28 级，
                // 写成 0.9 会到 100 级以上——这条拦的是后者，不是要钉住具体数值
                assertTrue("$name 偏了 $d 级（dark=$dark）：超过 36 就不是「一点色相」了", d <= 36)
            }
        }
    }

    @Test
    fun `强调色容器比大面积的底更明确，但仍不等于强调色本身`() {
        // 小面积（选中胶囊、chip）的偏移可以更大：它的任务就是让人一眼看到"选中了"。
        // 但也不能偏到跟 primary 一个颜色——那样"容器"和"强调色"就塌成一件事了。
        listOf(false, true).forEach { dark ->
            val base = scheme(blue, dark = dark)
            val tinted = tintedContainers(base)
            val d = spread(base.secondaryContainer, tinted.secondaryContainer)
            assertTrue("胶囊偏了 $d 级（dark=$dark）：小面积可以更明确，但不该超过 48 级", d in 1..48)
            assertTrue(
                "胶囊被偏成强调色本身了（差 ${spread(tinted.secondaryContainer, base.primary)} 级）",
                spread(tinted.secondaryContainer, base.primary) > 24,
            )
        }
    }

    @Test
    fun `偏色之后正文还看得清`() {
        listOf(false, true).forEach { dark ->
            val tinted = tintedContainers(scheme(blue, dark = dark))
            val onSection = contrast(tinted.onSurfaceVariant, tinted.surfaceVariant)
            val onCard = contrast(tinted.onSurface, tinted.surfaceContainerLow)
            val onCardDim = contrast(tinted.onSurfaceVariant, tinted.surfaceContainerLow)
            val onPill = contrast(tinted.onSecondaryContainer, tinted.secondaryContainer)
            println("dark=$dark 区块底 $onSection / 卡片正文 $onCard / 卡片次要 $onCardDim / 胶囊 $onPill")
            assertTrue("区块底上的次要文字对比度 $onSection 低于 4.5（dark=$dark）", onSection >= 4.5)
            assertTrue("卡片上的正文对比度 $onCard 低于 4.5（dark=$dark）", onCard >= 4.5)
            assertTrue("卡片上的次要文字对比度 $onCardDim 低于 4.5（dark=$dark）", onCardDim >= 4.5)
            assertTrue("胶囊上的文字对比度 $onPill 低于 4.5（dark=$dark）", onPill >= 4.5)
        }
    }

    @Test
    fun `底栏那三个颜色都听来源的`() {
        val fromBlue = pictPalette(scheme(blue, dark = false), dark = false)
        val fromOrange = pictPalette(scheme(orange, dark = false), dark = false)
        assertTrue("承载色没跟着来源", spread(fromBlue.barTint, fromOrange.barTint) >= 8)
        assertTrue("高光没跟着来源", spread(fromBlue.barSheen, fromOrange.barSheen) >= 4)
        assertTrue("选中胶囊没跟着来源", spread(fromBlue.navPill, fromOrange.navPill) >= 8)
    }

    @Test
    fun `胶囊底色深浅两版不同且都留了透明度`() {
        val light = pictPalette(scheme(blue, dark = false), dark = false)
        val dark = pictPalette(scheme(blue, dark = true), dark = true)
        assertTrue(light.navPill != dark.navPill)
        assertTrue("胶囊得是半透明的，不然玻璃透不下去", light.navPill.alpha in 0.3f..0.9f)
        assertTrue("胶囊得是半透明的，不然玻璃透不下去", dark.navPill.alpha in 0.3f..0.9f)
        // 带色相：不是灰的（三个通道不全相等）
        val (r, g, b) = channels(light.navPill)
        assertTrue("选中胶囊是灰的，等于没取色", !(r == g && g == b))
    }

    @Test
    fun `品牌那两套配色也算取色来源`() {
        // 关掉动态取色时来源就是品牌色，同一个规则照样成立
        val inBrand = tintedContainers(DarkColors)
        assertTrue(inBrand.surfaceVariant != DarkColors.surfaceVariant)
        val palette = pictPalette(DarkColors, dark = true)
        assertTrue(palette.barTint != DarkColors.primary)
        assertTrue(palette.navPill.alpha < 1f)
    }
}
