package com.pict.metatool.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.pict.metatool.R
import com.pict.metatool.ui.components.PlaceholderPane
import com.pict.metatool.ui.theme.PictTheme

/**
 * 设置页（P0 占位）。
 * 后续（P4-T4.x）：预设管理、输出命名规则、默认格式、关于与隐私说明。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    PlaceholderPane(
        icon = Icons.Filled.Settings,
        title = stringResource(R.string.placeholder_settings_title),
        body = stringResource(R.string.placeholder_settings_body),
        badge = stringResource(R.string.stage_badge),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    PictTheme { SettingsScreen() }
}
