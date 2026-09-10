package com.pict.metatool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pict.metatool.domain.settings.AppSettings
import com.pict.metatool.ui.detail.DetailScreen
import com.pict.metatool.ui.edit.EditScreen
import com.pict.metatool.ui.jobs.JobsScreen
import com.pict.metatool.ui.library.LibraryScreen
import com.pict.metatool.ui.navigation.DetailRoute
import com.pict.metatool.ui.navigation.EditRoute
import com.pict.metatool.ui.navigation.PictDestination
import com.pict.metatool.ui.settings.SettingsScreen

/**
 * 应用根组件（docs/02 §3）：Scaffold + 底部导航 + NavHost。
 * 单向数据流：状态自 ViewModel 向下流，事件自界面向上抛。
 *
 * [settings] 与 [onUpdateSettings] 由 `MainActivity` 注入（那一层要拿主题）：
 * 设置是全应用一份的状态，各页只读不写，改唯一从设置页发出去。
 */
@Composable
fun PictApp(
    navController: NavHostController = rememberNavController(),
    settings: AppSettings = AppSettings(),
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // 详情页与编辑页都是二级页面，进来就收起底部导航（docs/06 §3.2 / §3.3）。
            if (currentRoute != DetailRoute.PATTERN && currentRoute != EditRoute.PATTERN) {
                NavigationBar {
                    PictDestination.bottomBarItems.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(destination.route) {
                                        popUpTo(PictDestination.Library.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = stringResource(destination.labelRes),
                                )
                            },
                            label = { Text(text = stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = PictDestination.Library.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(PictDestination.Library.route) {
                LibraryScreen(
                    columns = settings.gridColumns,
                    onOpen = { uri -> navController.navigate(DetailRoute.build(uri)) },
                )
            }
            composable(PictDestination.Jobs.route) { JobsScreen() }
            composable(PictDestination.Settings.route) {
                SettingsScreen(settings = settings, onUpdate = onUpdateSettings)
            }
            composable(DetailRoute.PATTERN) { entry ->
                DetailScreen(
                    uri = entry.arguments?.getString(DetailRoute.ARG_URI).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onEdit = { uri -> navController.navigate(EditRoute.build(uri)) },
                )
            }
            composable(EditRoute.PATTERN) { entry ->
                EditScreen(
                    uri = entry.arguments?.getString(EditRoute.ARG_URI).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
