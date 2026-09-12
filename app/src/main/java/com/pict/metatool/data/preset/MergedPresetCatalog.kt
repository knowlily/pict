package com.pict.metatool.data.preset

import android.content.Context
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetCatalog
import com.pict.metatool.domain.preset.PresetEditor
import com.pict.metatool.domain.preset.PresetIssue
import com.pict.metatool.domain.preset.UserPresetIds
import com.pict.metatool.domain.preset.UserPresetInput

/**
 * 内置预设（assets）+ 用户自建预设（`files/presets/`）合成一个目录，同时兼当 [PresetEditor]。
 *
 * 用户预设的读取结果**缓存一次**，写/删之后失效重读：预设弹层会被反复打开，
 * 不能每开一次都去解析一遍用户目录；但写完之后又必须立刻看到新的那份。
 *
 * 同名冲突口径：内置优先（内置 id 是仓库里钉住的，用户预设不该能顶掉它）。
 * 显示顺序跟冲突口径相反：**自建排在各栏前面**（自己刚加的一眼就能看到），内置跟在后头；
 * 内置多的栏（设备 19 张、位置 10 张）里，自建若排尾就得横滑一屏才找得到。
 * 用户目录里坏掉的文件进 [userIssues]，只折成一行提示，不影响其余预设使用。
 */
class MergedPresetCatalog(
    private val builtin: PresetCatalog,
    private val store: UserPresetStore,
) : PresetCatalog, PresetEditor {

    private var cached: UserPresetStore.Loaded? = null

    private fun user(): UserPresetStore.Loaded = cached ?: store.load().also { cached = it }

    override fun all(): List<Preset> = user().presets + builtin.all()

    override fun byId(id: String): Preset? =
        builtin.byId(id) ?: user().presets.firstOrNull { it.id == id }

    /** 安装包里那几个文件的问题（用户目录的在 [userIssues]）。 */
    override val issues: List<PresetIssue> get() = builtin.issues

    override fun isUserPreset(id: String): Boolean = user().presets.any { it.id == id }

    override val userIssues: List<PresetIssue> get() = user().issues

    override fun nextId(name: String): String {
        val taken = (builtin.all() + user().presets).map { it.id }.toSet() + store.ids()
        return UserPresetIds.suggest(name, taken)
    }

    override fun save(input: UserPresetInput): PictResult<Preset> {
        input.validate()?.let { problem -> return failureOf(PictError.FIELD_INVALID, problem) }
        val id = input.id ?: nextId(input.name)
        val saved = store.save(input.toPreset(id))
        if (saved.isSuccess) cached = null
        return saved
    }

    override fun delete(id: String): Boolean {
        if (!isUserPreset(id)) return false
        return store.delete(id).also { removed -> if (removed) cached = null }
    }

    /** 丢掉读取缓存（用户在文件管理器里换了文件时也能自救）。 */
    fun refresh() {
        cached = null
    }

    companion object {

        /** App 里默认的那一份：assets 内置 + `files/presets/` 自建。 */
        fun of(context: Context): MergedPresetCatalog = MergedPresetCatalog(
            builtin = AssetPresetCatalog(context.applicationContext.assets),
            store = UserPresetStore.of(context),
        )
    }
}
