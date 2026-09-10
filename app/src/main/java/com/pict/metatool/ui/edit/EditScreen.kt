package com.pict.metatool.ui.edit

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pict.metatool.R
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.model.TagKey

/**
 * 单文件编辑页（docs/06 §3.3 / docs/07 T2.10）。
 *
 * 结构：顶栏（返回 / 预览改动 / 应用 N 项）、搜索框、按字段分组的值列表、
 * 未保存提示条；改动落在 [EditUiState.draft] 里，点「应用」才写文件。
 * 返回键与左上角返回都走同一道闸：有未保存改动就先问一次（docs/06 §3.3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    uri: String,
    onBack: () -> Unit,
    viewModel: EditViewModel = viewModel(factory = EditViewModel.factory(LocalContext.current)),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uri) { viewModel.load(uri) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.consumeMessage()
    }

    // 有改动就先问，不直接退（未保存提示）。
    val leave: () -> Unit = { if (state.isDirty) viewModel.requestDiscard() else onBack() }
    val sheetOpen = state.editing != null || state.showPreview || state.confirmDiscard ||
        state.showPresets || state.showRandomFill || state.showAddField
    BackHandler(enabled = !sheetOpen) { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.edit_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.edit_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openPreview() }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.edit_preview_open),
                        )
                    }
                    Button(
                        onClick = { viewModel.apply() },
                        enabled = state.canApply,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            text = if (state.isDirty) {
                                stringResource(R.string.edit_apply, state.dirtyCount)
                            } else {
                                stringResource(R.string.edit_apply_idle)
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.isDirty) {
                DirtyBar(count = state.dirtyCount, onRevertAll = { viewModel.revertAll() })
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SearchField(query = state.query, onQueryChange = viewModel::onQueryChange)

            if (state.metadata != null && state.canWriteInPlace) {
                EditToolsRow(
                    onPresets = { viewModel.openPresets() },
                    onRandomFill = { viewModel.openRandomFill() },
                    onAddField = { viewModel.openAddField() },
                )
            }

            val formatName = state.source?.format?.name.orEmpty()
            val error = state.error
            when {
                state.isLoading -> LoadingBlock()

                error != null -> ErrorBlock(
                    messageRes = error.messageRes,
                    detail = state.errorDetail,
                    onRetry = { viewModel.retry() },
                )

                !state.canWriteInPlace -> NoticeBlock(
                    text = stringResource(R.string.edit_readonly_format, formatName),
                )

                state.metadata == null -> EmptyBlock()

                else -> EditList(
                    state = state,
                    onEdit = { key -> viewModel.edit(key) },
                    onAdd = { spec -> viewModel.addField(spec) },
                )
            }
        }
    }

    state.editing?.let { key ->
        FieldSheet(
            state = state,
            key = key,
            onInput = viewModel::onInput,
            onCommit = { viewModel.commitInput() },
            onCancel = { viewModel.cancelInput() },
            onRestore = {
                viewModel.revert(key)
                viewModel.cancelInput()
            },
        )
    }

    if (state.showPreview) {
        PreviewSheet(
            state = state,
            onDismiss = { viewModel.closePreview() },
            onApply = {
                viewModel.closePreview()
                viewModel.apply()
            },
        )
    }

    if (state.confirmDiscard) {
        DiscardDialog(
            count = state.dirtyCount,
            onKeepEditing = { viewModel.cancelDiscard() },
            onLeave = {
                viewModel.cancelDiscard()
                onBack()
            },
        )
    }

    if (state.showPresets) {
        PresetSheet(
            state = state,
            onToggleOverwrite = { viewModel.setPresetOverwrite(it) },
            onApply = { presetId -> viewModel.applyPreset(presetId) },
            onDismiss = { viewModel.closePresets() },
        )
    }

    if (state.showRandomFill) {
        RandomFillSheet(
            state = state,
            onChoosePreset = { presetId -> viewModel.chooseRandomFillPreset(presetId) },
            onToggleKey = { key -> viewModel.toggleRandomFillKey(key) },
            onRerollSeed = { viewModel.rerollRandomFillSeed() },
            onFill = { viewModel.fillRandom() },
            onDismiss = { viewModel.closeRandomFill() },
        )
    }

    if (state.showAddField) {
        AddFieldSheet(
            state = state,
            onQueryChange = { viewModel.onAddFieldQueryChange(it) },
            onPick = { spec ->
                viewModel.addField(spec)
                viewModel.closeAddField()
            },
            onDismiss = { viewModel.closeAddField() },
        )
    }
}

/**
 * 编辑页的工具条：预设 / 随机填充 / 添加字段（docs/06 §3.3「快速填充」）。
 *
 * 放在搜索框下面而不是折进溢出菜单：这三件事是「不想一项项手改」时的一等需求，
 * 藏起来等于没有。
 */
@Composable
private fun EditToolsRow(
    onPresets: () -> Unit,
    onRandomFill: () -> Unit,
    onAddField: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onPresets, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.edit_tools_preset))
        }
        OutlinedButton(onClick = onRandomFill, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.edit_tools_random))
        }
        OutlinedButton(onClick = onAddField, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.edit_tools_add_field))
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text(text = stringResource(R.string.edit_search_hint)) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.detail_search_close),
                    )
                }
            }
        },
    )
}

@Composable
private fun EditList(
    state: EditUiState,
    onEdit: (TagKey) -> Unit,
    onAdd: (FieldSpec) -> Unit,
) {
    val sections = state.visibleSections
    val additions = state.additions

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (sections.isEmpty() && additions.isEmpty()) {
            item { NoticeBlock(text = stringResource(R.string.edit_search_none, state.query), textAlign = TextAlign.Center) }
        }

        items(items = sections, key = { it.title }) { section ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                section.rows.forEach { row -> FieldRow(row = row, onEdit = { onEdit(row.key) }) }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (additions.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.edit_search_add_more, additions.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(items = additions, key = { it.key.full }) { spec ->
                AddRow(spec = spec, onAdd = { onAdd(spec) })
            }
        }
    }
}

@Composable
private fun FieldRow(row: EditFieldRow, onEdit: () -> Unit) {
    val valueColor = if (row.dirty) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (row.editable) Modifier.clickable(onClick = onEdit) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.label, style = MaterialTheme.typography.bodyMedium)
                if (row.dirty) {
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text(
                        text = stringResource(R.string.edit_row_dirty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = row.display,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        if (row.editable) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.edit_row_edit, row.label),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.edit_row_locked),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp),
            )
        }
    }
}

/** 搜索时列出的、本文件还没有的目录字段（点了就补一个）；「添加字段」弹层复用同一行。 */
@Composable
internal fun AddRow(spec: FieldSpec, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = spec.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = spec.key.full,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAdd) { Text(text = stringResource(R.string.edit_search_add)) }
    }
}

@Composable
private fun DirtyBar(count: Int, onRevertAll: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.edit_dirty_bar, count),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRevertAll) {
                Text(text = stringResource(R.string.edit_action_revert_all))
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.edit_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBlock(messageRes: Int, detail: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        detail?.let {
            Text(
                text = stringResource(R.string.detail_error_detail, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(onClick = onRetry) { Text(text = stringResource(R.string.edit_retry)) }
    }
}

@Composable
private fun NoticeBlock(text: String, textAlign: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun EmptyBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.detail_empty_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.detail_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
    }
}
