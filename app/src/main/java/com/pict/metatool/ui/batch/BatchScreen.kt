package com.pict.metatool.ui.batch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pict.metatool.R
import com.pict.metatool.domain.batch.BatchMode
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.batch.ChangeKind
import com.pict.metatool.domain.batch.FieldChange
import com.pict.metatool.domain.batch.ItemPreview
import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.ui.theme.PictSpacing
import kotlinx.coroutines.launch

/**
 * 批量编辑页（docs/06 §3.8、docs/07 T5.4 / T5.5）。
 *
 * 一个页面两步：先定「这一批做什么」，再点预览看「会发生什么」。
 * 预览这一步**只读**（FR-32）——它拿不到任何写方法，因为读侧接口
 * `BatchSourceReader` 上就没声明过 `write`。
 *
 * 执行按钮只到提示为止：真跑要等任务队列（WorkManager，T5.3）接上，
 * 现在能保证的是「计划与预览算得准」。
 *
 * @param targets 从图库带过来的这批图（URI + 来源快照）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    targets: List<BatchTarget>,
    onBack: () -> Unit,
    viewModel: BatchViewModel = viewModel(
        factory = BatchViewModel.factory(LocalContext.current, targets),
    ),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pendingText = stringResource(R.string.batch_execute_pending)

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // 预览是页内的第二步，返回键先退到「改一改」，再退才是离开这一页。
    BackHandler {
        if (state.step == BatchStep.PREVIEW) viewModel.backToEdit() else onBack()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (state.step == BatchStep.PREVIEW) R.string.batch_preview_title
                            else R.string.batch_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.step == BatchStep.PREVIEW) viewModel.backToEdit() else onBack()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.batch_preview_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (state.step) {
            BatchStep.EDIT -> BatchEditStep(
                state = state,
                contentPadding = innerPadding,
                viewModel = viewModel,
            )

            BatchStep.PREVIEW -> BatchPreviewStep(
                state = state,
                contentPadding = innerPadding,
                viewModel = viewModel,
                onExecute = { scope.launch { snackbarHostState.showSnackbar(pendingText) } },
            )
        }
    }
}

/** 第一步：定这一批做什么。 */
@Composable
private fun BatchEditStep(
    state: BatchUiState,
    contentPadding: PaddingValues,
    viewModel: BatchViewModel,
) {
    var pickingPreset by remember { mutableStateOf(false) }
    val selectedPreset = state.draft.presetId?.let { id -> state.presets.firstOrNull { it.id == id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PictSpacing.screenHorizontal,
            end = PictSpacing.screenHorizontal,
            top = contentPadding.calculateTopPadding() + PictSpacing.sm,
            bottom = PictSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(PictSpacing.listGap),
    ) {
        item {
            Text(
                text = if (state.hasTargets) {
                    stringResource(R.string.batch_subtitle, state.targets.size)
                } else {
                    stringResource(R.string.batch_no_targets)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SectionTitle(stringResource(R.string.batch_section_action)) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm)) {
                BatchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.draft.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
        }

        if (state.draft.mode == BatchMode.CLEAR) {
            item { SectionTitle(stringResource(R.string.batch_clear_label)) }
            items(ClearTarget.entries, key = { it.name }) { target ->
                ClearTargetRow(
                    target = target,
                    checked = target in state.draft.clearTargets,
                    onToggle = { viewModel.toggleClearTarget(target) },
                )
            }
            item {
                Text(
                    text = stringResource(R.string.batch_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item { SectionTitle(stringResource(R.string.batch_preset_label)) }
            item {
                PickerRow(
                    value = selectedPreset?.name ?: stringResource(R.string.batch_preset_empty),
                    filled = selectedPreset != null,
                    onClick = { pickingPreset = true },
                )
            }
            if (state.draft.supportsOverwrite) {
                item {
                    SwitchRow(
                        label = stringResource(R.string.batch_overwrite),
                        hint = stringResource(R.string.batch_overwrite_hint),
                        checked = state.draft.overwriteExisting,
                        onToggle = viewModel::setOverwrite,
                    )
                }
            }
            if (state.draft.mode == BatchMode.RANDOM) {
                item {
                    Text(
                        text = stringResource(R.string.batch_random_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(PictSpacing.xs))
            Button(
                onClick = viewModel::preview,
                enabled = state.canPreview,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.batch_preview_action))
            }
        }

        if (state.isPreviewing) {
            val (done, total) = state.progress ?: (0 to state.targets.size)
            item {
                Column {
                    Text(
                        text = stringResource(R.string.batch_preview_progress, done, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (pickingPreset) {
        PresetPickerSheet(
            presets = state.presets,
            selectedId = state.draft.presetId,
            onSelect = { id ->
                viewModel.setPreset(id)
                pickingPreset = false
            },
            onDismiss = { pickingPreset = false },
        )
    }
}

/** 第二步：这一批会发生什么。 */
@Composable
private fun BatchPreviewStep(
    state: BatchUiState,
    contentPadding: PaddingValues,
    viewModel: BatchViewModel,
    onExecute: () -> Unit,
) {
    val stats = state.stats

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PictSpacing.screenHorizontal,
            end = PictSpacing.screenHorizontal,
            top = contentPadding.calculateTopPadding() + PictSpacing.sm,
            bottom = PictSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(PictSpacing.listGap),
    ) {
        if (stats != null) {
            item { StatsCard(stats) }
        }

        item {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PreviewFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text("${filter.label} ${state.countOf(filter)}") },
                        )
                    }
                }
            }
        }

        val visible = state.visibleItems
        if (visible.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.batch_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(visible, key = { it.target.uri }) { item ->
                ItemCard(
                    item = item,
                    expanded = item.target.uri in state.expanded,
                    onToggleExpand = { viewModel.toggleExpanded(item.target.uri) },
                )
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = viewModel::backToEdit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.batch_preview_back))
                }
                Button(onClick = onExecute, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.batch_execute))
                }
            }
        }
    }
}

/** 统计区：这一批的量级 + 改得最多的字段 + 没填上的原因。 */
@Composable
private fun StatsCard(stats: PreviewStats) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(PictSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.batch_stat_changed, stats.changed) +
                    " · " + stringResource(R.string.batch_stat_unchanged, stats.unchanged) +
                    (if (stats.blocked > 0) " · " + stringResource(R.string.batch_stat_blocked, stats.blocked) else ""),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.batch_stat_changes, stats.changes) +
                    (if (stats.segments > 0) " · " + stringResource(R.string.batch_stat_segments, stats.segments) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.added + stats.modified + stats.removed > 0) {
                Text(
                    text = stringResource(R.string.batch_stat_kinds, stats.added, stats.modified, stats.removed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.topKeys.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.batch_stat_top_keys,
                        stats.topKeys.joinToString("、") { (label, count) -> "$label $count" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.skips.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.batch_stat_skips,
                        stats.skips.joinToString("、") { (reason, count) -> "$reason $count" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.batch_preview_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单个文件：一行摘要，展开才看字段明细。 */
@Composable
private fun ItemCard(
    item: ItemPreview,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(PictSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.target.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = itemSummary(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.isBlocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (item.changes.isNotEmpty()) {
                    IconButton(onClick = onToggleExpand) {
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Filled.KeyboardArrowUp
                            } else {
                                Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = item.target.displayName,
                        )
                    }
                }
            }

            if (item.isBlocked) {
                Text(
                    text = item.blockedDetail ?: item.blocked?.code.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.isNoop) {
                Text(
                    text = stringResource(R.string.batch_item_noop),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.keptKeys.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.batch_item_kept, item.keptKeys.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.skipped.firstOrNull()?.let { skip ->
                Text(
                    text = stringResource(R.string.batch_item_skipped, item.skipped.size, skip.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.segmentClears.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.batch_item_segments, item.segmentClears.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = PictSpacing.sm))
                item.changes.forEach { change -> ChangeRow(change) }
            }
        }
    }
}

/** 字段明细的一行：`标签 · 类型 · 前 → 后`。 */
@Composable
private fun ChangeRow(change: FieldChange) {
    val spec = FieldCatalog.spec(change.key)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PictSpacing.xxs),
    ) {
        Text(
            text = spec?.label ?: change.key.full,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = change.kind.label,
            style = MaterialTheme.typography.bodySmall,
            color = kindColor(change.kind),
        )
        Text(
            text = " " + TagValueFormatter.format(change.before, spec) +
                " → " + TagValueFormatter.format(change.after, spec),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun kindColor(kind: ChangeKind) = when (kind) {
    ChangeKind.ADDED -> MaterialTheme.colorScheme.primary
    ChangeKind.MODIFIED -> MaterialTheme.colorScheme.tertiary
    ChangeKind.REMOVED -> MaterialTheme.colorScheme.error
}

private fun itemSummary(item: ItemPreview): String = when {
    item.isBlocked -> "不支持"
    item.changes.isEmpty() -> "无变化"
    else -> "将修改 ${item.changes.size}"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PickerRow(value: String, filled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(PictSpacing.lg),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (filled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun ClearTargetRow(target: ClearTarget, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(text = target.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = target.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 预设选择：20 个预设用列表比下拉稳当（名字长短差得多，下拉里会截断）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPickerSheet(
    presets: List<Preset>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.batch_preset_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                start = PictSpacing.screenHorizontal,
                end = PictSpacing.screenHorizontal,
                bottom = PictSpacing.sm,
            ),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = PictSpacing.xl)) {
            items(presets, key = { it.id }) { preset ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(preset.id) }
                        .padding(
                            horizontal = PictSpacing.screenHorizontal,
                            vertical = PictSpacing.md,
                        ),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = preset.name, style = MaterialTheme.typography.bodyLarge)
                        preset.description?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (preset.id == selectedId) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
