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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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

/**
 * 弹层里列表的高度上限：半屏。
 *
 * 这里踩过坑（2026-09-11 真机）：预设列表曾经是一个不受限的 `forEach`，20 个预设把
 * 弹层整个顶了下去——「填入草稿」按钮落在屏幕外，用户翻不到，于是「随机填充点了没反应」。
 * 改成「列表自己滚、按钮钉在外面」，并且给列表一个**跟着屏幕走**的上限：写死 420dp
 * 在 411dp 高的手机上仍然会把下面的按钮挤出屏幕。
 */
@Composable
internal fun sheetListMaxHeight() = (LocalConfiguration.current.screenHeightDp / 2).dp

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}

/**
 * 预设弹层搬去了 [com.pict.metatool.ui.preset.PresetPickerSheet]：那里是**分栏多选**
 * （设备 / 位置 / 时间 / 混合四栏并列，可同时挑），编辑页和批量页共用同一套；
 * 「自己加一个预设」的表单在 [com.pict.metatool.ui.preset.UserPresetEditorSheet]。
 * 这个文件只留随机填充的弹层与 [sheetListMaxHeight]。
 */

/**
 * 随机填充：挑预设 → 勾字段 → 定种子 → **生成预览** → 确认填入。
 *
 * 「种子」是显式且可见的：同一张图 + 同一预设 + 同一种子必然得到同一批值（docs/07 T3.6）。
 * 「换一批」只是换一个种子，不是引入不可复现的随机。
 *
 * 分两步而不是「一按就填」，是因为填充一次动的是几十个字段（docs/06 §3.3：
 * 「显示将写入的字段 diff」）：先让用户看清要写什么，再决定要不要。预览只算不写，
 * 所以「返回修改」没有任何代价。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RandomFillSheet(
    state: EditUiState,
    onChoosePreset: (String) -> Unit,
    onToggleKey: (com.pict.metatool.domain.model.TagKey) -> Unit,
    onRerollSeed: () -> Unit,
    onPreview: () -> Unit,
    onClearPreview: () -> Unit,
    onFill: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 整屏展开，不停在半屏：这些弹层是「可滚的列表 + 钉在下面的按钮」，
    // 停在半屏展开的位置时按钮正好落在屏幕外，翻都翻不到（2026-09-11 真机踩到）。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = stringResource(R.string.edit_random_title), style = MaterialTheme.typography.titleMedium)

            val preview = state.randomFillPreview
            if (preview == null) {
                RandomFillPicker(
                    state = state,
                    onChoosePreset = onChoosePreset,
                    onToggleKey = onToggleKey,
                    onRerollSeed = onRerollSeed,
                    onPreview = onPreview,
                )
            } else {
                RandomFillPreview(rows = preview, onBack = onClearPreview, onConfirm = onFill)
            }
        }
    }
}

/** 第一步：挑预设、勾字段、定种子。 */
@Composable
private fun RandomFillPicker(
    state: EditUiState,
    onChoosePreset: (String) -> Unit,
    onToggleKey: (com.pict.metatool.domain.model.TagKey) -> Unit,
    onRerollSeed: () -> Unit,
    onPreview: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = sheetListMaxHeight()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.edit_random_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { SectionLabel(stringResource(R.string.edit_random_preset)) }

            items(items = state.presets, key = { "preset:" + it.id }) { preset ->
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

            item {
                Column {
                    SectionLabel(stringResource(R.string.edit_random_fields))
                    Text(
                        text = stringResource(R.string.edit_random_fixed_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            val candidates = state.randomFillCandidates
            if (candidates.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.edit_random_no_candidates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(items = candidates, key = { "field:" + it.key.full }) { spec ->
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
            modifier = Modifier.fillMaxWidth(),
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

        Button(
            onClick = onPreview,
            enabled = state.randomFillReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.edit_random_preview))
        }
    }
}

/** 第二步：看清「将要写入」的字段 diff，再决定填不填。 */
@Composable
private fun RandomFillPreview(
    rows: List<EditDiffRow>,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = sheetListMaxHeight()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SectionLabel(stringResource(R.string.edit_random_preview_title)) }

            if (rows.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.edit_random_preview_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.edit_random_preview_count, rows.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(items = rows, key = { "row:" + it.key.full }) { row -> DiffRowView(row) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.edit_random_preview_back))
            }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
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
    // 整屏展开，不停在半屏：这些弹层是「可滚的列表 + 钉在下面的按钮」，
    // 停在半屏展开的位置时按钮正好落在屏幕外，翻都翻不到（2026-09-11 真机踩到）。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = sheetListMaxHeight())) {
                    items(items = fields, key = { it.key.full }) { spec ->
                        AddRow(spec = spec, onAdd = { onPick(spec) })
                    }
                }
            }
        }
    }
}
