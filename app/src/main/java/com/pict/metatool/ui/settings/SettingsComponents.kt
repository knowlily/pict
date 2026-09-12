package com.pict.metatool.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.pict.metatool.R
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
            modifier = Modifier.fillMaxWidth(),
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
 * 被点的那一行的位置（窗口坐标）。
 *
 * 点开一项之后弹出的东西要**盖在那一行上**（docs/06 §3.7）：Material 的对话框默认落在
 * 屏幕正中，横屏下一眼看不出它跟哪一行有关，眼睛还得从刚点的地方挪开去找。
 * 这个类记住的就是「刚才点的是哪一行、它在哪儿」。
 */
class SettingsRowAnchor internal constructor() {

    internal var row: IntRect by mutableStateOf(IntRect.Zero)
}

/** 建一个锚点，活在本 composable 的 `remember` 里。 */
@Composable
fun rememberSettingsRowAnchor(): SettingsRowAnchor = remember { SettingsRowAnchor() }

/** 挂在可点行上：把这一行的窗口位置交给 [anchor]，弹层就对着它落。 */
fun Modifier.settingsRowAnchor(anchor: SettingsRowAnchor): Modifier =
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        // 取整：弹层落点要跟行**像素对齐**，差半像素看着就是歪的
        anchor.row = IntRect(
            left = bounds.left.roundToInt(),
            top = bounds.top.roundToInt(),
            right = bounds.right.roundToInt(),
            bottom = bounds.bottom.roundToInt(),
        )
    }

/**
 * 就地展开的编辑层：跟 [anchor] 那一行**同宽同位**，落下去正好把它盖住。
 *
 * 跟对话框的区别只有落点：内容、按钮、点外面关掉都一样，所以设置页的编辑项不必
 * 在两种交互之间做选择，只是观感上「这一行展开了」，而不是「屏幕中间冒出一个东西」。
 *
 * 整屏 scrim 没有加：这一层的语义是「改这一行」，不是「离开这个页面」，
 * 压暗整页看着像被打断；点空白处照样关得掉（[PopupProperties.focusable]）。
 */
@Composable
fun SettingsRowPopup(
    anchor: SettingsRowAnchor,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    confirmColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val row = anchor.row
    val provider = remember(row) { CoverRowPositionProvider(row) }
    val width = with(LocalDensity.current) { row.width.toDp() }
    val defaultConfirmLabel = stringResource(R.string.settings_confirm)
    val cancelLabel = stringResource(R.string.settings_cancel)

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            // 跟锚点行同宽：量到锚点之前（宽度为 0）先不限定，免得第一帧闪一下窄条
            modifier = if (width > 0.dp) Modifier.width(width) else Modifier,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = PictSpacing.lg,
                    vertical = PictSpacing.md,
                ),
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

/** 把 [CoverRowPositionProvider] 的算式交给纯函数，单测不必起设备（NFR-09）。 */
private class CoverRowPositionProvider(private val row: IntRect) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = anchoredPopupOffset(row, windowSize, popupContentSize)
}

/**
 * 弹层左上角该落在哪。
 *
 * 跟锚点行的左上角对齐（于是就盖住了那一行）。弹层比剩下的空间高/宽时往里收缩，
 * 收到装得下为止——宁可盖住上面几行，也不要让「确定」掉到屏幕外面去。
 *
 * 还没量到锚点（[row] 还没量出来、宽高都是 0）时退回屏幕正中：跟改动前的对话框同一个落点，
 * 不至于从屏幕外闪进来。窗口还没量出来时给 (0,0)，交给系统自己决定。
 */
internal fun anchoredPopupOffset(row: IntRect, windowSize: IntSize, popupContentSize: IntSize): IntOffset {
    if (windowSize.width <= 0 || windowSize.height <= 0) return IntOffset.Zero

    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)

    if (row.width <= 0 || row.height <= 0) return IntOffset(x = maxX / 2, y = maxY / 2)

    return IntOffset(
        x = row.left.coerceIn(0, maxX),
        y = row.top.coerceIn(0, maxY),
    )
}
