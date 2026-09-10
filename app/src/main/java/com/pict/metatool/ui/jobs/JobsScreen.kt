package com.pict.metatool.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pict.metatool.R
import com.pict.metatool.ui.navigation.LocalBottomBarInset
import com.pict.metatool.ui.theme.PictSpacing

/**
 * 任务页（docs/06 §2 信息架构：进行中 + 历史任务；线框见 docs/06 §3.8）。
 *
 * 队列与历史都还没实现（Phase 5 / T5.x），所以这一页现在只有结构、没有数据：
 * 与其摆几张点不动的假卡片，不如把「将来这里会出现什么」写清楚。
 * 真数据接上以后，两个分组各自换成列表，页面骨架不用动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.jobs_title)) }) },
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
            Column(modifier = Modifier.fillMaxWidth()) {
                JobsSectionTitle(text = stringResource(R.string.jobs_running_section))
                JobsRunningEmptyCard()
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                JobsSectionTitle(text = stringResource(R.string.jobs_history_section))
                JobsHistoryEmptyCard()
            }

            Text(
                text = stringResource(R.string.jobs_stage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
