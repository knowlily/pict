package com.pict.metatool.domain.preset

/**
 * 分栏多选的选择状态（T3.x：设备 / 位置 / 时间 / 混合四栏并列，可同时选）。
 *
 * 语义就一条：**每栏（[PresetKind]）至多一个**，跨栏可以同时选中。
 * 于是「机型 iPhone 16 Pro + 位置 北京 + 时间 2026 上半年」不必再挤进一个混合预设里，
 * 而是三个各管一段的预设一起套用。同一栏再点一次 = 取消该栏选择（不会出现「一栏俩」）。
 *
 * 套用顺序固定为 [order]（设备 → 位置 → 时间 → 混合），与「套用预设是否覆盖已有值」开关配合：
 * 默认「只填缺失」时后来者只补空位，打开覆盖时后面的分类会盖掉前面的同名键。
 * 顺序固定意味着**同一组选择的套用结果稳定**，用户第二次点得到同样的结果。
 */
object PresetSelection {

    /** 套用顺序（= 枚举声明顺序），UI 分栏也用它排序。 */
    val order: List<PresetKind> = PresetKind.entries

    /** 点一下：未选中 → 选中该栏；已选中 → 取消该栏。 */
    fun toggle(selection: Map<PresetKind, String>, preset: Preset): Map<PresetKind, String> =
        if (selection[preset.kind] == preset.id) {
            selection - preset.kind
        } else {
            selection + (preset.kind to preset.id)
        }

    /** 按套用顺序把选择解析成预设；查不到的（比如自建预设被删了）自动跳过。 */
    fun orderedPresets(selection: Map<PresetKind, String>, presets: List<Preset>): List<Preset> =
        order.mapNotNull { kind -> selection[kind]?.let { id -> presets.firstOrNull { it.id == id } } }

    fun orderedPresets(selection: Map<PresetKind, String>, catalog: PresetCatalog): List<Preset> =
        order.mapNotNull { kind -> selection[kind]?.let(catalog::byId) }

    fun orderedIds(selection: Map<PresetKind, String>, presets: List<Preset>): List<String> =
        orderedPresets(selection, presets).map { it.id }

    fun orderedIds(selection: Map<PresetKind, String>, catalog: PresetCatalog): List<String> =
        orderedPresets(selection, catalog).map { it.id }

    /**
     * 从 id 列表还原选择（批量草稿里存的是 id 列表）。
     * 同类出现多个时**后者胜**，用来兼容早期版本或手工改坏的草稿。
     */
    fun of(ids: List<String>, presets: List<Preset>): Map<PresetKind, String> {
        val result = LinkedHashMap<PresetKind, String>()
        ids.forEach { id ->
            presets.firstOrNull { it.id == id }?.let { preset -> result[preset.kind] = preset.id }
        }
        return result
    }

    fun of(ids: List<String>, catalog: PresetCatalog): Map<PresetKind, String> = of(ids, catalog.all())

    /** 分栏都空时的提示行：`选一个，或几栏各选一个`。 */
    const val EMPTY_HINT: String = "选一个，或几栏各选一个"

    /**
     * 分成四栏（含**空栏**）。
     *
     * 空栏也要留着：栏头挂着「自己加一个」，用户想在「时间」那栏加预设时得先看得见那一栏；
     * 空栏直接消失会让人以为这一类不支持。
     */
    fun byKind(presets: List<Preset>): List<Pair<PresetKind, List<Preset>>> =
        order.map { kind -> kind to presets.filter { it.kind == kind } }

    /** 「iPhone 16 Pro + 北京」这样的选择摘要；空选给 [EMPTY_HINT]。 */
    fun label(selection: Map<PresetKind, String>, presets: List<Preset>): String {
        val names = orderedPresets(selection, presets).map { it.name }
        return if (names.isEmpty()) EMPTY_HINT else names.joinToString(" + ")
    }

    fun label(selection: Map<PresetKind, String>, catalog: PresetCatalog): String =
        label(selection, catalog.all())

    /** 批量草稿直接问「这几个 id 叫什么」。 */
    fun labelOf(ids: List<String>, presets: List<Preset>): String = label(of(ids, presets), presets)

    fun labelOf(ids: List<String>, catalog: PresetCatalog): String = labelOf(ids, catalog.all())
}
