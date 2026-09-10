package com.pict.metatool.domain.settings

/**
 * 主题模式（docs/01 FR-38）。
 *
 * 只描述「用户选了什么」，落到颜色上由界面层决定（[isDark] 是纯函数，可单测）。
 */
enum class ThemeMode {

    /** 跟随系统深浅色。 */
    SYSTEM,

    /** 一直浅色。 */
    LIGHT,

    /** 一直深色。 */
    DARK,
    ;

    /** 结合系统当前是不是深色，算出真正要用的深色开关。 */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {

        /** 认不出来的名字一律回落到这里：pref 文件被手改过也不该崩在启动路径上。 */
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * 底栏样式（docs/06 §2）。
 *
 * [FLOATING] 是浮在内容之上的胶囊；[DOCKED] 是贴着屏幕底边的通栏。
 * 两种都留着：通栏的手指行程短、边缘更好够到；胶囊把底边留出来，内容不贴着屏幕，
 * 手势导航的机型上从底部上划时不容易误触。让用户自己挑，不强加一种。
 */
enum class NavBarStyle {

    /** 悬浮胶囊（默认）。 */
    FLOATING,

    /** 贴底通栏。 */
    DOCKED,
    ;

    companion object {

        /** 认不出来的名字回落到默认样式，理由同 [ThemeMode.fromName]。 */
        fun fromName(name: String?): NavBarStyle = entries.firstOrNull { it.name == name } ?: FLOATING
    }
}

/**
 * 底栏入口（docs/06 §2 的底部三项）。
 *
 * 放在 domain 而不是界面层，有两个原因：
 * 1. 它要落盘——pref 里存的是这里的名字，属于持久化契约的一部分；
 * 2. 「至少保留一项」这条规则得能脱离界面直接单测。
 *
 * 图标与文案不在这里：那是界面的事，由 `ui/navigation/PictDestination` 映射。
 */
enum class NavItem {
    LIBRARY,
    JOBS,
    SETTINGS,
    ;

    companion object {

        /** 认不出来的名字返回空（读盘时用来跳过未认识的项，而不是把整份设置作废）。 */
        fun fromName(name: String?): NavItem? = entries.firstOrNull { it.name == name }

        /** 存盘用的名字序列：按枚举顺序拼，勾选先后不影响结果，pref 里的值可比对。 */
        fun encode(items: Set<NavItem>): String =
            entries.filter { it in items }.joinToString(separator = ",") { it.name }

        /** 读盘：忽略空串与没见过的名字，重复项自然合并。 */
        fun decode(raw: String?): Set<NavItem> = raw
            ?.split(',')
            ?.mapNotNull { fromName(it.trim()) }
            ?.toSet()
            ?: emptySet()
    }
}

/**
 * 应用设置（docs/01 FR-35 / FR-38，界面对应 docs/06 §3.7）。
 *
 * 纯数据 + 纯函数：不碰 Android、不碰存储。落盘与读取在 `data/settings`，
 * 于是「哪些值算合法」这套规则可以脱离设备直接 JVM 单测（NFR-09）。
 *
 * 这里只放**已经真正接线**的选项——每个字段都能说清它改的是哪一处行为。
 * FR-35 里还没落地的（默认输出目录、目标格式与质量、并发数、日志级别）属于批处理 P5，
 * 等那半边做出来再往这里加，不先摆一排不生效的开关。
 */
data class AppSettings(
    /** 导出副本时加在原文件名后面的尾巴；空串 = 不加后缀。 */
    val exportSuffix: String = DEFAULT_EXPORT_SUFFIX,
    /** 导出副本写完之后，是否把文件读回来逐项校验（关掉能少读一次文件）。 */
    val verifyAfterExport: Boolean = DEFAULT_VERIFY_AFTER_EXPORT,
    /** 套用预设时是否覆盖已有值；false（默认）= 只填原图缺的字段。 */
    val presetOverwriteDefault: Boolean = DEFAULT_PRESET_OVERWRITE,
    /** 随机填充的默认种子：同一张图 + 同一颗种子给出同一批结果（T3.6）。 */
    val randomSeedDefault: Long = DEFAULT_RANDOM_SEED,
    /** 主题（FR-38）。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 图库每行缩略图数量。 */
    val gridColumns: Int = DEFAULT_GRID_COLUMNS,
    /** 底栏样式：悬浮胶囊或贴底通栏（docs/06 §2）。 */
    val navBarStyle: NavBarStyle = DEFAULT_NAV_BAR_STYLE,
    /** 底栏显示哪些入口；空集合非法，规范化会还给默认三栏。 */
    val navItems: Set<NavItem> = DEFAULT_NAV_ITEMS,
) {

    /**
     * 用之前一律过一遍。
     *
     * 越界或带非法字符的值不生效，但也不报错：设置是启动路径上的东西，
     * 为一个坏值弹框挡住整个 App 不划算——安静地回到合法区间，界面上显示的就是生效值。
     */
    fun normalized(): AppSettings = copy(
        exportSuffix = normalizeSuffix(exportSuffix),
        gridColumns = gridColumns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS),
        navItems = normalizeNavItems(navItems),
    )

    companion object {

        /** 默认后缀。文件名里出现的一律半角字符，别写全角「－」。 */
        const val DEFAULT_EXPORT_SUFFIX: String = "-edited"

        const val DEFAULT_VERIFY_AFTER_EXPORT: Boolean = true

        const val DEFAULT_PRESET_OVERWRITE: Boolean = false

        const val DEFAULT_RANDOM_SEED: Long = 20260101L

        const val DEFAULT_GRID_COLUMNS: Int = 3

        const val MIN_GRID_COLUMNS: Int = 2

        const val MAX_GRID_COLUMNS: Int = 4

        /** 默认底栏样式：胶囊。 */
        val DEFAULT_NAV_BAR_STYLE: NavBarStyle = NavBarStyle.FLOATING

        /** 默认三栏全开。 */
        val DEFAULT_NAV_ITEMS: Set<NavItem> = NavItem.entries.toSet()

        /**
         * 后缀长度上限：够写「-已编辑-备份」这类，又不会把文件名顶到 Provider 的截断线
         * （[com.pict.metatool.ui.edit.ExportNaming.MAX_LENGTH] 那个 100 字符）。
         */
        const val MAX_SUFFIX_LENGTH: Int = 32

        /**
         * 规范化后缀：去掉路径分隔符与各平台的文件名保留字符、去掉控制字符，
         * 去掉首尾空白，超长截断。**允许空串**——空 = 不加后缀，此时目标目录里若已有同名文件，
         * 由目标 Provider 自己补「 (1)」（真机上实测如此）。
         */
        fun normalizeSuffix(raw: String): String = raw
            .filterNot { it in ILLEGAL_SUFFIX_CHARS || it.isISOControl() }
            .trim()
            .take(MAX_SUFFIX_LENGTH)

        /**
         * 不允许关掉的入口。
         *
         * 设置页是**唯一**能改底栏的地方，把关掉自己的开关交给用户就成了没有出口的迷宫：
         * 真机点验踩到过——在设置页关掉「设置」之后，设置页再也进不去（入口列表存在 pref 里，
         * 重启也回不来），只剩清数据一条路。所以「设置」不给关。
         */
        val REQUIRED_NAV_ITEMS: Set<NavItem> = setOf(NavItem.SETTINGS)

        /**
         * 设置页列出来的入口：必需项不出现在这一层。
         *
         * 必需项给了开关也关不掉，摆一个灰掉的开关只会让人以为自己那下没生效；干脆不列。
         * 底栏照旧按 [AppSettings.navItems] 渲染，必需项由 [normalizeNavItems] 兜住。
         */
        val OPTIONAL_NAV_ITEMS: List<NavItem> =
            NavItem.entries.filterNot { it in REQUIRED_NAV_ITEMS }

        /**
         * 规范化底栏入口：按枚举顺序重排、丢掉不认识的项（防 pref 被手改），
         * [REQUIRED_NAV_ITEMS] 里的项无论传什么都保住。
         *
         * 一个合法的都没有（没存过、或 pref 被改坏）时还给默认三栏而不是「只剩设置」：
         * 第一次启动的底栏不该长得跟别人不一样。剩下的项里若不含必需项，补回来。
         */
        fun normalizeNavItems(raw: Set<NavItem>): Set<NavItem> {
            if (NavItem.entries.none { it in raw }) return DEFAULT_NAV_ITEMS
            return NavItem.entries.filterTo(linkedSetOf()) { it in raw || it in REQUIRED_NAV_ITEMS }
        }

        /**
         * 试解析用户输入的种子。
         *
         * 返回 null 表示「不是一颗合法种子」，由界面提示，而不是悄悄换成 0——
         * 0 在 [com.pict.metatool.ui.edit.EditUiState] 里是「还没定过种子」的哨兵值，
         * 混用会让「换一批」之后又跳回默认种子。
         */
        fun parseSeed(raw: String): Long? = raw.trim().toLongOrNull()
    }
}

/** 路径分隔符与保留字符：Windows / Linux 两侧都不接受，写进文件名只会让 Provider 报错。 */
private val ILLEGAL_SUFFIX_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
