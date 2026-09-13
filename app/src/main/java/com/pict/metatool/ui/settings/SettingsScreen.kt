package com.pict.metatool.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.pict.metatool.domain.settings.Backdrop
import com.pict.metatool.domain.settings.NavBarStyle
import com.pict.metatool.domain.settings.NavItem
import com.pict.metatool.domain.settings.ThemeMode
import com.pict.metatool.domain.naming.ExportNaming
import com.pict.metatool.ui.navigation.LocalBottomBarInset
import com.pict.metatool.ui.theme.PictSpacing
import com.pict.metatool.ui.theme.supportsDynamicColor
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
    val backdropLabels = Backdrop.entries.associateWith { backdropLabel(it) }
    val keepOneNavItem = stringResource(R.string.settings_navbar_min_note)

    var editingSuffix by remember { mutableStateOf(false) }
    var editingSeed by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }
    // 三个编辑项的落点：弹出的编辑层要盖住**它所在的那张设置卡**（见 SettingsCardPopup 的说明）
    val suffixAnchor = rememberSettingsCardAnchor()
    val seedAnchor = rememberSettingsCardAnchor()
    val resetAnchor = rememberSettingsCardAnchor()
    // Android 12 以下系统给不出壁纸调色板：那一行是灰的，写明原因，而不是「开了没反应」
    val dynamicColorSupported = supportsDynamicColor()
    // 底色要等动态取色关掉之后才轮到自己挑：开着的时候底色跟着壁纸走（FR-38 续）
    val backdropEnabled = !(settings.dynamicColor && dynamicColorSupported)
    val backdrop = Backdrop.fromArgb(settings.backgroundColor)

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.settings_title)) }) },
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
            SettingsSection(
                title = stringResource(R.string.settings_section_output),
                anchor = suffixAnchor,
            ) {
                // 先说「改原图还是写副本」：这一项决定下面那些导出设置什么时候用得上
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_in_place_editing),
                    subtitle = stringResource(R.string.settings_in_place_editing_hint),
                    checked = settings.inPlaceEditing,
                    onCheckedChange = { on -> onUpdate { it.copy(inPlaceEditing = on) } },
                )

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

            SettingsSection(
                title = stringResource(R.string.settings_section_preset),
                anchor = seedAnchor,
            ) {
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

                // 取色来源（FR-38）：只在系统给得出壁纸调色板时可点，旧机器上是灰的并写明原因
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(
                        if (dynamicColorSupported) {
                            R.string.settings_dynamic_color_hint
                        } else {
                            R.string.settings_dynamic_color_unsupported
                        },
                    ),
                    checked = settings.dynamicColor && dynamicColorSupported,
                    enabled = dynamicColorSupported,
                    onCheckedChange = { on -> onUpdate { it.copy(dynamicColor = on) } },
                )

                // 底色（FR-38 续）：动态取色关掉之后才轮到自己挑，所以这一行跟着上面那一项亮/灰
                SettingsSwatchRow(
                    title = stringResource(R.string.settings_backdrop),
                    subtitle = stringResource(
                        if (backdropEnabled) {
                            R.string.settings_backdrop_current
                        } else {
                            R.string.settings_backdrop_locked
                        },
                        backdropLabels.getValue(backdrop),
                    ),
                    options = Backdrop.entries,
                    selected = backdrop,
                    label = { option -> backdropLabels.getValue(option) },
                    enabled = backdropEnabled,
                    onSelect = { option -> onUpdate { it.copy(backgroundColor = option.argb) } },
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

                // 玻璃开关紧跟在样式后面：样式管底栏长什么样，这一项管那层玻璃要不要。
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_navbar_glass),
                    subtitle = stringResource(R.string.settings_navbar_glass_hint),
                    checked = settings.liquidGlass,
                    onCheckedChange = { on -> onUpdate { it.copy(liquidGlass = on) } },
                )

                // 只列可选入口：必需项不出现——给一个关不掉的开关，比没有这个开关更让人困惑。
                AppSettings.OPTIONAL_NAV_ITEMS.forEach { item ->
                    SettingsSwitchRow(
                        title = navItemLabels.getValue(item),
                        subtitle = if (item == AppSettings.OPTIONAL_NAV_ITEMS.first()) {
                            stringResource(R.string.settings_navbar_items_hint)
                        } else {
                            null
                        },
                        checked = item in settings.navItems,
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

            SettingsSection(
                title = stringResource(R.string.settings_section_about),
                anchor = resetAnchor,
            ) {
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
        EditSuffixPopup(
            anchor = suffixAnchor,
            current = settings.exportSuffix,
            onDismiss = { editingSuffix = false },
            onConfirm = { raw ->
                editingSuffix = false
                onUpdate { it.copy(exportSuffix = raw) }
            },
        )
    }

    if (editingSeed) {
        EditSeedPopup(
            anchor = seedAnchor,
            current = settings.randomSeedDefault,
            onDismiss = { editingSeed = false },
            onConfirm = { seed ->
                editingSeed = false
                onUpdate { it.copy(randomSeedDefault = seed) }
            },
        )
    }

    if (confirmingReset) {
        ResetPopup(
            anchor = resetAnchor,
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

/** 底色的中文名。枚举本身在 domain 里，不带界面文案。 */
@Composable
private fun backdropLabel(option: Backdrop): String = stringResource(
    when (option) {
        Backdrop.AUTO -> R.string.backdrop_auto
        Backdrop.CLOUD -> R.string.backdrop_cloud
        Backdrop.SAND -> R.string.backdrop_sand
        Backdrop.MINT -> R.string.backdrop_mint
        Backdrop.SKY -> R.string.backdrop_sky
        Backdrop.LILAC -> R.string.backdrop_lilac
        Backdrop.MOSS -> R.string.backdrop_moss
        Backdrop.BLUSH -> R.string.backdrop_blush
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
 * 导出后缀编辑层：落在「导出文件名后缀」那一行上，把它盖住（docs/06 §3.7）。
 *
 * 边打边给示例文件名：`ExportNaming.suggest` 就是导出时真正用的那个函数，
 * 于是这里看到的截断与「已带后缀就不重复加」的规则，跟落盘时完全一致——
 * 不另写一套「预览规则」。
 */
@Composable
private fun EditSuffixPopup(
    anchor: SettingsCardAnchor,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }

    SettingsCardPopup(
        anchor = anchor,
        title = stringResource(
            R.string.settings_edit_title,
            stringResource(R.string.settings_export_suffix),
        ),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(text) },
    ) {
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
}

/** 默认种子编辑层：解析不了就当场说清楚，不悄悄换成 0（0 是「还没定过种子」的哨兵值）。 */
@Composable
private fun EditSeedPopup(
    anchor: SettingsCardAnchor,
    current: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val seed = AppSettings.parseSeed(text)
    val invalid = seed == null

    SettingsCardPopup(
        anchor = anchor,
        title = stringResource(R.string.settings_random_seed_dialog_title),
        onDismiss = onDismiss,
        onConfirm = { seed?.let(onConfirm) },
        confirmEnabled = seed != null,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(
                if (invalid) {
                    R.string.settings_random_seed_invalid
                } else {
                    R.string.settings_random_seed_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (invalid) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = PictSpacing.sm),
        )
    }
}

/** 恢复默认的二次确认：破坏性动作，确认按钮走 error 色（docs/06 §5）。 */
@Composable
private fun ResetPopup(
    anchor: SettingsCardAnchor,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SettingsCardPopup(
        anchor = anchor,
        title = stringResource(R.string.settings_reset_title),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmLabel = stringResource(R.string.settings_reset),
        confirmColor = MaterialTheme.colorScheme.error,
    ) {
        Text(
            text = stringResource(R.string.settings_reset_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
