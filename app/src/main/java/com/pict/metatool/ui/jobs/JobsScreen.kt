package com.pict.metatool.ui.jobs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pict.metatool.R
import com.pict.metatool.ui.components.PlaceholderPane
import com.pict.metatool.ui.theme.PictTheme

/**
 * 任务页（P0 占位）。
 * 后续（P3-T3.x）：WorkManager 队列、进度、暂停/取消、失败重试与回退。
 */
@Composable
fun JobsScreen(modifier: Modifier = Modifier) {
    PlaceholderPane(
        icon = Icons.AutoMirrored.Filled.List,
        title = stringResource(R.string.placeholder_jobs_title),
        body = stringResource(R.string.placeholder_jobs_body),
        badge = stringResource(R.string.stage_badge),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun JobsScreenPreview() {
    PictTheme { JobsScreen() }
}
