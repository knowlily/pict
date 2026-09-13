package com.pict.metatool.ui.navigation

/**
 * 预设管理页（设置 → 预设管理）。
 *
 * 没有参数：进去看的就是 `MergedPresetCatalog`（内置 + 自建）的全量。
 * 跟详情页、编辑页一样是二级页面，进来就收起底部导航（见 `PictApp` 的 topLevel 判定）。
 */
object PresetRoute {

    const val PATTERN: String = "presets"
}
