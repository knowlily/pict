package com.pict.metatool.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween

/**
 * 动效令牌（docs/06 §3）。
 * 原则：批量任务的进度反馈要「稳」，不用弹跳类缓动，避免长任务视觉疲劳。
 */
object PictMotion {
    /** 按压/勾选等即时反馈 */
    const val Quick = 120

    /** 页面切换、面板展开 */
    const val Standard = 220

    /** 危险操作确认、回退动画 */
    const val Emphasized = 320

    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> quick() = tween<T>(durationMillis = Quick, easing = StandardEasing)
    fun <T> standard() = tween<T>(durationMillis = Standard, easing = StandardEasing)
    fun <T> emphasized() = tween<T>(durationMillis = Emphasized, easing = EmphasizedEasing)
}
