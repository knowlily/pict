package com.pict.metatool.ui.navigation

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pict.metatool.ui.theme.PictMotion
import com.pict.metatool.ui.theme.PictSpacing

/** 胶囊的圆角半径。 */
private val BarCornerRadius = 28.dp

/** 背板模糊半径：够糊掉底下的缩略图和文字，又不至于把整块屏糊成一片色。 */
private val BarBlurRadius = 28.dp

/** 胶囊的投影高度，撑出一点「厚度」。 */
private val BarShadowElevation = 12.dp

/** 主描边（镜面反射）的粗细：比 1 dp 略粗一点，高光才看得出是「一道边」而不是抗锯齿。 */
private val BarSpecularWidth = 1.5.dp

/** 内圈高光离外沿的距离：做出玻璃有厚度的那一圈。 */
private val BarInnerRimInset = 2.5.dp

/** 背板提饱和的倍数：玻璃底下的颜色比原图更透亮一点，是 iOS 玻璃那股「润」的来头。 */
private const val GLASS_SATURATION: Float = 1.35f

/**
 * 玻璃承载色的不透明度。
 *
 * 真模糊时留更多透光（0.52），能看出底下有内容；API < 31 上 RenderEffect 不生效，
 * 就只剩半透明——糊不住底下的细节，得多压一点（0.9）保住文字可读。这是降级，不是等价实现。
 *
 * [glass] = false（设置里关掉液态玻璃）时给 1.0：一层实心底栏，不透光、不糊背板。
 */
internal fun glassTintAlpha(blurSupported: Boolean, glass: Boolean = true): Float = when {
    !glass -> 1f
    blurSupported -> 0.52f
    else -> 0.9f
}

/**
 * 悬浮底栏（docs/06 §2）：一枚浮在内容上的液态玻璃胶囊。
 *
 * 跟 Material 的 NavigationBar 的区别不只是形状与留白——它是**玻璃**：
 * 半透明承载色下面压着 [backdrop]，也就是把底下的内容糊一份当背板。
 * 玻璃的「质感」全在那几层叠加上（顺序就是下面 `drawWithContent` 里的编号）：
 * 糊过的背板（顺带提一点饱和）→ 半透明承载色 → 顶上渐隐的镜面高光 → 底下一点点内辉 →
 * 一道左上亮、右下淡的描边 → 再往里收一点的内圈高光。
 * 只压一层半透明色，看着就是「一块灰板子」，不是玻璃。
 *
 * 所以它必须浮在内容之上（`PictApp` 把它挪出了 Scaffold 的 bottomBar 槽），
 * 内容从玻璃底下穿过去，玻璃才有东西可糊。
 *
 * [backdrop] 是 `PictApp` 录好的全屏内容图层；传 null（预览、贴底样式）就退化成半透明底。
 * [glass] = false 时完全不画玻璃（实心底 + 描边），`PictApp` 那头也不会去录背板。
 * 模糊要 API 31 起（RenderEffect），更老的机器上糊不了，只有半透明（见 [glassTintAlpha]）。
 *
 * 选中项用圆角承载色标出来，底色与图标色一起过渡；动效走 [PictMotion.quick]，
 * 底栏是高频点击的地方，过渡要短、不要弹。
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
    backdrop: GraphicsLayer? = null,
    glass: Boolean = true,
) {
    val shape = RoundedCornerShape(BarCornerRadius)
    val density = LocalDensity.current
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurRadiusPx = with(density) { BarBlurRadius.toPx() }
    val cornerRadiusPx = with(density) { BarCornerRadius.toPx() }
    val hairlinePx = with(density) { BarSpecularWidth.toPx() }
    val innerInsetPx = with(density) { BarInnerRimInset.toPx() }

    val blurLayer = rememberGraphicsLayer()
    // 背板是「整屏坐标」里录的，画进胶囊之前得先减掉胶囊自己的位置。
    var barOrigin by remember { mutableStateOf(Offset.Zero) }

    val colorScheme = MaterialTheme.colorScheme
    val tint = colorScheme.surface.copy(alpha = glassTintAlpha(blurSupported, glass))
    // 镜面高光固定用白：深色下是灰白的一层，浅色下是近白的一层，两边都只是「亮一点」。
    val sheen = Color.White
    val rim = colorScheme.outlineVariant
    val glassFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(GLASS_SATURATION) })
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { barOrigin = it.positionInParent() }
            .navigationBarsPadding()
            .padding(horizontal = PictSpacing.lg, vertical = PictSpacing.md)
            .shadow(elevation = BarShadowElevation, shape = shape, clip = false)
            .clip(shape)
            .drawWithContent {
                if (!glass) {
                    // 关掉玻璃：实心承载色 + 一道描边，还是一块「板」，但不透光也不糊背板。
                    drawRect(color = colorScheme.surface)
                    drawRoundRect(
                        color = rim,
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                        style = Stroke(width = hairlinePx),
                    )
                    drawContent()
                    return@drawWithContent
                }

                // 1) 背板：把底下的内容糊一份铺满，顺手提一点饱和（玻璃底下的颜色更透亮）
                if (backdrop != null && blurSupported) {
                    blurLayer.renderEffect = BlurEffect(radiusX = blurRadiusPx, radiusY = blurRadiusPx)
                    // 提饱和挂在图层的 colorFilter 上（`DrawScope.drawLayer` 这个版本只收 alpha）
                    blurLayer.colorFilter = glassFilter
                    blurLayer.record {
                        translate(left = -barOrigin.x, top = -barOrigin.y) { drawLayer(backdrop) }
                    }
                    drawLayer(blurLayer)
                }

                // 2) 玻璃本身：半透明承载色
                drawRect(color = tint)

                // 3) 顶部镜面高光：从顶上最亮，往下半屏渐隐
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to sheen.copy(alpha = 0.34f),
                        0.55f to Color.Transparent,
                    ),
                    size = Size(size.width, size.height),
                )

                // 4) 底部内辉：光从下面反上来一点点，玻璃才有厚度（没有这层就是个上亮下平的渐变块）
                drawRect(
                    brush = Brush.verticalGradient(
                        0.72f to Color.Transparent,
                        1f to sheen.copy(alpha = 0.12f),
                    ),
                    size = Size(size.width, size.height),
                )

                // 5) 主描边：左上亮、右下淡——同一道光扫过整圈，iOS 玻璃最认得出的一笔
                drawRoundRect(
                    brush = Brush.linearGradient(
                        0f to sheen.copy(alpha = 0.5f),
                        0.45f to sheen.copy(alpha = 0.12f),
                        1f to sheen.copy(alpha = 0.04f),
                    ),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = hairlinePx),
                )

                // 6) 内圈高光：再往里收一点、更淡的一圈，看着像玻璃有厚度而不是贴纸
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

                // 7) 内容
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PictSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(PictSpacing.xs),
        ) {
            items.forEach { destination ->
                FloatingNavItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.FloatingNavItem(
    destination: PictDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    val container by animateColorAsState(
        targetValue = if (selected) {
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

    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = NavBarItemMinHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
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
