package com.pict.metatool.domain.settings

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 页面底色（FR-38 续）：动态取色关掉之后，底色可以自己挑。
 *
 * 全是**低饱和的浅底**，浅色主题下直接用；深色主题下由 [backdropArgbFor] 按同一色相压暗，
 * 一份色号两种主题都能用（省得维护两张表，也免得挑一个深底把字吃掉）。
 *
 * 底色是**整套配色的取色来源**（`ui/theme/PictColors.kt` 的 `backdropThemedScheme`）：
 * 一是 `background` 这个角色用它；二是强调那一族（图标、开关、按钮、选中胶囊、chip）
 * 转到它的色相上，容器再朝新的强调色偏一档。状态色（导出成功/失败的红）与文字色不动——
 * 那两样跟「挑了哪个底色」无关，动了只会掉对比度。
 */
enum class Backdrop(val argb: Long) {

    /** 不改，用主题自带的那一个（也就是应用原来的样子）。 */
    AUTO(AppSettings.BACKGROUND_AUTO),
    CLOUD(0xFFF5F7FA),
    SAND(0xFFF8F3EA),
    MINT(0xFFEBF6F0),
    SKY(0xFFE9F1F9),
    LILAC(0xFFF3EEF9),
    MOSS(0xFFEEF4E9),
    BLUSH(0xFFF9EFF2),
    ;

    companion object {

        /** 认出这个色号是哪一个；不认识（手改过 pref）就当「自动」。 */
        fun fromArgb(argb: Long): Backdrop = entries.firstOrNull { it.argb == argb } ?: AUTO
    }
}

/**
 * 底色在指定主题下的实际色值。
 *
 * - [AppSettings.BACKGROUND_AUTO]（或 [`Backdrop.AUTO`][Backdrop.AUTO]）→ `null`，
 *   意思是「别动，用主题自带的那一个」；
 * - 浅色主题：原样用；
 * - 深色主题：压暗（[darkenedBackdrop]）——把浅底直接搬到深色里是一块发光的板子，
 *   卡片的边界和文字会一起被吞掉。
 *
 * 一律返回不透明色：底色不是能透出窗口底层的东西，带着半透明会露出壁纸（横屏下尤其难看）。
 */
fun backdropArgbFor(argb: Long, dark: Boolean): Long? = when {
    argb == AppSettings.BACKGROUND_AUTO -> null
    !dark -> argb or OPAQUE
    else -> darkenedBackdrop(argb)
}

/** 深色主题下的底色：保色相，把亮度压到 [DARK_LIGHTNESS]、饱和度收到 [MAX_DARK_SATURATION] 以内。 */
private fun darkenedBackdrop(argb: Long): Long {
    val (hue, saturation, _) = toHsl(argb)
    return fromHsl(hue, minOf(saturation, MAX_DARK_SATURATION), DARK_LIGHTNESS)
}

private const val OPAQUE = 0xFF000000L

/** 深色底色的亮度：比 `DarkBackground` 略亮一点，卡片（surface）才浮得起来。 */
private const val DARK_LIGHTNESS = 0.11f

/** 深色下允许保留的饱和度上限：再高就从「有色调的灰」变成一块彩色板了。 */
private const val MAX_DARK_SATURATION = 0.30f

/** ARGB → (色相 0..360, 饱和度 0..1, 亮度 0..1)。 */
private fun toHsl(argb: Long): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF).toInt() / 255f
    val g = ((argb shr 8) and 0xFF).toInt() / 255f
    val b = (argb and 0xFF).toInt() / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min

    if (delta == 0f) return Triple(0f, 0f, lightness)

    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * 60f

    return Triple(hue, saturation, lightness)
}

/** (色相, 饱和度, 亮度) → ARGB，不透明。 */
private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Long {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val sector = (((hue % 360f) + 360f) % 360f) / 60f
    val second = chroma * (1f - abs(sector % 2f - 1f))
    val (r1, g1, b1) = when {
        sector < 1f -> Triple(chroma, second, 0f)
        sector < 2f -> Triple(second, chroma, 0f)
        sector < 3f -> Triple(0f, chroma, second)
        sector < 4f -> Triple(0f, second, chroma)
        sector < 5f -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    val floor = lightness - chroma / 2f

    fun channel(value: Float): Long = ((value + floor) * 255f).roundToInt().coerceIn(0, 255).toLong()

    return OPAQUE or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}
