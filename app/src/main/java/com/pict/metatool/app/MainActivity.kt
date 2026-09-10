package com.pict.metatool.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pict.metatool.data.settings.SettingsProvider
import com.pict.metatool.ui.PictApp
import com.pict.metatool.ui.theme.PictTheme

/**
 * 单 Activity 架构（docs/02 §3）：所有界面由 Compose 导航承载。
 *
 * 设置在这里读一次、往下传：主题要在最外层就定下来（换主题要整棵树重画），
 * 别的项（图库列数、导出后缀）由 [PictApp] 再分给各页——
 * 各页自己 `SettingsProvider.of()` 也能取，但那样主题就绕过了 Compose 的状态订阅，
 * 改了不会立刻生效。
 *
 * 「深色/浅色」不只是配色：系统栏图标明暗与窗口底色也由 [PictTheme] 按这里的 [darkTheme] 接管，
 * 所以选「深色」而系统是浅色时，状态栏图标会跟着变亮（docs/08 §10.5）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val store = remember(context) { SettingsProvider.of(context) }
            val settings by store.settings.collectAsState()

            PictTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
                PictApp(
                    settings = settings,
                    onUpdateSettings = { transform -> store.update(transform) },
                )
            }
        }
    }
}
