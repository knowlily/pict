package com.pict.metatool.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距令牌（docs/06 §2）：8dp 基准栅格，允许 4dp 半格。
 */
object PictSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** 页面横向安全边距 */
    val screenHorizontal = lg

    /** 列表项之间的垂直间距 */
    val listGap = md

    /** 底部导航栏之上的内容留白 */
    val aboveBottomBar = xl
}
