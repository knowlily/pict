package com.pict.metatool.ui.jobs

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pict.metatool.R
import com.pict.metatool.domain.job.JobHistoryEntry
import com.pict.metatool.domain.job.JobStatus
import com.pict.metatool.domain.job.UndoState
import com.pict.metatool.ui.theme.PictSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 任务页的零件（docs/06 §2 信息架构：进行中 + 历史任务，线框见 §3.8）。
 *
 * 两组各自的零件：进行中是一行任务（点开看进度），历史是一行记录带三个动作
 * （查看报告 / 重跑 / 撤销）。空态和真列表并存——空态只在真的空的时候出现。
 */

/** 分组小标题，跟设置页同一套排版。 */
@Composable
fun JobsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = PictSpacing.xs, bottom = PictSpacing.sm),
    )
}

/** 进行中：真没有排队的东西时才显示。 */
@Composable
fun JobsRunningEmptyCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PictSpacing.lg, vertical = PictSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = PictSpacing.md),
            ) {
                Text(
                    text = stringResource(R.string.jobs_running_empty_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.jobs_running_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PictSpacing.xxs),
                )
            }
        }
    }
}

/** 历史：一条都没有时才显示。 */
@Composable
fun JobsHistoryEmptyCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PictSpacing.lg, vertical = PictSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = PictSpacing.md),
            ) {
                Text(
                    text = stringResource(R.string.jobs_history_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.jobs_history_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PictSpacing.xxs),
                )
            }
        }
    }
}

/**
 * 进行中的一行。
 *
 * 整行可点：排上队之后用户唯一想知道的是「跑到哪了」，不该再让他找一个入口。
 */
@Composable
fun JobsRunningRow(
    entry: JobHistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PictSpacing.lg, vertical = PictSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(statusLabelRes(entry.status)) +
                        " · " +
                        stringResource(R.string.jobs_history_counts, entry.total, entry.succeeded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PictSpacing.xxs),
                )
            }
            if (entry.dryRun) {
                Text(
                    text = stringResource(R.string.jobs_progress_dry_run),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 历史的一行。
 *
 * 三个动作的可见性由 [entry] 自己说了算（域层已经把规则判完）：
 * 撤销不可用时**不留一个点不动的灰按钮**，而是把「为什么不能撤」写在原地——
 * 灰按钮只会让人反复点，文字能省掉一次「为什么」。
 */
@Composable
fun JobsHistoryRow(
    entry: JobHistoryEntry,
    undoing: Boolean,
    rerunning: Boolean,
    onOpenReport: () -> Unit,
    onRerun: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PictSpacing.lg, vertical = PictSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = formatHistoryTime(entry.finishedAtMillis ?: entry.createdAtMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = PictSpacing.xxs),
                    )
                }
                Text(
                    text = stringResource(statusLabelRes(entry.status)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = countsLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PictSpacing.sm),
            )

            entry.backupFolder
                ?.takeIf { entry.undoneAtMillis == null }
                ?.let { folder ->
                    Text(
                        text = stringResource(
                            R.string.jobs_history_backup_note,
                            folder,
                            entry.backupFiles,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = PictSpacing.xxs),
                    )
                }

            entry.undoneAtMillis?.let { undoneAt ->
                Text(
                    text = stringResource(
                        R.string.jobs_history_undone_note,
                        formatHistoryTime(undoneAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PictSpacing.xxs),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = PictSpacing.md),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PictSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                TextButton(onClick = onOpenReport) {
                    Text(text = stringResource(R.string.jobs_history_action_report))
                }

                if (entry.hasSpec) {
                    TextButton(onClick = onRerun, enabled = !rerunning) {
                        Text(text = stringResource(R.string.jobs_history_action_rerun))
                    }
                }

                when (entry.undoState) {
                    UndoState.AVAILABLE -> TextButton(onClick = onUndo, enabled = !undoing) {
                        Text(text = stringResource(R.string.jobs_history_action_undo))
                    }

                    else -> Text(
                        text = stringResource(undoExplanationRes(entry.undoState)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = PictSpacing.sm),
                    )
                }
            }
        }
    }
}

/** 「共 N 项 · 成功 M」外加有麻烦的那些——全是零就不占地方。 */
@Composable
private fun countsLine(entry: JobHistoryEntry): String {
    val parts = buildList {
        add(stringResource(R.string.jobs_history_counts, entry.total, entry.succeeded))
        if (entry.failed > 0) add(stringResource(R.string.jobs_history_failed, entry.failed))
        if (entry.verifyFailed > 0) {
            add(stringResource(R.string.jobs_history_verify_failed, entry.verifyFailed))
        }
        if (entry.skipped > 0) add(stringResource(R.string.jobs_history_skipped, entry.skipped))
    }
    return parts.joinToString(" · ")
}

@StringRes
private fun statusLabelRes(status: JobStatus): Int = when (status) {
    JobStatus.PENDING -> R.string.jobs_progress_state_pending
    JobStatus.RUNNING -> R.string.jobs_progress_state_running
    JobStatus.CANCELED -> R.string.jobs_progress_state_canceled
    JobStatus.COMPLETED -> R.string.jobs_progress_state_completed
    JobStatus.INTERRUPTED -> R.string.jobs_progress_state_interrupted
}

/** 撤不了的那几种情况各自说清楚，别都写成「不可用」。 */
@StringRes
private fun undoExplanationRes(state: UndoState): Int = when (state) {
    UndoState.UNDONE -> R.string.jobs_history_undo_undone
    UndoState.NOT_LATEST -> R.string.jobs_history_undo_not_latest
    UndoState.EXPIRED -> R.string.jobs_history_undo_expired
    UndoState.DRY_RUN -> R.string.jobs_history_undo_dry_run
    // AVAILABLE 走不到这里（上面已经按按钮分支处理），NO_BACKUP 与兜底同一句话
    UndoState.NO_BACKUP, UndoState.AVAILABLE -> R.string.jobs_history_undo_no_backup
}

private val historyTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatHistoryTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(historyTimeFormatter)
