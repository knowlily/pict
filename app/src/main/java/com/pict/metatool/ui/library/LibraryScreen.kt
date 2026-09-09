package com.pict.metatool.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pict.metatool.R
import com.pict.metatool.ui.components.PlaceholderPane
import com.pict.metatool.ui.theme.PictTheme

/**
 * 图库页（P0 占位）。
 * 后续（P1-T1.x）：SAF 选择器导入 → 网格预览 → 元数据读取。
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    PlaceholderPane(
        icon = Icons.Filled.Home,
        title = stringResource(R.string.placeholder_library_title),
        body = stringResource(R.string.placeholder_library_body),
        badge = stringResource(R.string.stage_badge),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    PictTheme { LibraryScreen() }
}
