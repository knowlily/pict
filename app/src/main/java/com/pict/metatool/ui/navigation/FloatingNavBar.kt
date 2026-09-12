package com.pict.metatool.ui.navigation

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.pict.metatool.ui.theme.PictMotion
import com.pict.metatool.ui.theme.PictSpacing
import kotlin.math.roundToInt

/** 胶囊的圆角半径。 */
private val BarCornerRadius = 28.dp

/** 背板模糊半径（库的 `blur`，真糊一份背板）。 */
private val BarBlurRadius = 28.dp

/** 边沿折射的高度与位移：库里「液态」的那部分，AGSL 把边缘附近的背板掰弯（API 33+）。 */
private val BarRefractionHeight = 24.dp
private val BarRefractionShift = 16.dp

/** 玻璃自己的投影（库的 `shadow`），撑出一点「厚度」。 */
private val BarShadowRadius = 16.dp

/** 糊不动时自己画的投影高度。 */
private val BarShadowElevation = 12.dp

/** 降级路径上的高光：主描边粗细、内圈高光离外沿的距离。 */
private val BarSpecularWidth = 1.5.dp
private val BarInnerRimInset = 2.5.dp

/** 选中项那团「液态」胶囊：圆角，以及按下时从边缘化开的折射。 */
private val PillCornerRadius = 22.dp
private val PillRefractionHeight = 10.dp
private val PillRefractionShift = 12.dp

/**
 * 悬浮底栏（docs/06 §2）：一枚浮在内容上的液态玻璃胶囊。
 *
 * 玻璃本身不再自己画，交给 Kyant0 的 Backdrop 库（`com.kyant.backdrop`，Apache-2.0，
 * 源码 github.com/Kyant0/AndroidLiquidGlass）：[backdrop] 是 `PictApp` 录好的整屏内容图层，
 * 库把它当材质，`vibrancy()` 提饱和、`blur()` 糊、`lens()` 把边缘掰出折射，
 * `highlight`/`shadow` 各画一层——以前那七层手绘玻璃（背板 + 承载色 + 镜面高光 + 内辉 +
 * 主描边 + 内圈高光）里，只有「承载色」留下，改成库的 `onDrawSurface`。
 *
 * 所以它必须浮在内容之上（`PictApp` 把它挪出了 Scaffold 的 bottomBar 槽），
 * 内容从玻璃底下穿过去，玻璃才有东西可糊。
 *
 * 分档全在 [glassEffectPlan] 里判：API ≥ 33 才有真折射，31–32 只有糊，
 * 更低（minSdk 26）糊不动，退化成自绘的半透明板 + 高光（降级，不是等价实现）；
 * [glass] = false 时连背板都不录（`PictApp` 那头一起关掉），只剩实心底 + 描边。
 *
 * 选中项不再是一块纯色圆角：它是第二层玻璃（[LiquidNavPill]），跟着选中项滑过去，
 * 按下去时折射从边缘化开。动效走 [PictMotion.quick] 与一根带点回弹的弹簧——
 * 底栏是高频点击的地方，位移要短。
 *
 * 无障碍：单项最小高度 56 dp 满足触摸目标；语义角色是 Tab，
 * selectable 自带「已选中」播报，图标与文字同用一份文案。
 */
@Composable
fun FloatingNavBar(
    items: List<PictDestination>,
    currentRoute: String?,
    onSelect: (PictDestination) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    glass: Boolean = true,
) {
    val plan = glassEffectPlan(Build.VERSION.SDK_INT, glass)
    // 糊得动、而且真有背板可画，才走库那条路；否则回落到自绘的半透明板。
    val libraryGlass = glassNeedsBackdrop(plan) && backdrop != null
    val glassy = plan != GlassEffectPlan.OFF

    val density = LocalDensity.current
    val blurRadiusPx = with(density) { BarBlurRadius.toPx() }
    val refractionHeightPx = with(density) { BarRefractionHeight.toPx() }
    val refractionShiftPx = with(density) { BarRefractionShift.toPx() }

    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(BarCornerRadius)
    val tint = colorScheme.surface.copy(alpha = glassTintAlpha(plan))
    // 镜面高光固定用白：深色下是灰白的一层，浅色下是近白的一层，两边都只是「亮一点」。
    val sheen = Color.White
    val rim = colorScheme.outlineVariant

    // 底栏自己画的那层（图标 + 文字）也录一份，选中胶囊才能在它上面再折一层（玻璃叠玻璃）。
    val barBackdrop = rememberLayerBackdrop()
    val pillBackdrop: Backdrop =
        if (backdrop != null) rememberCombinedBackdrop(backdrop, barBackdrop) else barBackdrop

    // 按在选中项上时，胶囊「化开」：折射、高光、投影一起涨，松手缩回去。
    val selectedPress = remember { MutableInteractionSource() }
    val pressed by selectedPress.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = PictMotion.quick(),
        label = "navGlassPress",
    )

    var innerSize by remember { mutableStateOf(IntSize.Zero) }
    val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    // 在底栏上左右滑时，选中胶囊跟着手指走的位移（像素，右为正）。松手/取消一定归零。
    var swipeOffsetPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = PictSpacing.lg, vertical = PictSpacing.md)
            .then(
                if (libraryGlass) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(blurRadiusPx)
                            if (plan == GlassEffectPlan.LENS) {
                                lens(refractionHeightPx, refractionShiftPx)
                            }
                        },
                        shadow = {
                            Shadow(radius = BarShadowRadius, color = Color.Black.copy(alpha = 0.12f))
                        },
                        onDrawSurface = { drawRect(tint) },
                    )
                } else {
                    Modifier
                        .shadow(elevation = BarShadowElevation, shape = shape, clip = false)
                        .clip(shape)
                        .drawWithContent {
                            val cornerRadiusPx = BarCornerRadius.toPx()
                            val hairlinePx = BarSpecularWidth.toPx()
                            drawRect(color = tint)
                            if (!glassy) {
                                // 关掉玻璃：实心承载色 + 一道描边，还是一块「板」，但不透光也不糊背板。
                                drawRoundRect(
                                    color = rim,
                                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                                    style = Stroke(width = hairlinePx),
                                )
                                drawContent()
                                return@drawWithContent
                            }
                            // 糊不动（API < 31）：背板画不出来，「玻璃感」只能全靠这几层自绘的光。
                            // 1) 顶部镜面高光：从顶上最亮，往下半屏渐隐
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to sheen.copy(alpha = 0.34f),
                                    0.55f to Color.Transparent,
                                ),
                            )
                            // 2) 底部内辉：光从下面反上来一点点，玻璃才有厚度
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.72f to Color.Transparent,
                                    1f to sheen.copy(alpha = 0.12f),
                                ),
                            )
                            // 3) 主描边：左上亮、右下淡
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    0f to sheen.copy(alpha = 0.5f),
                                    0.45f to sheen.copy(alpha = 0.12f),
                                    1f to sheen.copy(alpha = 0.04f),
                                ),
                                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                                style = Stroke(width = hairlinePx),
                            )
                            // 4) 内圈高光：再往里收一点、更淡的一圈，看着像玻璃有厚度而不是贴纸
                            val innerInsetPx = BarInnerRimInset.toPx()
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    0f to sheen.copy(alpha = 0.16f),
                                    1f to Color.Transparent,
                                ),
                                topLeft = Offset(innerInsetPx, innerInsetPx),
                                size = Size(size.width - innerInsetPx * 2, size.height - innerInsetPx * 2),
                                cornerRadius = CornerRadius(
                                    cornerRadiusPx - innerInsetPx,
                                    cornerRadiusPx - innerInsetPx,
                                ),
                                style = Stroke(width = hairlinePx),
                            )
                            drawContent()
                        }
                },
            )
            .then(
                // 在底栏上左右滑就切页（FR-35 续）：格子照旧点，滑动只是多一条路
                if (items.size > 1) {
                    Modifier.navBarSwipe(
                        itemCount = items.size,
                        currentIndex = selectedIndex,
                        onSwipeTo = { index -> items.getOrNull(index)?.let(onSelect) },
                        onDragOffsetChange = { swipeOffsetPx = it },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PictSpacing.xs)
                .onSizeChanged { innerSize = it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (libraryGlass) Modifier.layerBackdrop(barBackdrop) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(PictSpacing.xs),
            ) {
                items.forEach { destination ->
                    val selected = currentRoute == destination.route
                    FloatingNavItem(
                        destination = destination,
                        selected = selected,
                        onClick = { onSelect(destination) },
                        containerOnItem = !libraryGlass,
                        interactionSource = if (libraryGlass && selected) selectedPress else null,
                    )
                }
            }
            if (libraryGlass && items.isNotEmpty() && innerSize.width > 0) {
                LiquidNavPill(
                    backdrop = pillBackdrop,
                    innerSize = innerSize,
                    count = items.size,
                    selectedIndex = selectedIndex,
                    pressProgress = pressProgress,
                    swipeOffsetPx = swipeOffsetPx,
                )
            }
        }
    }
}

/**
 * 跟着选中项滑过去的那团玻璃。
 *
 * 库的用法是「玻璃叠玻璃」：底栏的背板（页面内容）加上底栏自己画的内容（图标、文字）合成一份，
 * 胶囊再把这份背板折一遍——静止时不掰像素（`lens` 的 refractionHeight 归零，库自己会 return），
 * 那会儿底下的图标才不会重影：掰的是库录下的那份拷贝，真图标还在原地没动。
 * 按下去折射从边缘化开、带上色散，才是「液态」。
 */
@Composable
private fun LiquidNavPill(
    backdrop: Backdrop,
    innerSize: IntSize,
    count: Int,
    selectedIndex: Int,
    pressProgress: Float,
    swipeOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { PictSpacing.xs.toPx() }
    val itemWidthPx = (innerSize.width - gapPx * (count - 1)) / count
    // 拖动中胶囊跟着手指走（夹在底栏里，到边就停），松手 swipeOffsetPx 归零、position 走弹簧滑到新格子：
    // 两条来源分开算——拖动要「直接」（手指在哪它就在哪），落位才「液体」（带点回弹滑过去）
    val followPx = navSwipeFollowOffsetPx(
        currentIndex = selectedIndex,
        itemCount = count,
        dragXPx = swipeOffsetPx,
        slotPx = itemWidthPx + gapPx,
    )
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        // 滑过去带一点回弹：像液体追上去，而不是直着平移
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "navPillPosition",
    )
    val pillWidth = with(density) { itemWidthPx.toDp() }
    val pillHeight = with(density) { innerSize.height.toDp() }
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pillTint = if (darkSurface) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .offset { IntOffset(x = (position * (itemWidthPx + gapPx) + followPx).roundToInt(), y = 0) }
            .size(width = pillWidth, height = pillHeight)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(PillCornerRadius) },
                effects = {
                    lens(
                        refractionHeight = with(density) { (PillRefractionHeight * pressProgress).toPx() },
                        refractionAmount = with(density) { (PillRefractionShift * pressProgress).toPx() },
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight.Default.copy(alpha = 0.3f + 0.6f * pressProgress) },
                shadow = {
                    Shadow(
                        radius = BarShadowRadius * (0.5f + pressProgress),
                        color = Color.Black.copy(alpha = 0.08f + 0.12f * pressProgress),
                    )
                },
                innerShadow = {
                    InnerShadow(
                        radius = 8.dp + 10.dp * pressProgress,
                        color = Color.Black.copy(alpha = 0.2f + 0.2f * pressProgress),
                    )
                },
                layerBlock = {
                    val scale = 1f + 0.03f * pressProgress
                    scaleX = scale
                    scaleY = scale
                },
                onDrawSurface = { drawRect(pillTint) },
            ),
    )
}

@Composable
private fun RowScope.FloatingNavItem(
    destination: PictDestination,
    selected: Boolean,
    onClick: () -> Unit,
    containerOnItem: Boolean,
    interactionSource: MutableInteractionSource?,
) {
    val label = stringResource(destination.labelRes)
    // 选中胶囊走库那条路时，这一格自己不铺底色——底色由跟着选中项滑的玻璃胶囊负责，
    // 两个都画就是两层，一动起来露馅。
    val container by animateColorAsState(
        targetValue = if (selected && containerOnItem) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = PictMotion.quick(),
        label = "navItemContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = PictMotion.quick(),
        label = "navItemContent",
    )
    val ownSource = remember { MutableInteractionSource() }
    val source = interactionSource ?: ownSource

    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = NavBarItemMinHeight)
            .clip(RoundedCornerShape(PillCornerRadius))
            .background(container)
            .selectable(
                selected = selected,
                interactionSource = source,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = PictSpacing.sm, vertical = PictSpacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(top = PictSpacing.xxs),
        )
    }
}
