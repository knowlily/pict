package com.pict.metatool.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.pict.metatool.domain.settings.AppSettings
import com.pict.metatool.domain.settings.NavBarStyle
import com.pict.metatool.domain.settings.NavItem
import com.pict.metatool.domain.settings.ThemeMode

/**
 * 落盘实现：SharedPreferences 上一薄层（docs/01 FR-35）。
 *
 * 为什么不上 DataStore：设置一共九项、全是标量，DataStore 要带一套协程读写与迁移，
 * 而这里需要的是「启动第一帧就能同步拿到主题」，同步读 pref 反而更直白。
 * 读出来的值整份交给 [AppSettings.normalized]，所以 pref 被手改坏、或以后收窄了取值范围，
 * 界面拿到的仍然是合法值。
 *
 * 键名是持久化契约：**只能加，不能改**——改名等于把用户已有的设置丢掉。
 */
class SharedPrefsSettingsStore(context: Context) : InMemorySettingsStore(
    initial = loadSettings(prefsOf(context)),
    persist = { saveSettings(prefsOf(context), it) },
)

/** pref 文件名。 */
private const val PREFS_FILE = "pict_settings"

private const val KEY_EXPORT_SUFFIX = "export_suffix"
private const val KEY_VERIFY_AFTER_EXPORT = "verify_after_export"
private const val KEY_PRESET_OVERWRITE = "preset_overwrite_default"
private const val KEY_RANDOM_SEED = "random_seed_default"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_GRID_COLUMNS = "grid_columns"
private const val KEY_NAV_BAR_STYLE = "navbar_style"
private const val KEY_LIQUID_GLASS = "liquid_glass"
private const val KEY_DYNAMIC_COLOR = "dynamic_color"
private const val KEY_NAV_ITEMS = "navbar_items"

/** 最近目录（FR-03）：一条一行，明细见 [RecentFolderCodec]。 */
private const val KEY_RECENT_FOLDERS = "recent_folders"

private fun prefsOf(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

/** 读：缺项取默认值，整份过一遍规范化。 */
internal fun loadSettings(prefs: SharedPreferences): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        exportSuffix = prefs.getString(KEY_EXPORT_SUFFIX, null) ?: defaults.exportSuffix,
        verifyAfterExport = prefs.getBoolean(KEY_VERIFY_AFTER_EXPORT, defaults.verifyAfterExport),
        presetOverwriteDefault = prefs.getBoolean(KEY_PRESET_OVERWRITE, defaults.presetOverwriteDefault),
        randomSeedDefault = prefs.getLong(KEY_RANDOM_SEED, defaults.randomSeedDefault),
        themeMode = ThemeMode.fromName(prefs.getString(KEY_THEME_MODE, null)),
        gridColumns = prefs.getInt(KEY_GRID_COLUMNS, defaults.gridColumns),
        navBarStyle = NavBarStyle.fromName(prefs.getString(KEY_NAV_BAR_STYLE, null)),
        liquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, defaults.liquidGlass),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, defaults.dynamicColor),
        // 没存过、或存进去的名字一个都不认识：decode 给空集合，normalized 会把它兜回默认三栏
        navItems = NavItem.decode(prefs.getString(KEY_NAV_ITEMS, null)),
        // 同理：没存过是一张白纸（不是错误），坏行由 codec 自己丢掉，剩下的过规范化
        recentFolders = RecentFolderCodec.decode(prefs.getString(KEY_RECENT_FOLDERS, null)),
    ).normalized()
}

/** 写：值已经规范化过，这里只负责落到 pref 上（apply 异步写，不卡住界面线程）。 */
internal fun saveSettings(prefs: SharedPreferences, settings: AppSettings) {
    prefs.edit()
        .putString(KEY_EXPORT_SUFFIX, settings.exportSuffix)
        .putBoolean(KEY_VERIFY_AFTER_EXPORT, settings.verifyAfterExport)
        .putBoolean(KEY_PRESET_OVERWRITE, settings.presetOverwriteDefault)
        .putLong(KEY_RANDOM_SEED, settings.randomSeedDefault)
        .putString(KEY_THEME_MODE, settings.themeMode.name)
        .putInt(KEY_GRID_COLUMNS, settings.gridColumns)
        .putString(KEY_NAV_BAR_STYLE, settings.navBarStyle.name)
        .putBoolean(KEY_LIQUID_GLASS, settings.liquidGlass)
        .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
        .putString(KEY_NAV_ITEMS, NavItem.encode(settings.navItems))
        .putString(KEY_RECENT_FOLDERS, RecentFolderCodec.encode(settings.recentFolders))
        .apply()
}

/**
 * 进程内唯一的一份设置（无 DI 框架，手工装配，与各页 ViewModel 的用法一致）。
 *
 * 做成单例的理由：主题在 Activity、列数在图库页、后缀在编辑页，
 * 各读一份 pref 会各自缓存出不同副本，设置页改完别的页不刷新。
 */
object SettingsProvider {

    @Volatile
    private var cached: SettingsStore? = null

    fun of(context: Context): SettingsStore = cached ?: synchronized(this) {
        cached ?: SharedPrefsSettingsStore(context.applicationContext).also { cached = it }
    }
}
