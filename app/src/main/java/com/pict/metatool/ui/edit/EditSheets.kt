package com.pict.metatool.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pict.metatool.R
import com.pict.metatool.domain.model.TagKey

/**
 * 编辑页的浮层：字段编辑弹层（docs/06 §3.3）、改动预览（diff）、未保存提示。
 *
 * 弹层只负责收集输入与展示，校验和草稿状态都在 [EditUiState] / [EditFieldInput] 里，
 * 这里不判断「值合不合法」——输入错了由 `state.inputError` 带出来。
 */

/** 字段编辑弹层：输入框 + 类型键盘 + 校验提示 + 恢复原值。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FieldSheet(
    state: EditUiState,
    key: TagKey,
    onInput: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
) {
    val spec = state.editingSpec
    val label = state.editingLabel ?: key.full

    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = key.full,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            spec?.let {
                Text(
                    text = stringResource(R.string.edit_sheet_format, it.type.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = stringResource(
                    R.string.edit_sheet_current,
                    state.editingOriginal ?: stringResource(R.string.edit_sheet_empty),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                isError = state.inputError != null,
                maxLines = 3,
                label = { Text(text = label) },
                placeholder = { Text(text = state.editingHint) },
                supportingText = {
                    Text(text = state.inputError ?: state.editingHint)
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardFor(state.editingKind)),
            )

            Text(
                text = stringResource(R.string.edit_sheet_clear_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onRestore,
                    enabled = state.editingChanged,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.edit_sheet_restore))
                }
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.edit_sheet_cancel))
                }
                Button(onClick = onCommit, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.edit_sheet_confirm))
                }
            }
        }
    }
}

/** 改动预览：折叠之后的 diff，原值 → 新值。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewSheet(
    state: EditUiState,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    // 整屏展开，不停在半屏：列表下面的按钮得留在屏幕上（改动的行数与按钮同屏才好看）。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.edit_preview_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.edit_preview_count, state.dirtyCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.planFailure?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.verifySummary?.let { summary ->
                Text(
                    text = stringResource(R.string.edit_preview_verify, summary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (state.diffRows.isEmpty()) {
                Text(
                    text = stringResource(R.string.edit_preview_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = sheetListMaxHeight())) {
                    items(items = state.diffRows, key = { it.key.full }) { row ->
                        DiffRowView(row = row)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.edit_sheet_cancel))
                }
                Button(
                    onClick = onApply,
                    enabled = state.canApply,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.edit_apply, state.dirtyCount))
                }
            }
        }
    }
}

@Composable
internal fun DiffRowView(row: EditDiffRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(diffKindLabel(row.kind)),
                style = MaterialTheme.typography.labelSmall,
                color = when (row.kind) {
                    EditDiffKind.ADDED -> MaterialTheme.colorScheme.primary
                    EditDiffKind.CHANGED -> MaterialTheme.colorScheme.tertiary
                    EditDiffKind.CLEARED -> MaterialTheme.colorScheme.error
                },
            )
        }
        Text(
            text = stringResource(
                R.string.edit_preview_arrow,
                row.before ?: stringResource(R.string.edit_preview_empty_value),
                row.after ?: stringResource(R.string.edit_preview_empty_value),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun diffKindLabel(kind: EditDiffKind): Int = when (kind) {
    EditDiffKind.ADDED -> R.string.edit_preview_added
    EditDiffKind.CHANGED -> R.string.edit_preview_changed
    EditDiffKind.CLEARED -> R.string.edit_preview_cleared
}

/** 未保存提示：返回前问一次，避免顺手退掉改了一半的东西。 */
@Composable
internal fun DiscardDialog(count: Int, onKeepEditing: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(text = stringResource(R.string.edit_discard_title, count)) },
        text = { Text(text = stringResource(R.string.edit_discard_body)) },
        confirmButton = {
            TextButton(onClick = onLeave) {
                Text(text = stringResource(R.string.edit_discard_leave))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(text = stringResource(R.string.edit_discard_keep))
            }
        },
    )
}

/** 输入类型 → 键盘。分数用文本键盘，因为要打 `/`。 */
private fun keyboardFor(kind: InputKind): KeyboardType = when (kind) {
    InputKind.INTEGER, InputKind.LIST_NUMBER -> KeyboardType.Number
    InputKind.DECIMAL -> KeyboardType.Decimal
    InputKind.DATETIME, InputKind.DATE, InputKind.TIME -> KeyboardType.Text
    else -> KeyboardType.Text
}
