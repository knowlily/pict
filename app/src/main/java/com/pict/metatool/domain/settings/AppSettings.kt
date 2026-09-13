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
 * 最近导入过的目录（docs/01 FR-03：「杀进程重启后仍可直接访问该目录」）。
 *
 * 只记 URI 与名字：存文件列表没有意义（列表每次重新扫，还可能已经变了），
 * 这里要回答的只是「上次在哪儿」。
 *
 * 授权是否仍然有效**不在这里判断**——那是设备的事（`persistedUriPermissions`），
 * 由 `data/source` 层每次用之前校验；这条记录只保证存下来的值合法、可去重、有上限。
 */
data class RecentFolder(
    /** SAF 树 URI 的字符串形式。 */
    val uri: String,
    /** 给用户看的目录名。 */
    val name: String,
    /** 上次使用时刻：排序与「最近」的依据。 */
    val usedAtMillis: Long,
)

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
    /**
     * 允不允许**直接改动原文件**（原地写）。
     *
     * 默认**关**：不管图是从哪儿导进来的（相册选择器、文件管理器、文件夹导入），
     * 改动一律写成新文件，原图一个字节都不动。理由是把「改坏了」的代价降到零——
     * 不改原件就不需要先备份、也不需要解释备份在哪。
     *
     * 打开之后，可写来源的编辑页顶栏会多出「应用」，点它就地覆盖原文件（改前自动备份）。
     * 关着的时候顶栏只有一个「另存」：`导出` 与 `另存` 合并成同一个动作（都是写副本），
     * 摆两个做同一件事的按钮只会让人猜它们有什么区别。
     */
    val inPlaceEditing: Boolean = DEFAULT_IN_PLACE_EDITING,
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
    /**
     * 底栏是不是液态玻璃（只有悬浮样式用得上）。
     *
     * 「悬浮／贴底」说的是底栏长什么样，这一项说的是**那层玻璃要不要**：关掉 = 一层实心底栏，
     * 不录背板、不模糊、不透光。嫌玻璃糊得看不清底下、或者机器糊不动想省一次离屏绘制时都有得选。
     */
    val liquidGlass: Boolean = DEFAULT_LIQUID_GLASS,
    /**
     * 取色来源（FR-38）：true = 跟着壁纸走（Material You），false = 用应用自带的品牌配色。
     *
     * 这一项管的是**颜色从哪来**，跟深浅色是两件事：`themeMode` 定深浅，这一项定色相。
     * 只在 Android 12（API 31）及以上有效——系统从那一版起才给得出壁纸调色板
     * （判断在 `supportsDynamicColor`，域层不认识 SDK 号，所以这里只管存）。
     */
    val dynamicColor: Boolean = DEFAULT_DYNAMIC_COLOR,
    /**
     * 页面底色（FR-38 续）：关掉动态取色之后，底色可以自己挑。
     *
     * 存 ARGB 色号，[BACKGROUND_AUTO] = 不改、用主题自带的那一个。取值来自 [Backdrop] 那几个
     * 低饱和浅底；深色主题下由 `backdropArgbFor` 按同一色相压暗，所以一份色号两种主题都能用。
     * 认不出来的色号在 [normalized] 里回到 AUTO——这里只认自己发出去的那几个。
     */
    val backgroundColor: Long = BACKGROUND_AUTO,
    /**
     * 最近导入过的目录，最近用的排前面（FR-03）。
     *
     * 授权本身早就持久化了，缺的一直是「上次是哪个目录」这笔账：重启之后只能重新点
     * 「选择文件夹」把同一棵树再挑一遍。存的是目录 URI 与名字，不是文件列表——
     * 列表每次重新扫，目录才是「上次在这儿」这件事的答案。
     */
    val recentFolders: List<RecentFolder> = emptyList(),
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
        backgroundColor = Backdrop.fromArgb(backgroundColor).argb,
        navItems = normalizeNavItems(navItems),
        recentFolders = normalizeRecentFolders(recentFolders),
    )

    /**
     * 记下一次走通的目录：新的一条排在最前面，同一目录只留最新那一次。
     *
     * 只在**授权拿到手之后**记（见 `LibraryViewModel.importFolder`）：没拿到授权就记下来，
     * 下次点它会直接失败，那是在给人挖坑。
     */
    fun withRecentFolder(uri: String, name: String, usedAtMillis: Long): AppSettings = copy(
        recentFolders = normalizeRecentFolders(
            listOf(RecentFolder(uri = uri, name = name, usedAtMillis = usedAtMillis)) + recentFolders,
        ),
    )

    companion object {

        /** 默认后缀。文件名里出现的一律半角字符，别写全角「－」。 */
        const val DEFAULT_EXPORT_SUFFIX: String = "-edited"

        const val DEFAULT_VERIFY_AFTER_EXPORT: Boolean = true

        /**
         * 默认**不**直接改动原文件。
         *
         * 这条默认值是有意的：改原图是不可逆的（哪怕有备份，用户也得先知道备份在哪），
         * 而写成副本永远安全。想就地改的人去设置里打开，一次开关换长期习惯。
         */
        const val DEFAULT_IN_PLACE_EDITING: Boolean = false

        const val DEFAULT_PRESET_OVERWRITE: Boolean = false

        const val DEFAULT_RANDOM_SEED: Long = 20260101L

        const val DEFAULT_GRID_COLUMNS: Int = 3

        const val MIN_GRID_COLUMNS: Int = 2

        const val MAX_GRID_COLUMNS: Int = 4

        /** 默认底栏样式：胶囊。 */
        val DEFAULT_NAV_BAR_STYLE: NavBarStyle = NavBarStyle.FLOATING

        /** 默认开玻璃：悬浮样式的默认观感就是它。 */
        const val DEFAULT_LIQUID_GLASS: Boolean = true

        /**
         * 默认跟着壁纸取色（FR-38）。
         *
         * Material You 是 Android 12+ 上用户已经习惯的样子：应用一开就跟着系统，不用先去设置里翻。
         * 品牌配色没被丢掉——关掉这一项就回到 [BluePrimary] 那一套，设置里一行就能切。
         * 12 以下的机器上系统给不出调色板，这个 true 会被 `supportsDynamicColor` 拦下。
         */
        const val DEFAULT_DYNAMIC_COLOR: Boolean = true

        /** 底色「自动」：不改主题自带的那一个（也是存量设置读不到这个键时的取值）。 */
        const val BACKGROUND_AUTO: Long = 0L

        /** 默认三栏全开。 */
        val DEFAULT_NAV_ITEMS: Set<NavItem> = NavItem.entries.toSet()

        /**
         * 后缀长度上限：够写「-已编辑-备份」这类，又不会把文件名顶到 Provider 的截断线
         * （[com.pict.metatool.domain.naming.ExportNaming.MAX_LENGTH] 那个 100 字符）。
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

        /** 最近目录最多记这几条：再多也不叫「最近」，只是没删干净的历史。 */
        const val MAX_RECENT_FOLDERS: Int = 10

        /**
         * 规范化最近目录：丢掉空 URI / 空名字的、同一目录只留最近那一次、
         * 按时间倒序、最多 [MAX_RECENT_FOLDERS] 条。
         *
         * 去重与上限放在这里而不是写盘那侧：读回来的旧账（比如以后把上限调小）
         * 也该在进内存的时候就被收干净，别的代码就不用各自再防一遍。
         */
        fun normalizeRecentFolders(raw: List<RecentFolder>): List<RecentFolder> = raw
            .filter { it.uri.isNotBlank() && it.name.isNotBlank() }
            .sortedByDescending { it.usedAtMillis }
            .distinctBy { it.uri }
            .take(MAX_RECENT_FOLDERS)

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
