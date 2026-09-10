package com.pict.metatool.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pict.metatool.ui.theme.PictSpacing

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
    val clickable = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
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
