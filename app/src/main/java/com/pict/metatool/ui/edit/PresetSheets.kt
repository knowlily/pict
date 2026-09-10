package com.pict.metatool.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pict.metatool.R
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.preset.Preset

/**
 * 编辑页的「预设 / 随机填充 / 添加字段」三个弹层（docs/06 §3.3、docs/07 T3.7 / T3.9）。
 *
 * 三个弹层只做两件事：让用户挑，然后把结果交回 [EditUiState]。
 * 它们**都不直接写文件**——值一律先进草稿，用户在「预览」里看清 diff 再点「应用」，
 * 因此「挑错了」的代价是撤销而不是写坏原图。
 */

/** 预设快选：整套档案一次填好（设备 / 位置 / 时间 / 混合）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetSheet(
    state: EditUiState,
    onToggleOverwrite: (Boolean) -> Unit,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = stringResource(R.string.edit_preset_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.edit_preset_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleOverwrite(!state.presetOverwrite) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.presetOverwrite, onCheckedChange = onToggleOverwrite)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.edit_preset_overwrite),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.edit_preset_overwrite_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.presetIssues.firstOrNull()?.let { issue ->
                Text(
                    text = stringResource(R.string.edit_preset_issue, issue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.presets.isEmpty()) {
                Text(
                    text = stringResource(R.string.edit_preset_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                state.presetsByKind.forEach { (kind, presets) ->
                    item(key = "kind-${kind.id}") {
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(items = presets, key = { it.id }) { preset ->
                        PresetRow(preset = preset, onApply = { onApply(preset.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Preset, onApply: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = preset.name, style = MaterialTheme.typography.bodyMedium)
            preset.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.edit_preset_keys, preset.keyCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = onApply) { Text(text = stringResource(R.string.edit_preset_apply)) }
    }
}

/**
 * 随机填充：挑预设 → 勾字段 → 定种子 → 填入草稿。
 *
 * 「种子」是显式且可见的：同一张图 + 同一预设 + 同一种子必然得到同一批值（docs/07 T3.6）。
 * 「换一批」只是换一个种子，不是引入不可复现的随机。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RandomFillSheet(
    state: EditUiState,
    onChoosePreset: (String) -> Unit,
    onToggleKey: (com.pict.metatool.domain.model.TagKey) -> Unit,
    onRerollSeed: () -> Unit,
    onFill: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = stringResource(R.string.edit_random_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.edit_random_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.edit_random_preset),
                style = MaterialTheme.typography.labelLarge,
            )
            state.presets.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoosePreset(preset.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = preset.id == state.randomFillPresetId,
                        onClick = { onChoosePreset(preset.id) },
                    )
                    Text(
                        text = stringResource(R.string.edit_preset_keys_named, preset.name, preset.keyCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                text = stringResource(R.string.edit_random_fields),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = stringResource(R.string.edit_random_fixed_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            val candidates = state.randomFillCandidates
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.edit_random_no_candidates),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(items = candidates, key = { it.key.full }) { spec ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleKey(spec.key) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = spec.key in state.randomFillKeys,
                                onCheckedChange = { onToggleKey(spec.key) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = spec.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = spec.key.full,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.edit_random_seed_value, state.randomFillSeed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRerollSeed) {
                    Text(text = stringResource(R.string.edit_random_seed_reroll))
                }
            }

            Button(onClick = onFill, enabled = state.randomFillReady) {
                Text(text = stringResource(R.string.edit_random_fill))
            }
        }
    }
}

/**
 * 添加原图没有的元数据：列出目录里可写、本文件还没有的字段。
 *
 * 为什么单独开一个入口：搜索框那条路要求用户先**猜**字段名（或记得中文标签）；
 * 「我想给这张图加个拍摄地点」这种意图得能一路翻到，而不是搜不到就以为不支持。
 * 点一行 = 打开该字段的编辑弹层（走既有的输入校验），值同样先进草稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddFieldSheet(
    state: EditUiState,
    onQueryChange: (String) -> Unit,
    onPick: (FieldSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.edit_add_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.edit_add_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.addFieldQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.edit_add_search_hint)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
            )

            val fields = state.addableFields
            if (fields.isEmpty()) {
                Text(
                    text = stringResource(R.string.edit_add_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(items = fields, key = { it.key.full }) { spec ->
                        AddRow(spec = spec, onAdd = { onPick(spec) })
                    }
                }
            }
        }
    }
}
