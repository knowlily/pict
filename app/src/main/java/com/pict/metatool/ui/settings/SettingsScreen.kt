package com.pict.metatool.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import com.pict.metatool.BuildConfig
import com.pict.metatool.R
import com.pict.metatool.domain.settings.AppSettings
import com.pict.metatool.domain.settings.NavBarStyle
import com.pict.metatool.domain.settings.NavItem
import com.pict.metatool.domain.settings.ThemeMode
import com.pict.metatool.ui.edit.ExportNaming
import com.pict.metatool.ui.theme.PictSpacing
import kotlinx.coroutines.launch

/**
 * 设置页（docs/01 FR-35 / FR-36 / FR-38，线框见 docs/06 §3.7）。
 *
 * 这里**没有**自己的 ViewModel：设置是全应用一份的东西，状态已经活在
 * `SettingsStore` 里（`data/settings`），本页只要把当前值和「怎么改」接上——
 * 再包一层 ViewModel 只会多一层没有内容的转发。
 *
 * [onUpdate] 收的是一整个变换（`(AppSettings) -> AppSettings`），不是新值：
 * 存储在自己的锁里做「读—改—写」，多个页面同时改不会互相覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings = AppSettings(),
    onUpdate: ((AppSettings) -> AppSettings) -> Unit = {},
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resetDone = stringResource(R.string.settings_reset_done)
    // 主题名要先在 composable 上下文里取出来：SettingsChoiceRow 的 label 是普通 lambda
    val themeLabels = ThemeMode.entries.associateWith { themeLabel(it) }
    val navBarStyleLabels = NavBarStyle.entries.associateWith { navBarStyleLabel(it) }
    val navItemLabels = NavItem.entries.associateWith { navItemLabel(it) }
    val keepOneNavItem = stringResource(R.string.settings_navbar_min_note)

    var editingSuffix by remember { mutableStateOf(false) }
    var editingSeed by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PictSpacing.screenHorizontal, vertical = PictSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(PictSpacing.xl),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_output)) {
                SettingsValueRow(
                    title = stringResource(R.string.settings_export_suffix),
                    value = settings.exportSuffix.ifEmpty {
                        stringResource(R.string.settings_export_suffix_none)
                    },
                    subtitle = stringResource(
                        R.string.settings_export_suffix_sample,
                        ExportNaming.suggest(SAMPLE_FILE_NAME, settings.exportSuffix),
                    ),
                    onClick = { editingSuffix = true },
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_verify_export),
                    subtitle = stringResource(R.string.settings_verify_export_hint),
                    checked = settings.verifyAfterExport,
                    onCheckedChange = { on -> onUpdate { it.copy(verifyAfterExport = on) } },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_preset)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_preset_overwrite),
                    subtitle = stringResource(R.string.settings_preset_overwrite_hint),
                    checked = settings.presetOverwriteDefault,
                    onCheckedChange = { on -> onUpdate { it.copy(presetOverwriteDefault = on) } },
                )

                SettingsValueRow(
                    title = stringResource(R.string.settings_random_seed),
                    value = settings.randomSeedDefault.toString(),
                    subtitle = stringResource(R.string.settings_random_seed_hint),
                    onClick = { editingSeed = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_ui)) {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_theme),
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = { mode -> themeLabels.getValue(mode) },
                    onSelect = { mode -> onUpdate { it.copy(themeMode = mode) } },
                )

                SettingsChoiceRow(
                    title = stringResource(R.string.settings_grid_columns),
                    subtitle = stringResource(R.string.settings_grid_columns_hint),
                    options = GRID_COLUMN_OPTIONS,
                    selected = settings.gridColumns,
                    label = { it.toString() },
                    onSelect = { columns -> onUpdate { it.copy(gridColumns = columns) } },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_navbar)) {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_navbar_style),
                    subtitle = stringResource(R.string.settings_navbar_style_hint),
                    options = NavBarStyle.entries,
                    selected = settings.navBarStyle,
                    label = { style -> navBarStyleLabels.getValue(style) },
                    onSelect = { style -> onUpdate { it.copy(navBarStyle = style) } },
                )

                NavItem.entries.forEach { item ->
                    // 「设置」是必需项：关掉它，设置页本身就没有入口了（真机点验踩到过）
                    val required = item in AppSettings.REQUIRED_NAV_ITEMS
                    SettingsSwitchRow(
                        title = navItemLabels.getValue(item),
                        subtitle = when {
                            item == NavItem.entries.first() ->
                                stringResource(R.string.settings_navbar_items_hint)

                            required -> stringResource(R.string.settings_navbar_item_required)
                            else -> null
                        },
                        checked = item in settings.navItems,
                        enabled = !required,
                        onCheckedChange = { on ->
                            val next = if (on) settings.navItems + item else settings.navItems - item
                            if (next.isEmpty()) {
                                // 最后一个入口不给关：底栏空了页面就点不动了（domain 的规范化也会兜一道）
                                scope.launch { snackbarHostState.showSnackbar(keepOneNavItem) }
                            } else {
                                onUpdate { it.copy(navItems = next) }
                            }
                        },
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsValueRow(
                    title = stringResource(R.string.settings_version),
                    value = stringResource(R.string.settings_version_value, versionName, versionCode),
                )

                SettingsNote(
                    title = stringResource(R.string.settings_offline_title),
                    body = stringResource(R.string.settings_offline_body),
                )

                SettingsDangerRow(
                    title = stringResource(R.string.settings_reset),
                    subtitle = stringResource(R.string.settings_reset_hint),
                    onClick = { confirmingReset = true },
                )
            }
        }
    }

    if (editingSuffix) {
        EditSuffixDialog(
            current = settings.exportSuffix,
            onDismiss = { editingSuffix = false },
            onConfirm = { raw ->
                editingSuffix = false
                onUpdate { it.copy(exportSuffix = raw) }
            },
        )
    }

    if (editingSeed) {
        EditSeedDialog(
            current = settings.randomSeedDefault,
            onDismiss = { editingSeed = false },
            onConfirm = { seed ->
                editingSeed = false
                onUpdate { it.copy(randomSeedDefault = seed) }
            },
        )
    }

    if (confirmingReset) {
        ResetDialog(
            onDismiss = { confirmingReset = false },
            onConfirm = {
                confirmingReset = false
                onUpdate { AppSettings() }
                scope.launch { snackbarHostState.showSnackbar(resetDone) }
            },
        )
    }
}

/** 示例用的文件名：带扩展名，好让人一眼看出后缀加在哪。 */
private const val SAMPLE_FILE_NAME = "IMG_1234.JPG"

private val GRID_COLUMN_OPTIONS =
    (AppSettings.MIN_GRID_COLUMNS..AppSettings.MAX_GRID_COLUMNS).toList()

/** 主题三档的中文名。枚举本身在 domain 里，不带界面文案。 */
@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)

/** 底栏两种样式的名字。 */
@Composable
private fun navBarStyleLabel(style: NavBarStyle): String = stringResource(
    when (style) {
        NavBarStyle.FLOATING -> R.string.settings_navbar_style_floating
        NavBarStyle.DOCKED -> R.string.settings_navbar_style_docked
    },
)

/** 底栏入口的显示名：跟底栏自己用同一份文案，不另写一套。 */
@Composable
private fun navItemLabel(item: NavItem): String = stringResource(
    when (item) {
        NavItem.LIBRARY -> R.string.nav_library
        NavItem.JOBS -> R.string.nav_jobs
        NavItem.SETTINGS -> R.string.nav_settings
    },
)

/**
 * 导出后缀编辑框。
 *
 * 边打边给示例文件名：`ExportNaming.suggest` 就是导出时真正用的那个函数，
 * 于是这里看到的截断与「已带后缀就不重复加」的规则，跟落盘时完全一致——
 * 不另写一套「预览规则」。
 */
@Composable
private fun EditSuffixDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.settings_edit_title,
                    stringResource(R.string.settings_export_suffix),
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.settings_export_suffix)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(
                        R.string.settings_export_suffix_sample,
                        ExportNaming.suggest(SAMPLE_FILE_NAME, text),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = PictSpacing.md),
                )

                Text(
                    text = stringResource(R.string.settings_export_suffix_empty_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PictSpacing.sm),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(text = stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
    )
}

/** 默认种子编辑框：解析不了就当场说清楚，不悄悄换成 0（0 是「还没定过种子」的哨兵值）。 */
@Composable
private fun EditSeedDialog(
    current: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val seed = AppSettings.parseSeed(text)
    val invalid = seed == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_random_seed_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (invalid) {
                    Text(
                        text = stringResource(R.string.settings_random_seed_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = PictSpacing.sm),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_random_seed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = PictSpacing.sm),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { seed?.let(onConfirm) },
                enabled = seed != null,
            ) {
                Text(text = stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
    )
}

/** 恢复默认的二次确认：破坏性动作，确认按钮走 error 色（docs/06 §5）。 */
@Composable
private fun ResetDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_reset_title)) },
        text = { Text(text = stringResource(R.string.settings_reset_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.settings_reset),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.settings_cancel),
                    textAlign = TextAlign.Start,
                )
            }
        },
    )
}
