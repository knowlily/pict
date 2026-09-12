package com.pict.metatool.ui.job

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pict.metatool.R
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobReportItem
import com.pict.metatool.domain.job.JobStatus
import com.pict.metatool.ui.theme.PictSpacing
import java.util.Locale

/**
 * 任务进度页（docs/07 T5.6、docs/06 §3.8 线框「进行中」）。
 *
 * 一屏三块，从上到下按「用户此刻最需要知道的」排：
 * 总进度（完成数 / 总数 / 百分比 + 预估剩余）→ 当前项 → 逐项列表。
 * 底部是取消与「查看报告」——报告按钮只在明细到手之后才出现
 * （还在跑时只有通知栏那点进度，点进去只能是空页）。
 *
 * 取消**不做二次确认**：它本身就是可恢复的操作（没开工的项记 SKIPPED，写了一半的会写完），
 * 而且用户按它的那一刻通常就是想马上停下。
 *
 * @param onBack 返回上一页。
 * @param onOpenReport 打开这个任务的报告页（T5.7）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobProgressScreen(
    jobId: String,
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JobProgressViewModel = viewModel(
        factory = JobProgressViewModel.factory(LocalContext.current, jobId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var cancelAsked by remember { mutableStateOf(false) }
    // 提示文案在组合里取好再交给副作用：@Composable 不能在 LaunchedEffect 里调用
    val cancelNote = stringResource(R.string.jobs_progress_canceled_note)

    // 取消到「真的停下」之间还有一两张要收尾，先把这件事说清楚，
    // 免得用户以为按钮没生效、连按好几次
    LaunchedEffect(cancelAsked) {
        if (cancelAsked) snackbarHostState.showSnackbar(cancelNote)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.label.ifEmpty { stringResource(R.string.jobs_progress_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.jobs_progress_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.canCancel || state.hasReport) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PictSpacing.screenHorizontal,
                            vertical = PictSpacing.sm,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
                ) {
                    if (state.canCancel) {
                        OutlinedButton(
                            onClick = {
                                viewModel.cancel()
                                cancelAsked = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = stringResource(R.string.jobs_progress_cancel))
                        }
                    }
                    if (state.hasReport) {
                        Button(
                            onClick = { onOpenReport(jobId) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = stringResource(R.string.jobs_progress_report))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ProgressHeader(state = state)

            val items = state.report?.items.orEmpty()
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.jobs_progress_item_no_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = PictSpacing.screenHorizontal,
                        vertical = PictSpacing.md,
                    ),
                )
            } else {
                Text(
                    text = stringResource(R.string.jobs_progress_items_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = PictSpacing.screenHorizontal,
                        end = PictSpacing.screenHorizontal,
                        bottom = PictSpacing.xs,
                    ),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = PictSpacing.screenHorizontal,
                        end = PictSpacing.screenHorizontal,
                        bottom = PictSpacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(PictSpacing.xs),
                ) {
                    itemsIndexed(items = items, key = { index, item -> "$index-${item.name}" }) { _, item ->
                        ItemRow(item = item)
                    }
                }
            }
        }
    }
}

/** 总进度：进度条 + 完成数 + 当前项 + 预估剩余（FR-29 的四件事）。 */
@Composable
private fun ProgressHeader(state: JobProgressUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PictSpacing.screenHorizontal, vertical = PictSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(PictSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.jobs_progress_count, state.finished, state.total, state.percent),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = when {
                // 「断了」比「只算不写」「正在处理哪张」都要紧：这张子行说的是这次任务
                // 此刻是不是还在往前走
                state.status == JobStatus.INTERRUPTED ->
                    stringResource(R.string.jobs_progress_state_interrupted)
                state.dryRun -> stringResource(R.string.jobs_progress_dry_run)
                state.currentName != null -> stringResource(R.string.jobs_progress_current, state.currentName!!)
                state.isWaiting -> stringResource(R.string.jobs_progress_waiting)
                else -> stringResource(R.string.jobs_progress_state_running)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!state.isTerminal) {
            Text(
                text = state.remainingMillis
                    ?.let { stringResource(R.string.jobs_progress_remaining, durationText(it)) }
                    ?: stringResource(R.string.jobs_progress_remaining_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一项一行：状态 + 文件名 + （耗时 / 备注 或 错误码）。 */
@Composable
private fun ItemRow(item: JobReportItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(PictSpacing.sm),
            )
            .padding(horizontal = PictSpacing.sm, vertical = PictSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
    ) {
        Text(
            text = item.status.label,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(item.status),
        )
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = item.note?.takeIf { it.isNotBlank() }
                ?: item.errorCode
                ?: item.durationMillis?.let { durationText(it) }
                ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun statusColor(status: JobItemStatus): Color = when (status) {
    JobItemStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    JobItemStatus.FAILED, JobItemStatus.VERIFY_FAILED -> MaterialTheme.colorScheme.error
    JobItemStatus.RUNNING, JobItemStatus.RETRYING -> MaterialTheme.colorScheme.secondary
    JobItemStatus.PENDING, JobItemStatus.SKIPPED, JobItemStatus.UNSUPPORTED ->
        MaterialTheme.colorScheme.onSurfaceVariant
}

/** 耗时文案：毫秒 / 秒 / 分秒（单位走资源，数字在这里拼）。 */
@Composable
private fun durationText(millis: Long): String = when {
    millis < 1_000L -> stringResource(R.string.jobs_duration_millis, millis)
    millis < 60_000L -> stringResource(
        R.string.jobs_duration_seconds,
        String.format(Locale.US, "%.1f", millis / 1000.0),
    )
    else -> stringResource(
        R.string.jobs_duration_minutes,
        (millis / 60_000L).toString(),
        ((millis / 1000L) % 60L).toString(),
    )
}

