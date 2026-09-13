package com.pict.metatool.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 取色来源铺到哪儿（FR-38 续，docs/06 §2）。
 *
 * 起因是实测的一句反馈：「动态取色之后，不止背景变色，要全面一点」。
 * 量出来的现状（`D:/tmp/pict-diag-20260913/dyn_ab.py`，同一个页面只切设置里那个开关）：
 * 任务页整屏只有 **0.46%** 的像素变了，图库页 **0.07%**——也就是说，
 * 开关翻过去之后，除了零散几个字，画面基本没动，用户当然觉得「只有背景变色」。
 *
 * 为什么会这样：取色来源确实换的是整套 `ColorScheme`，可**容器这一类角色本身是中性的**。
 * Material You 给出的 `surfaceContainer*`、`surfaceVariant` 是一串几乎不带色相的灰
 * （它就是按中性色阶设计的），卡片、区块、底栏承载色全落在这些角色上，
 * 于是「跟着壁纸走」在画面上只体现为背景那一点点明暗差。强调色（primary）倒是换了，
 * 但它只用在图标、开关、极小面积上，眼睛注意不到。
 *
 * 所以这一层的做法是**把容器往强调色偏一点**，规则只有一条：
 *
 * > 容器色 = 取色来源给的容器色，朝取色来源的强调色偏一档（本文件的常数）。
 *
 * 「取色来源」换了，整个结果跟着换，而不是按开没开动态取色写两套数：
 * 动态取色开着时偏的是壁纸色，关掉时偏的是品牌色（[LightColors]/[DarkColors] 里的 primary）。
 * 这样 [PictTheme] 只有一个分支，卡片在两种来源下都是「同一套设计语言、不同的色相」。
 *
 * 偏的幅度是按「不破坏原有层级与对比」定的：
 * - 区块（[ColorScheme.surfaceVariant]）偏得多一点，它是大面积的底；
 * - 卡片/弹层所在的 `surfaceContainer` 阶梯按高度逐渐多偏一点，
 *   免得把 Material 靠明度做的层级压平（越低越接近背景，越高压得越实）；
 * - 文字色（`onSurface*`）、状态色（error）、[ColorScheme.background] 一律不碰——
 *   前两个动了会掉对比度，后者本来就是跟着取色来源走的那个角色。
 *
 * 玻璃那几层（底栏承载色、高光、选中胶囊）不落在任何角色上，见 [PictPalette]。
 */

/** 区块底色的偏移量（`surfaceVariant`：设置页的行、详情的条目、批量的卡片底）。 */
private const val SectionTint = 0.14f

/** 卡片 / 弹层所在的 `surfaceContainer` 阶梯，从最低到最高；逐级多偏一点，保住明度层级。 */
private const val ContainerLowestTint = 0.05f
private const val ContainerLowTint = 0.07f
private const val ContainerTint = 0.09f
private const val ContainerHighTint = 0.11f
private const val ContainerHighestTint = 0.13f

/** `surfaceBright` / `surfaceDim`：背景的亮暗两个极端，轻微带一点色相就够。 */
private const val SurfaceEdgeTint = 0.06f

/**
 * 底栏玻璃承载色的偏移量。它还要乘上 `glassTintAlpha`（0.62），所以这里给得比别处大。
 */
private const val BarTint = 0.22f

/** 玻璃高光偏多少：纯白在浅色主题下就是一条灰边，带一点色相才像「这块玻璃」。 */
private const val SheenTint = 0.22f

/**
 * 自选底色（FR-38 续）当主题色用时，哪些角色跟着换个色相。
 *
 * 起因还是实测的一句反馈：「更换主题色的时候，界面所有都要变色」。当时底色的做法是
 * **只换 `background` 一个角色**，理由是「挑个底色不该顺带把导出失败的红改掉」——
 * 道理没错，但活只干了一半：实测同一个页面只把底色从「自动」挑成「薄荷」，
 * 除了页面底那一片，卡片、区块底、底栏玻璃、选中胶囊全还是品牌蓝那一套
 * （容器朝 `primary` 偏，而 `primary` 没动），用户看到的就是「只有背景变色」。
 *
 * 所以现在把选定的底色当成**取色来源本身**：[backdropThemedScheme] 拿底色的色相，
 * 把强调那一族角色整体转过去，再走一遍 [tintedContainers]。
 * 换一个底色 = 换一整套配色，跟动态取色是同一套机制、同一个收口点。
 */
internal fun backdropThemedScheme(scheme: ColorScheme, backdrop: Color): ColorScheme {
    val hue = hueOf(backdrop)
    // 强调那一族跟着底色走：图标、开关、按钮、选中胶囊、各种 chip 一并对上色相，
    // 不然「底是绿的、开关还是蓝的」，两个色系摆在一起更像没做完。
    val rotated = scheme.copy(
        primary = repaintToHue(scheme.primary, hue),
        primaryContainer = repaintToHue(scheme.primaryContainer, hue),
        secondary = repaintToHue(scheme.secondary, hue),
        secondaryContainer = repaintToHue(scheme.secondaryContainer, hue),
        tertiary = repaintToHue(scheme.tertiary, hue),
        tertiaryContainer = repaintToHue(scheme.tertiaryContainer, hue),
        // 底色自己就是背景那一个角色，原样用（深色主题下已经在 `backdropArgbFor` 里压暗过）
        background = backdrop,
    )
    return tintedContainers(rotated)
}

/**
 * 色相 0..360（灰返回 0）。只用来取「往哪边转」，所以不做色彩空间的讲究，
 * 按 sRGB 三通道算的 HSL 就够——它跟 `Color.hsl` 是同一套定义，转回去不会有偏差。
 */
internal fun hueOf(color: Color): Float {
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    val delta = max - min
    if (delta == 0f) return 0f
    val hue = when (max) {
        color.red -> ((color.green - color.blue) / delta + if (color.green < color.blue) 6f else 0f)
        color.green -> (color.blue - color.red) / delta + 2f
        else -> (color.red - color.green) / delta + 4f
    } * 60f
    return (hue % 360f + 360f) % 360f
}

/**
 * 把 [color] 换成 [hue] 这个色相，**亮度按 WCAG 相对亮度对齐**，饱和度和明度关系照旧。
 *
 * 为什么不直接 `Color.hsl(hue, 原饱和度, 原明度)`：HSL 的明度不是亮度。同样是
 * 明度 0.5、饱和度 1，黄色比蓝色亮好几倍——直接换色相会把「白字压强调色」这种搭配
 * 从 4.5:1 拽到 2:1 以下（沙底、苔底那两个暖色相就会走到这一步）。
 * 所以这里二分找明度，让换完之后的相对亮度跟原色对齐（[BisectionSteps] 次足够到 1e-6）。
 *
 * 饱和度**不跟底色走**：它管的是强调色够不够醒目，动它会把「选中」这种状态弄糊。
 *
 * 中性色（饱和度低于 [NeutralSaturation]）原样返回：没有色相可换，硬转只是换个灰。
 */
internal fun repaintToHue(color: Color, hue: Float): Color {
    val saturation = saturationOf(color)
    if (saturation < NeutralSaturation) return color
    val target = color.luminance()
    var low = 0f
    var high = 1f
    repeat(BisectionSteps) {
        val mid = (low + high) / 2f
        if (Color.hsl(hue, saturation, mid).luminance() < target) low = mid else high = mid
    }
    return Color.hsl(hue, saturation, (low + high) / 2f)
}

/** HSL 的饱和度（灰返回 0）。 */
internal fun saturationOf(color: Color): Float {
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return 0f
    return if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
}

/** 低于这个饱和度就当没有色相（品牌灰、`surface` 那一族）。 */
private const val NeutralSaturation = 0.02f

/** 二分找亮度的次数：20 次就到 1e-6，这里给宽一点，反正是一次性算的。 */
private const val BisectionSteps = 24

/** 选中胶囊那类「强调色容器」的偏移量：面积小，得比区块底更明确才认得出——
 *  真机截图给视觉看时，18% 那版被指出「选中胶囊像一层灰膜」，所以加大；
 *  但 26% 会把它偏出 53 级（单测那条「不超过 48 级」当场拦下），落在 22% 更合适。 */
private const val AccentContainerTint = 0.22f

/** 选中胶囊的底色不透明度：深色下要压住底下的图标，浅色下可以更透一点。 */
private const val PillAlphaDark = 0.66f
private const val PillAlphaLight = 0.58f

/**
 * 把 [base] 朝 [accent] 偏 [amount]（0 = 原样，1 = 整个换成强调色）。
 *
 * 单独拎出来是为了能单测：`amount` 为 0 必须原样返回（透明度也一样），
 * 否则「偏一档」在某个角色上会变成「完全替换」，而这种错只在眼睛看得出来、测试不写就漏。
 */
internal fun tintToward(base: Color, accent: Color, amount: Float): Color =
    if (amount <= 0f) base else lerp(base, accent, amount)

/**
 * 把一套配色的容器角色朝它自己的强调色偏一档（见文件头那段）。
 *
 * 只改容器；`primary` 之类的强调色、`onSurface*` 之类的文字色、状态色原样带走。
 * 这么做还有一个好处：**所有既有调用点都不用动**——`Card`、`Surface`、
 * 底部弹层、`ImageGridCell` 的占位底，它们读的都是这些角色，
 * 规则落在角色上，新加的界面自动跟着走（写在每处调用点上的话，迟早漏一个）。
 */
internal fun tintedContainers(scheme: ColorScheme): ColorScheme = scheme.copy(
    surfaceVariant = tintToward(scheme.surfaceVariant, scheme.primary, SectionTint),
    // 强调色容器（底栏选中胶囊、贴底样式的指示条、各种 chip）也是容器：
    // 它本来就跟着来源走，但仍朝强调色偏一档——不然「选中」这个状态跟强调色是两套色
    secondaryContainer = tintToward(scheme.secondaryContainer, scheme.primary, AccentContainerTint),
    surfaceContainerLowest = tintToward(scheme.surfaceContainerLowest, scheme.primary, ContainerLowestTint),
    surfaceContainerLow = tintToward(scheme.surfaceContainerLow, scheme.primary, ContainerLowTint),
    surfaceContainer = tintToward(scheme.surfaceContainer, scheme.primary, ContainerTint),
    surfaceContainerHigh = tintToward(scheme.surfaceContainerHigh, scheme.primary, ContainerHighTint),
    surfaceContainerHighest = tintToward(scheme.surfaceContainerHighest, scheme.primary, ContainerHighestTint),
    surfaceBright = tintToward(scheme.surfaceBright, scheme.primary, SurfaceEdgeTint),
    surfaceDim = tintToward(scheme.surfaceDim, scheme.primary, SurfaceEdgeTint),
)

/**
 * 底栏那边几个**不落在任何配色角色上**的颜色。
 *
 * 玻璃是自己画的（承载色、镜面高光、选中胶囊各自一层 `drawRect`），
 * 它们以前分别写的是 `surface`、`Color.White`、白/黑各一个不透明度——
 * 三处都不带色相，所以任凭取色来源怎么换，底栏看上去都一样。
 *
 * @param barTint 玻璃的承载色（不带透明度，那部分由 `glassTintAlpha` 决定）。
 * @param barSheen 镜面高光与描边的颜色。
 * @param navPill 选中胶囊的底色（已带不透明度）。
 */
internal data class PictPalette(
    val barTint: Color,
    val barSheen: Color,
    val navPill: Color,
)

/**
 * 从一套配色推出底栏那几个颜色。
 *
 * @param dark 深色主题（由 [PictTheme] 传，它才是判定深浅的那一处；这里不再自己看亮度——
 *   同一个判断写在两处，改了主题分支就会出现「底栏按深色算、别处按浅色算」）。
 */
internal fun pictPalette(scheme: ColorScheme, dark: Boolean): PictPalette = PictPalette(
    barTint = tintToward(scheme.surface, scheme.primary, BarTint),
    barSheen = tintToward(Color.White, scheme.primary, SheenTint),
    // 胶囊也用「已经偏过一档」的那个角色，跟 `tintedContainers` 一个口径
    navPill = tintedContainers(scheme).secondaryContainer
        .copy(alpha = if (dark) PillAlphaDark else PillAlphaLight),
)

/**
 * 当前这套配色推出来的底栏颜色。给不到（比如预览里没套 [PictTheme]）时用品牌色兜底，
 * 不抛异常——那是预览崩，不是用户看得见的问题。
 */
internal val LocalPictPalette = staticCompositionLocalOf {
    pictPalette(DarkColors, dark = true)
}

/** 跟 `MaterialTheme.colorScheme` 一个用法：`MaterialTheme.pictPalette.barTint`。 */
internal val MaterialTheme.pictPalette: PictPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalPictPalette.current
