package com.pict.metatool.ui.preset

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pict.metatool.R
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.ui.theme.PictSpacing

/**
 * 预设管理页（设置 → 预设管理）。
 *
 * 一页看全内置 + 自建的预设：点一行展开字段清单，自建的能删，内置的只能看。
 * 删的只有应用数据里那一份，内置预设钉在安装包里（docs/03 §7）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetManageScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PresetManageViewModel = viewModel(
        factory = PresetManageViewModel.factory(LocalContext.current),
    ),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 回执文本在组合期就定下来（LaunchedEffect 里不能调 stringResource）。
    val eventText = state.event?.let { event ->
        when (event) {
            is PresetManageEvent.Deleted -> stringResource(R.string.preset_manage_deleted, event.name)
            is PresetManageEvent.DeleteFailed ->
                stringResource(R.string.preset_manage_delete_failed, event.name)

            is PresetManageEvent.Saved -> if (event.isNew) {
                stringResource(R.string.preset_manage_saved_new, event.name)
            } else {
                stringResource(R.string.preset_manage_saved_edit, event.name)
            }
        }
    }
    LaunchedEffect(eventText) {
        if (eventText != null) {
            snackbarHostState.showSnackbar(eventText)
            viewModel.consumeEvent()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preset_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.preset_manage_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 「自己加一份」摆在这一页上：管理页本来就是要加/删/改的地方，入口散在编辑页那边，
        // 用户得先挑张图进编辑页才能建预设——那句话就是为这个提的。
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.addPreset() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.preset_manage_add)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = PictSpacing.screenHorizontal,
                end = PictSpacing.screenHorizontal,
                top = PictSpacing.sm,
                bottom = PictSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.md),
        ) {
            item(key = "preset-manage-summary") {
                PresetManageSummary(builtinCount = state.builtinCount, userCount = state.userCount)
            }

            state.builtinIssues.firstOrNull()?.let { issue ->
                item(key = "preset-manage-builtin-issue") {
                    PresetManageIssue(
                        text = stringResource(R.string.preset_manage_builtin_issue, issue),
                        isError = false,
                    )
                }
            }
            state.userIssues.firstOrNull()?.let { issue ->
                item(key = "preset-manage-user-issue") {
                    PresetManageIssue(
                        text = stringResource(R.string.preset_manage_user_issue, issue),
                        isError = true,
                    )
                }
            }

            if (state.groups.isEmpty()) {
                item(key = "preset-manage-empty") {
                    PresetManageIssue(
                        text = stringResource(R.string.preset_manage_empty),
                        isError = true,
                    )
                }
            }

            state.groups.forEach { group ->
                item(key = "preset-manage-kind-${group.kind.id}") {
                    PresetManageKindHeader(kind = group.kind, count = group.presets.size)
                }
                items(items = group.presets, key = { it.id }) { preset ->
                    PresetManageRow(
                        preset = preset,
                        detail = state.expanded?.takeIf { it.id == preset.id },
                        onToggle = { viewModel.toggle(preset) },
                        onEdit = { viewModel.editPreset(preset) },
                        onDelete = { viewModel.askDelete(preset) },
                    )
                }
            }
        }
    }

    state.pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.preset_manage_delete_title)) },
            text = { Text(stringResource(R.string.preset_manage_delete_body, preset.name)) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.preset_manage_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.preset_manage_cancel))
                }
            },
        )
    }

    // 表单是编辑页、批量页那份（同一个 `UserPresetEditorSheet`）：三个入口一张表，
    // 建出来的东西形态一致，校验规则也只有一套。
    state.editor?.let { draft ->
        UserPresetEditorSheet(
            initial = draft,
            message = state.editorMessage,
            onSave = viewModel::saveEditor,
            onDelete = viewModel::askDeleteById,
            onDismiss = viewModel::dismissEditor,
        )
    }
}

/** 抬头那张卡：两边各有多少份，以及「自建的会跟着应用数据一起没」。 */
@Composable
private fun PresetManageSummary(builtinCount: Int, userCount: Int) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(PictSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.preset_manage_counts, builtinCount, userCount),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.preset_manage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行提示（内置/自建的坏文件）。错误态用红字，警告态用次要色。 */
@Composable
private fun PresetManageIssue(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = PictSpacing.xs),
    )
}

/** 类别小标题：设备 / 位置 / 时间 / 混合，右边跟这一栏有几份。 */
@Composable
private fun PresetManageKindHeader(kind: PresetKind, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PictSpacing.xs, top = PictSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kind.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.preset_manage_kind_count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一份预设：收起时看名称/来源/字段数/说明，展开后加字段清单、约束与删除入口。 */
@Composable
private fun PresetManageRow(
    preset: Preset,
    detail: PresetDetail?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            // 圆角要在 clickable 之前，否则按压高亮是直角（同 SettingsSwitchRow 的说明）。
            .clip(shape)
            .clickable(onClick = onToggle),
    ) {
        Column(
            modifier = Modifier.padding(PictSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                PresetOriginBadge(preset.origin)
            }

            Text(
                text = preset.id + " · " +
                    stringResource(R.string.preset_manage_fields, preset.keyCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            preset.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (detail == null) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (detail != null) {
                preset.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                    PresetManageMetaLine(stringResource(R.string.preset_manage_tags, tags.joinToString("、")))
                }
                preset.author?.let { author ->
                    PresetManageMetaLine(stringResource(R.string.preset_manage_author, author))
                }
                preset.license?.let { license ->
                    PresetManageMetaLine(stringResource(R.string.preset_manage_license, license))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(PictSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(PictSpacing.xs),
                ) {
                    detail.fields.forEach { field -> PresetManageFieldRow(field) }
                }

                detail.constraints.forEach { constraint ->
                    PresetManageMetaLine(stringResource(R.string.preset_manage_constraint, constraint))
                }

                if (preset.origin == PresetOrigin.USER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PictSpacing.xs)) {
                        TextButton(onClick = onEdit) {
                            Text(stringResource(R.string.preset_manage_edit))
                        }
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(R.string.preset_manage_delete))
                        }
                    }
                }
            }
        }
    }
}

/** 字段一行：左边中文名 + TagKey（两行小字），右边规则摘要。 */
@Composable
private fun PresetManageFieldRow(field: PresetFieldRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = field.key.full,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = field.rule,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = PictSpacing.sm),
        )
    }
}

@Composable
private fun PresetManageMetaLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 来源徽标：自建用主色底（自己加的，一眼认出来），内置用中性底。 */
@Composable
private fun PresetOriginBadge(origin: PresetOrigin) {
    // 内置走 secondaryContainer、自建走 primaryContainer：两个都要一眼看出是标签，
    // 别用 surfaceVariant —— 它跟卡片底几乎同色，「内置」会退化成一行普通文字。
    val isUser = origin == PresetOrigin.USER
    Text(
        text = origin.label,
        style = MaterialTheme.typography.labelSmall,
        color = if (isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            )
            .padding(horizontal = PictSpacing.sm, vertical = PictSpacing.xxs),
    )
}
