package com.pict.metatool.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.pict.metatool.R
import com.pict.metatool.domain.settings.Backdrop
import com.pict.metatool.ui.theme.PictMotion
import com.pict.metatool.ui.theme.PictSpacing
import kotlin.math.roundToInt

/**
 * 设置页的零件（docs/06 §3.7）。
 *
 * 三件事统一在这里：分组卡片长什么样、每行的标题/说明怎么排、点击区多大。
 * 设置页正文只负责「有哪些项、点了之后改哪个字段」，排版细节不重复十遍。
 *
 * 触控区一律 ≥ 48 dp（docs/06 §7）：开关行整行可点，不是只有那个小滑块能点。
 */

/** 一组设置：小标题 + 一张圆角卡片（docs/06 §4：Card 16 dp 圆角、elevation 3 dp）。 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    anchor: SettingsCardAnchor? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = PictSpacing.xs, bottom = PictSpacing.sm),
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            // 传了 anchor 的卡片：这张卡里的编辑项点开之后，编辑层要盖住整张卡（见 SettingsCardPopup）
            modifier = Modifier
                .fillMaxWidth()
                .then(if (anchor == null) Modifier else Modifier.settingsCardAnchor(anchor)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = PictSpacing.lg, vertical = PictSpacing.xs),
                content = content,
            )
        }
    }
}

/** 开关行：整行可点，点哪都算。 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // 行自己的圆角跟卡片同一套（shapes.small）：按压/长按的高亮由行自己画，
            // 不裁就还是直角矩形——圆角卡里蹦出一个方框，长按停在那儿的时候最显眼。
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = PictSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowText(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 色块的触控区 ≥ 48 dp（docs/06 §7）；可见的那一圈比它小，看着才不笨重。 */
private val SwatchTouchSize = 48.dp
private val SwatchSize = 38.dp

/**
 * 底色行：一行圆形色块，点一个换一个（FR-38 续，docs/06 §3.7）。
 *
 * 色块比文字标签好认，但读屏软件只认名字：每个色块都带 `contentDescription` 与「已选中」语义。
 * 选中的那个加一圈主色描边 + 一个对勾，对勾的黑白按色块亮度自动挑——浅底上画白勾等于没画。
 */
@Composable
fun SettingsSwatchRow(
    title: String,
    options: List<Backdrop>,
    selected: Backdrop,
    label: (Backdrop) -> String,
    onSelect: (Backdrop) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PictSpacing.md),
    ) {
        SettingsRowText(title = title, subtitle = subtitle)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PictSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(PictSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                BackdropSwatch(
                    option = option,
                    selected = option == selected,
                    label = label(option),
                    enabled = enabled,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/** 一个色块；[Backdrop.AUTO] 画主题自带的表面色——「自动」本身不是一个色号。 */
@Composable
private fun BackdropSwatch(
    option: Backdrop,
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fill = if (option == Backdrop.AUTO) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color(option.argb)
    }
    val tick = if (fill.luminance() > 0.5f) Color.Black else Color.White

    Box(
        modifier = Modifier
            .size(SwatchTouchSize)
            // 圆角在 clickable 之前：水波纹跟着圆圈走（同 SettingsSwitchRow 的说明）
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .alpha(if (enabled) 1f else DisabledSwatchAlpha),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SwatchSize)
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = tick,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 底色不能用（动态取色开着）时色块的透明度：看得出「在，但点不动」。 */
private const val DisabledSwatchAlpha = 0.38f

/** 取值行：右边显示当前值，[onClick] 非空时整行可点并带动 `>`。 */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 圆角要在 clickable 之前：高亮跟行同一个形状（见 SettingsSwitchRow 的说明）。
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(clickable)
            .padding(vertical = PictSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowText(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(start = PictSpacing.sm),
        )

        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 多选一：标题下面一排骨牌，选中态由调用方给。选项少（2–4 个）时才用。 */
@Composable
fun <T> SettingsChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PictSpacing.md),
    ) {
        SettingsRowText(title = title, subtitle = subtitle)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PictSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(text = label(option)) },
                )
            }
        }
    }
}

/** 危险动作行：整行可点，标题走 error 色（如「恢复默认设置」）。 */
@Composable
fun SettingsDangerRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = PictSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PictSpacing.xxs),
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/** 只读的说明段落，用来讲清楚这一组设置背后的行为（如隐私声明）。 */
@Composable
fun SettingsNote(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PictSpacing.md),
    ) {
        SettingsRowText(title = title, subtitle = body)
    }
}

/** 行内文字：标题一行、说明一行（说明永远在标题下面，别挤到右侧去）。 */
@Composable
private fun SettingsRowText(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PictSpacing.xxs),
            )
        }
    }
}

/**
 * 一张设置卡片的窗口矩形（窗口坐标）。
 *
 * 点开一项之后弹出的编辑层要**盖住它所在的那张卡**（docs/06 §3.7）：卡片是「外面的设置框」，
 * 行只是里面的一格。只盖一行的话，卡的圆角、左右那几像素的边、上下相邻的行都会从弹层旁边
 * 露出来，看着像一个贴歪的小盒子；盖住整张卡，观感就是「这张卡原地展开成编辑层」。
 * 这个类记住的就是「刚才点的那张卡在哪儿」。
 */
class SettingsCardAnchor internal constructor() {

    internal var card: IntRect by mutableStateOf(IntRect.Zero)
}

/** 建一个锚点，活在本 composable 的 `remember` 里。 */
@Composable
fun rememberSettingsCardAnchor(): SettingsCardAnchor = remember { SettingsCardAnchor() }

/** 挂在设置卡片上：把这张卡的窗口位置交给 [anchor]，弹层就对着它落。 */
fun Modifier.settingsCardAnchor(anchor: SettingsCardAnchor): Modifier =
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        // 取整：弹层落点要跟卡片**像素对齐**，差半像素看着就是歪的
        anchor.card = IntRect(
            left = bounds.left.roundToInt(),
            top = bounds.top.roundToInt(),
            right = bounds.right.roundToInt(),
            bottom = bounds.bottom.roundToInt(),
        )
    }

/**
 * 就地展开的编辑层：跟 [anchor] 那张卡**同宽同位、至少同高**，落下去把整张卡盖住。
 *
 * 内容、按钮、点外面关掉都跟原来的对话框一样，区别只在落点与体量，所以设置页的编辑项不必
 * 在两种交互之间做选择，观感上是「这张卡展开了」，而不是「屏幕中间冒出一个东西」。
 * 卡片比内容高时内容在卡里居中——否则下半张卡会空得莫名其妙。
 *
 * 整屏 scrim 没有加：这一层的语义是「改这张卡里的东西」，不是「离开这个页面」，
 * 压暗整页看着像被打断；点空白处照样关得掉（[PopupProperties.focusable]）。
 */
@Composable
fun SettingsCardPopup(
    anchor: SettingsCardAnchor,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    confirmColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val card = anchor.card
    val provider = remember(card) { CoverCardPositionProvider(card) }
    val density = LocalDensity.current
    val width = with(density) { card.width.toDp() }
    val minHeight = with(density) { card.height.toDp() }
    val defaultConfirmLabel = stringResource(R.string.settings_confirm)
    val cancelLabel = stringResource(R.string.settings_cancel)
    // 入场是「原地展开」：淡入 + 一点点放大（Quick 档，docs/06 §3），别啪一下跳出来
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(targetValue = 1f, animationSpec = PictMotion.quick()) }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            // 跟 SettingsSection 那张卡同一个圆角与投影语言，只是压得更厚一点（盖在卡上）
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            // 量到卡片之前（宽高为 0）先不限定尺寸，免得第一帧闪一下窄条
            modifier = Modifier
                .then(if (card.width > 0) Modifier.width(width) else Modifier)
                .then(if (card.height > 0) Modifier.heightIn(min = minHeight) else Modifier)
                .graphicsLayer {
                    alpha = entrance.value
                    val scale = 0.97f + 0.03f * entrance.value
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = PictSpacing.lg,
                    vertical = PictSpacing.md,
                ),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Column(
                    modifier = Modifier.padding(top = PictSpacing.md),
                    content = content,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = PictSpacing.md),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = cancelLabel)
                    }

                    if (onConfirm != null) {
                        TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                            Text(text = confirmLabel ?: defaultConfirmLabel, color = confirmColor)
                        }
                    }
                }
            }
        }
    }
}

/** 把 [CoverCardPositionProvider] 的算式交给纯函数，单测不必起设备（NFR-09）。 */
private class CoverCardPositionProvider(private val card: IntRect) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = anchoredPopupOffset(card, windowSize, popupContentSize)
}

/**
 * 弹层左上角该落在哪。
 *
 * 跟卡片左上角对齐（于是整张卡被盖住）。弹层比剩下的空间高/宽时往里收缩，收到装得下为止——
 * 宁可盖住上面几行，也不要让「确定」掉到屏幕外面去。
 *
 * 还没量到卡片（[card] 还没量出来、宽高都是 0）时退回屏幕正中：跟改动前的对话框同一个落点，
 * 不至于从屏幕外闪进来。窗口还没量出来时给 (0,0)，交给系统自己决定。
 */
internal fun anchoredPopupOffset(card: IntRect, windowSize: IntSize, popupContentSize: IntSize): IntOffset {
    if (windowSize.width <= 0 || windowSize.height <= 0) return IntOffset.Zero

    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)

    if (card.width <= 0 || card.height <= 0) return IntOffset(x = maxX / 2, y = maxY / 2)

    return IntOffset(
        x = card.left.coerceIn(0, maxX),
        y = card.top.coerceIn(0, maxY),
    )
}
