package com.pict.metatool.ui.preset

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pict.metatool.R
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.domain.preset.PresetSelection
import com.pict.metatool.ui.edit.sheetListMaxHeight

/**
 * 预设**分栏多选**弹层（编辑页与批量页共用同一套）。
 *
 * 为什么要分栏：一个混合预设要在文件里预先把「机型 + 位置 + 时间」钉死，
 * 组合数是相乘的 —— 4 个机型 × 4 个地点 × 3 个时间就是 48 个预设文件，
 * 用户想换一个维度只能换整套。分栏之后每栏各挑一个，组合在套用那一刻拼出来。
 *
 * 语义（[PresetSelection]）：**每栏至多一个**，跨栏可同时选中；同栏再点一下取消。
 * 弹层只改「选择」，真正的写入发生在按「套用到草稿」时，用户可以先改主意。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetPickerSheet(
    title: String,
    presets: List<Preset>,
    selection: Map<PresetKind, String>,
    confirmLabel: String,
    issues: List<String> = emptyList(),
    warnings: List<String> = emptyList(),
    header: (@Composable ColumnScope.() -> Unit)? = null,
    onToggle: (Preset) -> Unit,
    onAddOwn: (PresetKind) -> Unit,
    onEditOwn: (Preset) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pickedCount = PresetSelection.orderedPresets(selection, presets).size

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.preset_pick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            issues.firstOrNull()?.let { issue ->
                Text(
                    text = stringResource(R.string.preset_pick_user_issue, issue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            warnings.firstOrNull()?.let { warning ->
                Text(
                    text = stringResource(R.string.edit_preset_issue, warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            header?.invoke(this)

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = sheetListMaxHeight())) {
                PresetSelection.byKind(presets).forEach { (kind, group) ->
                    item(key = "preset-kind-${kind.id}") {
                        KindHeader(kind = kind, onAddOwn = onAddOwn)
                    }
                    if (group.isEmpty()) {
                        item(key = "preset-empty-${kind.id}") {
                            Text(
                                text = stringResource(R.string.preset_pick_empty_kind),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    } else {
                        item(key = "preset-row-${kind.id}") {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 4.dp),
                            ) {
                                items(items = group, key = { it.id }) { preset ->
                                    PresetCard(
                                        preset = preset,
                                        selected = selection[kind] == preset.id,
                                        onToggle = onToggle,
                                        onEditOwn = onEditOwn,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.preset_pick_selected,
                        PresetSelection.label(selection, presets),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.preset_pick_close)) }
                Button(onClick = onConfirm, enabled = pickedCount > 0) { Text(text = confirmLabel) }
            }
        }
    }
}

/** 栏头：`设备` + 「＋ 自己加一个」。空栏也留着，不然用户找不到在那一类里加东西的入口。 */
@Composable
private fun KindHeader(kind: PresetKind, onAddOwn: (PresetKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kind.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onAddOwn(kind) }) {
            Text(
                text = stringResource(R.string.preset_pick_add_own, kind.label),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 一张预设卡片：名字 + 说明 + 字段数；自建的那几张多一个「改」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetCard(
    preset: Preset,
    selected: Boolean,
    onToggle: (Preset) -> Unit,
    onEditOwn: (Preset) -> Unit,
) {
    val own = preset.origin == PresetOrigin.USER
    OutlinedCard(
        onClick = { onToggle(preset) },
        modifier = Modifier.width(168.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.preset_pick_chosen),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            preset.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.edit_preset_keys, preset.keyCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                if (own) {
                    TextButton(onClick = { onEditOwn(preset) }) {
                        Text(
                            text = stringResource(R.string.preset_pick_edit),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
