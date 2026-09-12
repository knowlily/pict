package com.pict.metatool.ui.navigation

import android.os.Build

/**
 * 底栏玻璃这回合能做到哪一步（FR-35）。
 *
 * 用的是 Kyant0 的 Backdrop 库（`com.github.Kyant0:AndroidLiquidGlass`，Apache-2.0），
 * 它整套走 `RenderEffect` + AGSL 着色器：模糊要 API 31（`RenderEffect.createBlurEffect`），
 * 边沿折射 `lens` 要 API 33（AGSL `RuntimeShader`），而本项目的 minSdk 是 26。
 * 分档只在这一个纯函数里判，界面按档决定画哪套，别到处散落 `Build.VERSION` 判断。
 */
internal enum class GlassEffectPlan {
    /**
     * 关掉（设置里关了液态玻璃），或者底栏不是悬浮样式。
     * 一层实心承载色，既不录背板也不画背板。
     */
    OFF,

    /**
     * API < 31：`RenderEffect` 不生效，糊不动。只剩半透明 + 自绘的高光与描边。
     * 这是降级，不是等价实现——底下的字不会糊，会和底栏的字叠着看。
     */
    TINT_ONLY,

    /** API 31–32：能真模糊，但折射要 AGSL（33），所以只有糊，没有边沿那点「液态」。 */
    BLUR,

    /** API ≥ 33：模糊 + 边沿折射，液态玻璃完整那套。 */
    LENS,
}

/** 版本分档。`enabled` 来自设置里的「液态玻璃」开关、以及底栏是不是悬浮样式。 */
internal fun glassEffectPlan(
    sdkInt: Int = Build.VERSION.SDK_INT,
    enabled: Boolean = true,
): GlassEffectPlan = when {
    !enabled -> GlassEffectPlan.OFF
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> GlassEffectPlan.LENS
    sdkInt >= Build.VERSION_CODES.S -> GlassEffectPlan.BLUR
    else -> GlassEffectPlan.TINT_ONLY
}

/**
 * 承载色的不透明度（降级口径）。
 *
 * 能糊就少压一点、多透光 0.52；糊不了就压到 0.9 保住可读——底下是什么都还看得见，但字不打架；
 * 关掉玻璃是 1.0，实心。这是降级，不是等价实现。
 */
internal fun glassTintAlpha(plan: GlassEffectPlan): Float = when (plan) {
    GlassEffectPlan.OFF -> 1f
    GlassEffectPlan.TINT_ONLY -> 0.9f
    GlassEffectPlan.BLUR, GlassEffectPlan.LENS -> 0.52f
}

/** 值不值得为这块玻璃离屏录一份背板：糊得动（含折射）才录，糊不动录了也白花显存。 */
internal fun glassNeedsBackdrop(plan: GlassEffectPlan): Boolean =
    plan == GlassEffectPlan.BLUR || plan == GlassEffectPlan.LENS
