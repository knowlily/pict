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
import com.pict.metatool.ui.jobs.JobsScreen
import com.pict.metatool.ui.library.LibraryScreen
import com.pict.metatool.ui.navigation.PictDestination
import com.pict.metatool.ui.settings.SettingsScreen

/**
 * 应用根组件（docs/02 §3）：Scaffold + 底部导航 + NavHost。
 * 单向数据流：状态自 ViewModel 向下流，事件自界面向上抛。
 */
@Composable
fun PictApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
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
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = PictDestination.Library.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(PictDestination.Library.route) { LibraryScreen() }
            composable(PictDestination.Jobs.route) { JobsScreen() }
            composable(PictDestination.Settings.route) { SettingsScreen() }
        }
    }
}
