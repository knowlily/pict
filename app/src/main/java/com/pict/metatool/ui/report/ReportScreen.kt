package com.pict.metatool.ui.report

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pict.metatool.R
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobReport
import com.pict.metatool.ui.components.PlaceholderPane
import com.pict.metatool.ui.theme.PictSpacing

/**
 * 任务报告页（docs/07 T5.7、docs/01 FR-31）。
 *
 * 三块：结果、参数与种子、失败与丢弃。导出两个按钮走系统「新建文件」
 * （`ACTION_CREATE_DOCUMENT`）：用户自己挑位置，我们不猜他要把报告放哪。
 *
 * 文件名给的是 `pict-<jobId>.csv`：id 由我们自己生成、只含字母数字与连字符，
 * 直接当文件名是安全的——省掉一轮「名字里有没有非法字符」的清洗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    jobId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportViewModel = viewModel(
        factory = ReportViewModel.factory(LocalContext.current, jobId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val exportCsv = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(CSV_MIME_TYPE),
    ) { target -> target?.let(viewModel::exportCsv) }

    val exportJson = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(JSON_MIME_TYPE),
    ) { target -> target?.let(viewModel::exportJson) }

    val message = state.message
    val messageText = message?.let { reportMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.jobs_report_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.jobs_report_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val report = state.report
        if (report == null) {
            PlaceholderPane(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.jobs_report_title),
                body = stringResource(R.string.jobs_report_missing),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PictSpacing.screenHorizontal,
                    end = PictSpacing.screenHorizontal,
                    bottom = PictSpacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.sm),
        ) {
            ReportSection(title = stringResource(R.string.jobs_report_summary_title)) {
                SummaryLine(
                    label = stringResource(R.string.jobs_report_total),
                    value = report.total.toString(),
                )
                // 各项状态的中文标签来自域层（本来就给用户看），这里只负责数数
                JobItemStatus.entries.forEach { status ->
                    val count = report.items.count { it.status == status }
                    if (count > 0) SummaryLine(label = status.label, value = count.toString())
                }
                SummaryLine(
                    label = stringResource(R.string.jobs_report_changed_total),
                    value = report.changedKeysTotal.toString(),
                )
                if (report.dryRun) {
                    Text(
                        text = stringResource(R.string.jobs_progress_dry_run),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ReportSection(title = stringResource(R.string.jobs_report_params_title)) {
                val params = report.params
                SummaryLine(label = stringResource(R.string.jobs_report_param_mode), value = params.mode)
                SummaryLine(
                    label = stringResource(R.string.jobs_report_param_preset),
                    value = params.presetId ?: stringResource(R.string.jobs_report_param_none),
                )
                SummaryLine(
                    label = stringResource(R.string.jobs_report_param_seed),
                    value = params.seed.toString(),
                )
                SummaryLine(
                    label = stringResource(R.string.jobs_report_param_overwrite),
                    value = yesNo(params.overwriteExisting),
                )
                SummaryLine(
                    label = stringResource(R.string.jobs_report_param_clear),
                    value = params.clearTargets.takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = "、")
                        ?: stringResource(R.string.jobs_report_param_none),
                )
                SummaryLine(
                    label = stringResource(R.string.jobs_report_param_concurrency),
                    value = params.concurrency.toString(),
                )
                SummaryLine(
                    label = stringResource(R.string.jobs_report_param_retries),
                    value = params.maxRetries.toString(),
                )
                SummaryLine(
                    label = stringResource(R.string.jobs_report_param_items),
                    value = params.itemCount.toString(),
                )
            }

            ReportSection(title = stringResource(R.string.jobs_report_notes_title)) {
                val noted = report.noted
                if (noted.isEmpty()) {
                    Text(
                        text = stringResource(R.string.jobs_report_notes_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    noted.forEach { item ->
                        Text(
                            text = "${item.name}：${item.note.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
            ) {
                OutlinedButton(
                    onClick = { exportCsv.launch(csvFileName(report)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.jobs_report_export_csv))
                }
                OutlinedButton(
                    onClick = { exportJson.launch(jsonFileName(report)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.jobs_report_export_json))
                }
            }
        }
    }
}

/** 一块圆角卡片：标题 + 内容（docs/06 §5 的圆角卡片）。 */
@Composable
private fun ReportSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PictSpacing.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(PictSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

/** 「标签 —— 值」一行；值过长时省略，不把标签挤没。 */
@Composable
private fun SummaryLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PictSpacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(
    if (value) R.string.jobs_report_yes else R.string.jobs_report_no,
)

/** 导出文件名：id 只含字母数字与连字符，直接当文件名用是安全的。 */
internal fun csvFileName(report: JobReport): String = "pict-${report.jobId}.csv"

internal fun jsonFileName(report: JobReport): String = "pict-${report.jobId}.json"

/** 一次性提示文案（docs/06 §6：发生了什么 → 怎么办）。 */
@Composable
private fun reportMessageText(message: ReportMessage): String = when (message) {
    ReportMessage.Exported -> stringResource(R.string.jobs_report_exported)
    ReportMessage.ExportFailed -> stringResource(R.string.jobs_report_export_failed)
}

private const val CSV_MIME_TYPE = "text/csv"

private const val JSON_MIME_TYPE = "application/json"
