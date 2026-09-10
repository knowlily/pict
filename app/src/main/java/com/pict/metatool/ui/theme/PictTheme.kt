package com.pict.metatool.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
 * @param dynamicColor 默认关闭：品牌色优先，避免 Material You 取色破坏状态色语义（docs/06 §2）。
 *
 * 系统栏与窗口底色也在这里接管，**以应用选定的主题为准**，而不是只靠 `values-night/`：
 * 那份资源只有「系统夜间模式」一个输入。用户在设置里选「深色」而系统是浅色（或反过来）时，
 * 图标明暗会跟背景同色——实测就是「黑图标压黑底」「白图标压白底」，状态栏基本看不见
 * （docs/08 §10.5）。所以图标明暗与窗口底色都跟着 [darkTheme] 走。
 */
@Composable
fun PictTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

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
