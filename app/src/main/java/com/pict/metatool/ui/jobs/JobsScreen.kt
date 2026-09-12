package com.pict.metatool.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pict.metatool.R
import com.pict.metatool.ui.navigation.LocalBottomBarInset
import com.pict.metatool.ui.theme.PictSpacing

/**
 * 任务页（docs/06 §2 信息架构：进行中 + 历史任务；线框见 docs/06 §3.8）。
 *
 * 两组各自读盘：进行中来自还没有终态的快照，历史来自跑完的那些。数据在
 * [JobHistoryViewModel] 里装配（扫盘 + 域层排序/可撤判定），这里只管画。
 *
 * 进页面刷一次，跑完一个任务再刷一次：任务页是「回来看结果」的地方，
 * 不该为了看到刚跑完的那一行去下拉一下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    onOpenProgress: (String) -> Unit,
    onOpenReport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: JobHistoryViewModel = viewModel(factory = JobHistoryViewModel.factory(context))
    val state by viewModel.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // 进页面读一次（回到这一页也会再走一遍：离开时这一层会被回收）
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 撤销/重跑的结果用一句话说清，不弹窗打断
    val message = state.message
    if (message != null) {
        val text = message.resolve(context)
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    state.confirming?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUndo,
            title = {
                Text(text = stringResource(R.string.jobs_history_undo_confirm_title, entry.label))
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.jobs_history_undo_confirm_body,
                        entry.backupFiles,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUndo) {
                    Text(text = stringResource(R.string.jobs_history_undo_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUndo) {
                    Text(text = stringResource(R.string.jobs_history_undo_confirm_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.jobs_title)) },
                actions = {
                    TextButton(onClick = viewModel::refresh) {
                        Text(text = stringResource(R.string.jobs_history_refresh))
                    }
                },
            )
        },
        snackbarHost = {
            // 悬浮底栏浮在底部：提示条得让开胶囊那段高，否则玻璃把它压住
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalBottomBarInset.current),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PictSpacing.screenHorizontal, vertical = PictSpacing.sm)
                // 悬浮底栏浮在内容上，页面自己留出被玻璃压住的那段（贴底样式这里是 0）
                .padding(bottom = LocalBottomBarInset.current + PictSpacing.aboveBottomBar),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.xl),
        ) {
            if (state.loading && state.isEmpty) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(PictSpacing.md),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.jobs_history_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                JobsSectionTitle(text = stringResource(R.string.jobs_running_section))
                if (state.running.isEmpty()) {
                    JobsRunningEmptyCard()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PictSpacing.listGap)) {
                        state.running.forEach { entry ->
                            JobsRunningRow(
                                entry = entry,
                                onClick = { onOpenProgress(entry.jobId) },
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                JobsSectionTitle(text = stringResource(R.string.jobs_history_section))
                if (state.finished.isEmpty()) {
                    JobsHistoryEmptyCard()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PictSpacing.listGap)) {
                        state.finished.forEach { entry ->
                            JobsHistoryRow(
                                entry = entry,
                                undoing = state.undoing == entry.jobId,
                                rerunning = state.rerunning == entry.jobId,
                                onOpenReport = { onOpenReport(entry.jobId) },
                                onRerun = { viewModel.rerun(entry) },
                                onUndo = { viewModel.askUndo(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}
