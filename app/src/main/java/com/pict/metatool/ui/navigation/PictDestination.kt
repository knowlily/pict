package com.pict.metatool.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.pict.metatool.R

/**
 * 顶层导航目的地（docs/06 §1：底部三栏）。
 * 后续详情页 / 编辑器等二级页面用独立 route 常量挂到 NavHost 上，不进入底部栏。
 */
enum class PictDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Library(route = "library", labelRes = R.string.nav_library, icon = Icons.Filled.Home),
    Jobs(route = "jobs", labelRes = R.string.nav_jobs, icon = Icons.AutoMirrored.Filled.List),
    Settings(route = "settings", labelRes = R.string.nav_settings, icon = Icons.Filled.Settings),
    ;

    companion object {
        /** 底部栏展示顺序 */
        val bottomBarItems: List<PictDestination> = listOf(Library, Jobs, Settings)

        fun fromRoute(route: String?): PictDestination? =
            bottomBarItems.firstOrNull { it.route == route }
    }
}
