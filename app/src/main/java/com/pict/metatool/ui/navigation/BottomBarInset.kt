package com.pict.metatool.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pict.metatool.ui.theme.PictSpacing

/** 底栏单项的最小高度：跟 FloatingNavItem 的 heightIn 是同一个数。 */
val NavBarItemMinHeight: Dp = 56.dp

/**
 * 悬浮胶囊占掉的高度（不含系统导航条内边距）：上下留白 ×2 + 胶囊内边距 ×2 + 单项高度。
 *
 * 悬浮样式下底栏是浮在内容之上的（不然玻璃背板没东西可糊），Scaffold 不再替页面留位，
 * 页面得拿这个数给滚动内容留底，否则最后一项目永远压在玻璃下面。
 */
val FloatingNavBarReservedHeight: Dp =
    PictSpacing.md * 2 + PictSpacing.xs * 2 + NavBarItemMinHeight

/**
 * 底栏压住的那段高度，页面给滚动内容留底用的。
 *
 * 贴底样式走 Scaffold 的 bottomBar 槽，Scaffold 自己留位，这里就是 0；
 * 悬浮样式由 PictApp 提供胶囊高度。
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }
