package com.pict.metatool.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.pict.metatool.R
import com.pict.metatool.domain.settings.NavItem

/**
 * 顶层导航目的地（docs/06 §2：底部三栏）。
 * 后续详情页 / 编辑器等二级页面用独立 route 常量挂到 NavHost 上，不进入底部栏。
 *
 * [navItem] 是它与设置的对应关系：设置里关掉哪个入口，就按这个字段把它从底栏上摘掉。
 */
enum class PictDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val navItem: NavItem,
) {
    Library(
        route = "library",
        labelRes = R.string.nav_library,
        icon = Icons.Filled.Home,
        navItem = NavItem.LIBRARY,
    ),
    Jobs(
        route = "jobs",
        labelRes = R.string.nav_jobs,
        icon = Icons.AutoMirrored.Filled.List,
        navItem = NavItem.JOBS,
    ),
    Settings(
        route = "settings",
        labelRes = R.string.nav_settings,
        icon = Icons.Filled.Settings,
        navItem = NavItem.SETTINGS,
    ),
    ;

    companion object {
        /** 全部顶层入口，顺序就是底栏上从左到右的顺序 */
        val bottomBarItems: List<PictDestination> = listOf(Library, Jobs, Settings)

        /**
         * 按设置里勾选的入口过滤。
         *
         * 顺序永远取自 [bottomBarItems]（枚举声明序），不跟着勾选顺序走——
         * 否则底栏的图标位置会随用户点开关的顺序跳来跳去。
         */
        fun visibleItems(visible: Set<NavItem>): List<PictDestination> =
            bottomBarItems.filter { it.navItem in visible }

        fun fromRoute(route: String?): PictDestination? =
            bottomBarItems.firstOrNull { it.route == route }
    }
}
