package com.pict.metatool.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pict.metatool.R
import com.pict.metatool.domain.model.ImageItem
import com.pict.metatool.ui.components.PlaceholderPane
import com.pict.metatool.ui.theme.PictSpacing
import com.pict.metatool.ui.theme.PictTheme
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.platform.LocalContext

/**
 * 图库页（docs/06 §3.1、docs/07 T1.9）。
 *
 * 三条导入入口都在这里发意图，真正的 URI 查询/目录遍历在 [LibraryViewModel] → `SafSource`：
 * - 选择图片：`ACTION_OPEN_DOCUMENT` 多选；
 * - 选择文件夹：`ACTION_OPEN_DOCUMENT_TREE`，持久化授权后扫描；
 * - 从相册选择：Photo Picker（系统无该能力时由 `ActivityResultContracts` 自动退化）。
 *
 * 与文档一致：单击进详情（T1.10 接入），长按进入多选（额外给一次触感反馈）。
 *
 * @param onOpen 点开某张图，由导航层跳到详情页。
 */
@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.factory(LocalContext.current),
    ),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importUris(uris, ImageItem.Origin.FILE_PICKER) }

    val pickPhotos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.importUris(uris, ImageItem.Origin.PHOTO_PICKER) }
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri -> treeUri?.let(viewModel::importFolder) }

    val message = state.message
    val messageText = message?.let { libraryMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.consumeMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryHeader(
                state = state,
                onPickFiles = { pickFiles.launch(FILE_PICKER_MIME_TYPES) },
                onPickFolder = { pickFolder.launch(null) },
                onPickPhotos = {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onToggleSelectAll = viewModel::toggleSelectAll,
                onClearSelection = viewModel::clearSelection,
                onRemoveSelected = viewModel::removeSelected,
            )

            if (state.isScanning) {
                ScanBanner(found = state.scannedCount, onCancel = viewModel::cancelScan)
            }

            when {
                state.showEmptyState -> LibraryEmptyState(
                    onPickFiles = { pickFiles.launch(FILE_PICKER_MIME_TYPES) },
                    onPickFolder = { pickFolder.launch(null) },
                    modifier = Modifier.weight(1f),
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PictSpacing.screenHorizontal,
                        end = PictSpacing.screenHorizontal,
                        top = PictSpacing.sm,
                        bottom = PictSpacing.aboveBottomBar,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(PictSpacing.md),
                ) {
                    items(items = state.items, key = { it.uri }) { item ->
                        ImageGridCell(
                            item = item,
                            selected = item.uri in state.selectedUris,
                            onClick = { onOpen(item.uri) },
                            onLongClick = { viewModel.toggleSelection(item.uri) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(PictSpacing.lg),
        )
    }
}

/** 顶部标题 + 计数 + 导入入口（docs/06 §3.1 线框）。 */
@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onPickPhotos: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PictSpacing.screenHorizontal, vertical = PictSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            if (state.hasSelection) {
                TextButton(onClick = onRemoveSelected) {
                    Text(text = stringResource(R.string.library_action_remove))
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.library_action_import),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_import_files)) },
                        onClick = {
                            menuExpanded = false
                            onPickFiles()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_import_folder)) },
                        onClick = {
                            menuExpanded = false
                            onPickFolder()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_import_photos)) },
                        onClick = {
                            menuExpanded = false
                            onPickPhotos()
                        },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.hasSelection) {
                    stringResource(R.string.library_count_selected, state.selectedCount, state.totalCount)
                } else {
                    stringResource(R.string.library_count_total, state.totalCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            if (state.items.isNotEmpty()) {
                TextButton(onClick = onToggleSelectAll) {
                    Text(
                        text = stringResource(
                            if (state.isAllSelected) R.string.library_action_deselect_all
                            else R.string.library_action_select_all,
                        ),
                    )
                }
            }
            if (state.hasSelection) {
                TextButton(onClick = onClearSelection) {
                    Text(text = stringResource(R.string.library_action_clear_selection))
                }
            }
        }
    }
}

/** 扫描进度条（docs/06 §5：长任务在 App 内显示进度，可取消）。 */
@Composable
private fun ScanBanner(found: Int, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PictSpacing.screenHorizontal, vertical = PictSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.library_scanning, found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.library_scan_cancel))
            }
        }
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** 空状态：插画 + 一句话 + 两个按钮（docs/06 §3.1）。 */
@Composable
private fun LibraryEmptyState(
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PlaceholderPane(
            icon = Icons.Filled.Home,
            title = stringResource(R.string.library_empty_title),
            body = stringResource(R.string.library_empty_body),
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PictSpacing.screenHorizontal,
                    end = PictSpacing.screenHorizontal,
                    bottom = PictSpacing.lg,
                ),
            horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
        ) {
            Button(onClick = onPickFiles, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.library_import_files))
            }
            OutlinedButton(onClick = onPickFolder, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.library_import_folder))
            }
        }
    }
}

/** 一次性提示文案（docs/06 §6 错误文案三段式：发生了什么 → 为什么 → 怎么办）。 */
@Composable
private fun libraryMessageText(message: LibraryMessage): String = when (message) {
    LibraryMessage.ImportEmpty -> stringResource(R.string.library_message_import_empty)
    LibraryMessage.ImportFailed -> stringResource(R.string.library_message_import_failed)
    is LibraryMessage.ScanTruncated -> stringResource(R.string.library_message_scan_truncated, message.limit)
}

/** 文件选择只收图片（docs/05 §3.1）。 */
private val FILE_PICKER_MIME_TYPES = arrayOf("image/*")

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    PictTheme { LibraryScreen() }
}
