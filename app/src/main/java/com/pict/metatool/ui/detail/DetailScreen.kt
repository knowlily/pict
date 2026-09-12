package com.pict.metatool.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.pict.metatool.R
import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.format.ByteSizeFormatter
import com.pict.metatool.domain.model.MetadataSet

/**
 * 图片详情页（docs/06 §3.2）。
 *
 * 顶部是文件名与操作菜单，中间是可缩放大图（双指），下面五个 Tab：
 * 概览 / EXIF / GPS / XMP / 文件。字段行的展示值来自 `TagValueFormatter`，
 * 点一行把原始值复制到剪贴板。
 *
 * @param uri 图片地址；由图库网格点击时传进来（导航层已解码）。
 * @param onEdit 进入单文件编辑页（docs/07 T2.10），只把地址交给导航层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    uri: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: DetailViewModel = viewModel(factory = DetailViewModel.factory(LocalContext.current)),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(uri) { viewModel.load(uri) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        when (message) {
            is DetailMessage.Copied -> {
                snackbarHostState.showSnackbar(context.getString(R.string.detail_copied, message.label))
            }
        }
        viewModel.consumeMessage()
    }

    val fallbackTitle = stringResource(R.string.detail_fallback_title)
    val backLabel = stringResource(R.string.detail_back)
    val moreLabel = stringResource(R.string.detail_more)
    val reloadLabel = stringResource(R.string.detail_menu_reload)
    val copyUriLabel = stringResource(R.string.detail_menu_copy_uri)
    val searchLabel = stringResource(R.string.detail_search_open)

    Scaffold(
        topBar = {
            if (state.searchActive) {
                SearchTopBar(
                    query = state.searchQuery,
                    onQueryChange = viewModel::onQueryChange,
                    onClose = viewModel::closeSearch,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title.ifBlank { fallbackTitle },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::openSearch) {
                            Icon(Icons.Filled.Search, contentDescription = searchLabel)
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = moreLabel)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(reloadLabel) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.retry()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(copyUriLabel) },
                                onClick = {
                                    menuOpen = false
                                    copyToClipboard(context, copyUriLabel, uri)
                                    viewModel.onCopied(copyUriLabel)
                                },
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // 搜索和空文件时不占地方；能写才给「编辑元数据」（docs/06 §3.2）。
            if (!state.searchActive && state.hasMetadata) {
                EditEntryBar(onClick = { onEdit(uri) })
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.searchActive) {
                PreviewPane(uri = uri, modifier = Modifier.fillMaxWidth().height(220.dp))
                DetailTabRow(selected = state.selectedTab, onSelect = viewModel::selectTab)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> LoadingPane()
                    state.error != null -> ErrorPane(
                        error = state.error!!,
                        detail = state.errorDetail,
                        onRetry = viewModel::retry,
                    )
                    state.showEmptyState -> EmptyPane(
                        title = stringResource(R.string.detail_empty_title),
                        body = stringResource(R.string.detail_empty_body),
                    )
                    state.searchActive -> SearchPane(
                        query = state.searchQuery,
                        hits = state.searchHits,
                        onCopy = { label, text ->
                            copyToClipboard(context, label, text)
                            viewModel.onCopied(label)
                        },
                    )
                    else -> AnimatedContent(
                        targetState = state.selectedTab,
                        transitionSpec = {
                            (fadeIn() + slideInVertically { it / 24 }) togetherWith
                                (fadeOut() + slideOutVertically { -it / 24 })
                        },
                        label = "detail-tab",
                    ) { tab ->
                        when (tab) {
                            DetailTab.OVERVIEW -> OverviewPane(state.overviewRows)
                            DetailTab.GPS -> GpsPane(
                                metadata = state.metadata,
                                onCopy = { label, text ->
                                    copyToClipboard(context, label, text)
                                    viewModel.onCopied(label)
                                },
                            )
                            DetailTab.FILE -> FilePane(
                                state = state,
                                onCopy = { label, text ->
                                    copyToClipboard(context, label, text)
                                    viewModel.onCopied(label)
                                },
                            )
                            else -> SectionPane(
                                sections = state.sections,
                                onCopy = { label, text ->
                                    copyToClipboard(context, label, text)
                                    viewModel.onCopied(label)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 可双指缩放 / 拖动的大图；缩小到 1 倍时不再跟随拖动。 */
@Composable
private fun PreviewPane(uri: String, modifier: Modifier = Modifier) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offsetX by remember(uri) { mutableStateOf(0f) }
    var offsetY by remember(uri) { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 5f)
                    scale = next
                    if (next > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = stringResource(R.string.detail_preview_desc),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}

/** 搜索模式的顶栏：自动聚焦的输入框，返回键退出搜索。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val closeLabel = stringResource(R.string.detail_search_close)
    val hint = stringResource(R.string.detail_search_hint)

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = closeLabel)
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(hint) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
    )
}

/** 搜索结果：跨全部 Tab 的命中，按字段目录顺序，每行上方标出所属分组。 */
@Composable
private fun SearchPane(
    query: String,
    hits: List<SearchHit>,
    onCopy: (String, String) -> Unit,
) {
    if (query.isBlank()) {
        EmptyPane(
            title = stringResource(R.string.detail_search_idle_title),
            body = stringResource(R.string.detail_search_idle_body),
        )
        return
    }
    if (hits.isEmpty()) {
        EmptyPane(
            title = stringResource(R.string.detail_search_none_title),
            body = stringResource(R.string.detail_search_none_body, query),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items = hits, key = { it.row.key.full }) { hit ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = hit.section,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                )
                MetadataRowItem(row = hit.row, onCopy = onCopy)
            }
        }
    }
}

@Composable
private fun DetailTabRow(selected: DetailTab, onSelect: (DetailTab) -> Unit) {
    // PrimaryTabRow：TabRow 在 material3 1.4 已弃用，官方替代就是它（同类还有 SecondaryTabRow，
    // 底下带分隔线、指示条走 secondary 色）。换过来之后指示条由 2dp 变 3dp、上圆角，
    // 颜色仍是 primary——这是 Material 那边给的默认外观变化，不是我们改的设计。
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        DetailTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(tab.label, maxLines = 1) },
            )
        }
    }
}

/** 概览页：固定顺序的摘要行，值取不到的行不显示。 */
@Composable
private fun OverviewPane(rows: List<OverviewRow>) {
    if (rows.isEmpty()) {
        EmptyPane(
            title = stringResource(R.string.detail_empty_title),
            body = stringResource(R.string.detail_empty_body),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** EXIF / XMP / 文件页：按分组列出字段，点行复制原始值。 */
@Composable
private fun SectionPane(sections: List<MetadataSection>, onCopy: (String, String) -> Unit) {
    if (sections.isEmpty()) {
        EmptyPane(
            title = stringResource(R.string.detail_empty_title),
            body = stringResource(R.string.detail_empty_body),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        sections.forEach { section ->
            item(key = "section-${section.title}") {
                SectionHeader(title = section.title)
            }
            items(section.rows, key = { it.key.full }) { row ->
                MetadataRowItem(row = row, onCopy = onCopy)
            }
        }
    }
}

/** 文件页：先列文件本身的信息，再列目录里的结构字段。 */
@Composable
private fun FilePane(state: DetailUiState, onCopy: (String, String) -> Unit) {
    val fileRows = buildList {
        state.source?.let { info ->
            add(stringResource(R.string.detail_file_name) to info.displayName)
            info.mimeType?.let { add(stringResource(R.string.detail_file_mime) to it) }
            add(stringResource(R.string.detail_file_size) to ByteSizeFormatter.format(info.sizeBytes))
            add(stringResource(R.string.detail_file_format) to info.format.label)
        }
        state.uri?.let { add(stringResource(R.string.detail_file_uri) to it) }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "section-file") {
            SectionHeader(title = "文件")
        }
        items(fileRows, key = { "file-${it.first}" }) { (label, value) ->
            PlainRowItem(label = label, value = value, onCopy = onCopy)
        }
        state.sections.forEach { section ->
            item(key = "section-${section.title}") {
                SectionHeader(title = section.title)
            }
            items(section.rows, key = { it.key.full }) { row ->
                MetadataRowItem(row = row, onCopy = onCopy)
            }
        }
    }
}

/** GPS 页：十进制度 + 度分秒两种写法，外加复制坐标。 */
@Composable
private fun GpsPane(metadata: MetadataSet?, onCopy: (String, String) -> Unit) {
    val display = metadata?.let(OverviewBuilder::coordinates)
    if (display == null) {
        EmptyPane(
            title = stringResource(R.string.detail_gps_empty),
            body = stringResource(R.string.detail_empty_body),
        )
        return
    }

    val copyLabel = stringResource(R.string.detail_gps_copy)
    Column(modifier = Modifier.padding(16.dp)) {
        CoordinateCard(
            label = stringResource(R.string.detail_gps_decimal),
            value = display.decimal,
        )
        Spacer(modifier = Modifier.height(12.dp))
        CoordinateCard(
            label = stringResource(R.string.detail_gps_dms),
            value = display.dms,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { onCopy(copyLabel, display.raw) },
        ) {
            Text(copyLabel)
        }
    }
}

@Composable
private fun CoordinateCard(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 目录里的字段行：只读字段用灰色，可编辑字段带铅笔标记。 */
@Composable
private fun MetadataRowItem(row: MetadataRow, onCopy: (String, String) -> Unit) {
    val editableHint = stringResource(R.string.detail_editable_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(row.label, row.rawValue) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.canEdit) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (row.canEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = editableHint,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 文件页里不来自字段目录的行（文件名 / 大小等）。 */
@Composable
private fun PlainRowItem(label: String, value: String, onCopy: (String, String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(label, value) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LoadingPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.detail_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorPane(error: PictError, detail: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(error.messageRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.detail_error_detail, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.detail_retry))
            }
        }
    }
}

@Composable
private fun EmptyPane(title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
