package com.pict.metatool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pict.metatool.data.batch.SafBatchTargets
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.settings.AppSettings
import com.pict.metatool.domain.settings.NavBarStyle
import com.pict.metatool.ui.batch.BatchScreen
import com.pict.metatool.ui.detail.DetailScreen
import com.pict.metatool.ui.edit.EditScreen
import com.pict.metatool.ui.jobs.JobsScreen
import com.pict.metatool.ui.library.LibraryScreen
import com.pict.metatool.ui.navigation.BatchRoute
import com.pict.metatool.ui.navigation.DetailRoute
import com.pict.metatool.ui.navigation.EditRoute
import com.pict.metatool.ui.navigation.FloatingNavBar
import com.pict.metatool.ui.navigation.FloatingNavBarReservedHeight
import com.pict.metatool.ui.navigation.LocalBottomBarInset
import com.pict.metatool.ui.navigation.PictDestination
import com.pict.metatool.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *
 * 两种样式的落位也不同：贴底样式仍然占 Scaffold 的 bottomBar 槽，Scaffold 替页面留位；
 * 悬浮样式是玻璃的，得浮在内容之上、让内容从底下穿过去才有东西可糊，所以它挪到了
 * 内容外层的一个 Box 里，页面靠 [LocalBottomBarInset] 自己留出被压住的那段高度。
 */
@Composable
fun PictApp(
    navController: NavHostController = rememberNavController(),
    settings: AppSettings = AppSettings(),
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 详情页、编辑页、批量页都是二级页面，进来就收起底部导航（docs/06 §3.2 / §3.3 / §3.8）。
    val topLevel = currentRoute != DetailRoute.PATTERN &&
        currentRoute != EditRoute.PATTERN &&
        currentRoute != BatchRoute.PATTERN
    val destinations = remember(settings.navItems) { PictDestination.visibleItems(settings.navItems) }
    val floatingBar = topLevel && settings.navBarStyle == NavBarStyle.FLOATING

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

    // 玻璃底栏要拿「它底下那层内容」当背板去糊，所以内容得先录进一张离屏图层（见 glassBackdrop）。
    val backdrop = rememberGraphicsLayer()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdrop(enabled = floatingBar, layer = backdrop),
        ) {
            CompositionLocalProvider(
                LocalBottomBarInset provides if (floatingBar) FloatingNavBarReservedHeight else 0.dp,
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // 悬浮样式自己浮在内容上，这里只给贴底样式留位。
                        if (topLevel && !floatingBar) {
                            NavigationBar {
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
                                // 一张都没选时 BatchRoute 返回 null，这里就不发导航
                                onBatchEdit = { uris -> BatchRoute.build(uris)?.let(navController::navigate) },
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
                                settings = settings,
                            )
                        }
                        composable(BatchRoute.PATTERN) { entry ->
                            // 路由里只有地址，来源信息（真名 / MIME / 大小 / 可写位）得先查出来：
                            // 拿「末段当名字 + 格式未知」的占位去预览，可写性判不出来，
                            // 结果是把一个个能写的 JPEG 全标成「不支持原地写」。
                            // 查询是阻塞 IO —— 放 IO 线程，查完再建页面，没查完先转圈。
                            val context = LocalContext.current
                            val uris = BatchRoute.parse(entry.arguments?.getString(BatchRoute.ARG_URIS))
                            val targets by produceState<List<BatchTarget>?>(null, uris) {
                                value = withContext(Dispatchers.IO) {
                                    SafBatchTargets(context.contentResolver).of(uris)
                                }
                            }
                            val resolved = targets
                            if (resolved == null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                BatchScreen(
                                    targets = resolved,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (floatingBar) {
            FloatingNavBar(
                items = destinations,
                currentRoute = currentRoute,
                onSelect = onSelect,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 把这一层内容录进离屏图层，再原样画出来。
 *
 * Compose 没法「取某块区域背后的像素」，玻璃底栏想拿底下的内容当背板，只能自己先录一份。
 * 关掉时（贴底样式、二级页面）完全不录，别白白多一次离屏绘制。
 */
private fun Modifier.glassBackdrop(enabled: Boolean, layer: GraphicsLayer): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    }
