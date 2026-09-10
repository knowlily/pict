package com.pict.metatool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pict.metatool.domain.settings.AppSettings
import com.pict.metatool.domain.settings.NavBarStyle
import com.pict.metatool.ui.detail.DetailScreen
import com.pict.metatool.ui.edit.EditScreen
import com.pict.metatool.ui.jobs.JobsScreen
import com.pict.metatool.ui.library.LibraryScreen
import com.pict.metatool.ui.navigation.DetailRoute
import com.pict.metatool.ui.navigation.EditRoute
import com.pict.metatool.ui.navigation.FloatingNavBar
import com.pict.metatool.ui.navigation.PictDestination
import com.pict.metatool.ui.settings.SettingsScreen

/**
 * 应用根组件（docs/02 §3）：Scaffold + 底部导航 + NavHost。
 * 单向数据流：状态自 ViewModel 向下流，事件自界面向上抛。
 *
 * [settings] 与 [onUpdateSettings] 由 `MainActivity` 注入（那一层要拿主题）：
 * 设置是全应用一份的状态，各页只读不写，改唯一从设置页发出去。
 *
 * 底部导航的样式与入口都听设置的（docs/06 §2）：[NavBarStyle.FLOATING] 走自绘的
 * [FloatingNavBar]，[NavBarStyle.DOCKED] 走 Material 的 NavigationBar。
 * 入口怎么筛都至少留一项（`AppSettings.normalizeNavItems` 兜的底），这里不做空判断。
 */
@Composable
fun PictApp(
    navController: NavHostController = rememberNavController(),
    settings: AppSettings = AppSettings(),
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 详情页与编辑页都是二级页面，进来就收起底部导航（docs/06 §3.2 / §3.3）。
    val topLevel = currentRoute != DetailRoute.PATTERN && currentRoute != EditRoute.PATTERN
    val destinations = remember(settings.navItems) { PictDestination.visibleItems(settings.navItems) }

    // 正待着的这一页被设置里关掉了（比如在设置页把「设置」关了）：立刻换到还留着的第一个入口。
    // 不然用户会停在一个底栏里没有任何高亮、也点不回去的页面上。
    val here = PictDestination.fromRoute(currentRoute)
    LaunchedEffect(destinations, here) {
        if (here != null && here !in destinations) {
            navController.navigate(destinations.first().route) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val onSelect: (PictDestination) -> Unit = { destination ->
        if (currentRoute != destination.route) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (topLevel) {
                when (settings.navBarStyle) {
                    NavBarStyle.FLOATING -> FloatingNavBar(
                        items = destinations,
                        currentRoute = currentRoute,
                        onSelect = onSelect,
                    )

                    NavBarStyle.DOCKED -> NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = { onSelect(destination) },
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
