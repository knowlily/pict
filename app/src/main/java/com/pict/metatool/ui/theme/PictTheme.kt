package com.pict.metatool.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = AmberTertiary,
    onTertiary = AmberOnTertiary,
    tertiaryContainer = AmberTertiaryContainer,
    onTertiaryContainer = AmberOnTertiaryContainer,
    error = ErrorRed,
    onError = ErrorOnRed,
    errorContainer = ErrorContainerRed,
    onErrorContainer = OnErrorContainerRed,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

private val DarkColors = darkColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BlueOnPrimaryContainer,
    onPrimaryContainer = BluePrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealOnSecondaryContainer,
    onSecondaryContainer = TealSecondaryContainer,
    tertiary = AmberTertiary,
    onTertiary = AmberOnTertiary,
    tertiaryContainer = AmberOnTertiaryContainer,
    onTertiaryContainer = AmberTertiaryContainer,
    error = ErrorRed,
    onError = ErrorOnRed,
    errorContainer = OnErrorContainerRed,
    onErrorContainer = ErrorContainerRed,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

/**
 * @param dynamicColor 取色来源（FR-38）：true = 跟着壁纸走（Material You），false = 用应用
 *   自带的品牌配色。默认 false 是「调用方没说就用品牌色」；`MainActivity` 传的是设置里
 *   那一项（默认开），所以在 Android 12 及以上，实际观感是跟着壁纸的。
 *
 *   品牌配色仍然是一等公民：状态色语义（error、导出成功/失败）在动态取色下由系统的
 *   error 角色兜底，不靠写死的色值（docs/06 §2）。
 *
 * 系统栏与窗口底色也在这里接管，**以应用选定的主题为准**，而不是只靠 `values-night/`：
 * 那份资源只有「系统夜间模式」一个输入。用户在设置里选「深色」而系统是浅色（或反过来）时，
 * 图标明暗会跟背景同色——实测就是「黑图标压黑底」「白图标压白底」，状态栏基本看不见
 * （docs/08 §10.5）。所以图标明暗与窗口底色都跟着 [darkTheme] 走。
 *
 * 版本判断走 [supportsDynamicColor]：那边一处定规则，设置页里那一行的可用状态共用同一个判断，
 * 不会出现「开关亮着但颜色没变」。
 *
 * @param backdrop 自选页面底色（FR-38 续，ARGB），`null` = 用主题自带的那一个。只换
 *   `background` 这一个角色：卡片表面、强调色、状态色都还是各自那一套，挑个底色不会顺带把
 *   「导出成功/失败」的颜色改掉。动态取色开着时底色跟着壁纸走，调用方传 `null`——
 *   判断留在 `MainActivity`，主题这一层不偷看设置。
 */
@Composable
fun PictTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    backdrop: Long? = null,
    content: @Composable () -> Unit,
) {
    val baseColors = when {
        dynamicColor && supportsDynamicColor() -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    val colorScheme = if (backdrop == null) baseColors else baseColors.copy(background = Color(backdrop))

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // 冷启动首帧与页面切换时露出的窗口底色：跟着应用主题，深色下不闪白
            window.setBackgroundDrawable(ColorDrawable(colorScheme.background.toArgb()))
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !darkTheme
            bars.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PictTypography,
        shapes = PictShapes,
        content = content,
    )
}
