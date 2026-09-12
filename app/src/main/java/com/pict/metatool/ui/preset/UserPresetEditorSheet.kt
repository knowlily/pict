package com.pict.metatool.ui.preset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pict.metatool.R
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldSpec
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.UserFieldInput
import com.pict.metatool.domain.preset.UserPresetInput
import com.pict.metatool.ui.edit.sheetListMaxHeight

/**
 * 「自己加一个预设」的表单（新建 / 改自己那份）。
 *
 * 表单状态留在**这个 composable 里**，不进 ViewModel：它是一次性的草稿，
 * 关掉就该消失，没有跨屏/跨进程活着的理由；ViewModel 只收「保存这一份」这一个动作。
 *
 * 只给自己加的预设开口子：内置那些在安装包里，改了没地方存（也说不清改了算谁的）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserPresetEditorSheet(
    initial: UserPresetInput,
    message: String?,
    onSave: (UserPresetInput) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember(initial) { mutableStateOf(initial) }
    var pickingRow by remember(initial) { mutableStateOf(-1) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val row = pickingRow
            if (row >= 0) {
                Text(
                    text = stringResource(R.string.preset_editor_pick_field),
                    style = MaterialTheme.typography.titleSmall,
                )
                FieldPickerList(
                    onPick = { spec ->
                        input = input.withRowKey(row, spec.key)
                        pickingRow = -1
                    },
                )
                TextButton(onClick = { pickingRow = -1 }) {
                    Text(text = stringResource(R.string.preset_editor_back))
                }
            } else {
                PresetEditorForm(
                    input = input,
                    message = message,
                    onInput = { input = it },
                    onPickField = { pickingRow = it },
                    onSave = { onSave(input) },
                    onDelete = onDelete,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun PresetEditorForm(
    input: UserPresetInput,
    message: String?,
    onInput: (UserPresetInput) -> Unit,
    onPickField: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Text(
        text = stringResource(
            if (input.isNew) R.string.preset_editor_new_title else R.string.preset_editor_edit_title,
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.preset_editor_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = input.name,
        onValueChange = { onInput(input.copy(name = it)) },
        label = { Text(text = stringResource(R.string.preset_editor_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = stringResource(R.string.preset_editor_kind),
        style = MaterialTheme.typography.labelLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetKind.entries.forEach { kind ->
            FilterChip(
                selected = input.kind == kind,
                onClick = { onInput(input.copy(kind = kind)) },
                label = { Text(text = kind.label) },
            )
        }
    }

    OutlinedTextField(
        value = input.description,
        onValueChange = { onInput(input.copy(description = it)) },
        label = { Text(text = stringResource(R.string.preset_editor_desc)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = stringResource(R.string.preset_editor_fields),
        style = MaterialTheme.typography.labelLarge,
    )
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = sheetListMaxHeight())) {
        itemsIndexed(items = input.rows) { index, row ->
            FieldRow(
                row = row,
                onPickField = { onPickField(index) },
                onValue = { onInput(input.withRowValue(index, it)) },
                onRemove = { onInput(input.removeRow(index)) },
            )
            HorizontalDivider()
        }
        item {
            TextButton(onClick = { onInput(input.addRow()) }) {
                Text(text = stringResource(R.string.preset_editor_add_row))
            }
        }
    }

    if (input.preserved.isNotEmpty()) {
        Text(
            text = stringResource(R.string.preset_editor_preserved, input.preserved.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    val problem = input.validate()
    val hint = message ?: problem
    if (hint != null) {
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        input.id?.let { id ->
            TextButton(onClick = { onDelete(id) }) {
                Text(
                    text = stringResource(R.string.preset_editor_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.preset_editor_cancel)) }
        Button(onClick = onSave, enabled = problem == null) {
            Text(text = stringResource(R.string.preset_editor_save))
        }
    }
}

/** 一行：选字段（显示中文名）+ 值输入 + 删掉这行。 */
@Composable
private fun FieldRow(
    row: UserFieldInput,
    onPickField: () -> Unit,
    onValue: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val spec = row.key?.let { FieldCatalog.spec(it) }
            TextButton(onClick = onPickField) {
                Text(
                    text = spec?.label ?: row.key?.full
                        ?: stringResource(R.string.preset_editor_pick_field),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) {
                Text(
                    text = stringResource(R.string.preset_editor_remove_row),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        row.key?.let { key ->
            Text(
                text = key.full,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        OutlinedTextField(
            value = row.valueText,
            onValueChange = onValue,
            label = { Text(text = stringResource(R.string.preset_editor_value)) },
            supportingText = { Text(text = stringResource(R.string.preset_editor_value_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 字段选择：只列可写字段，按中文名或全名搜。 */
@Composable
private fun FieldPickerList(onPick: (FieldSpec) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { FieldCatalog.all.filter { it.canEdit } }
    val matched = remember(query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.lowercase().contains(needle) || it.key.full.lowercase().contains(needle)
            }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(text = stringResource(R.string.preset_editor_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = sheetListMaxHeight())) {
        items(items = matched, key = { it.key.full }) { spec ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(spec) }
                    .padding(vertical = 8.dp),
            ) {
                Text(text = spec.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = spec.key.full,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider()
        }
    }
}
