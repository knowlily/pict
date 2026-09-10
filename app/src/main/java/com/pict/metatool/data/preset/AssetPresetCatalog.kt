package com.pict.metatool.data.preset

import android.content.res.AssetManager
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetCatalog
import com.pict.metatool.domain.preset.PresetIssue
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.domain.preset.PresetParseResult
import com.pict.metatool.domain.preset.PresetParser

/**
 * 从 APK 的 `assets/presets/` 下那几份 `.json` 读内置预设（docs/07 T3.2 的加载部分）。
 *
 * 注意：Kotlin 的块注释可嵌套，所以这里不能写 `assets/presets/` 加星号通配——
 * 那个星号斜杠会被当成注释开始，整个文件跟着报「Unclosed comment」。
 *
 * 这些 JSON 由 `app/build.gradle.kts` 的 `syncPresets` 任务从仓库根 `presets/` 同步进来，
 * 因此包里读到的就是仓库里那几份文件——不存在「仓库改了、APK 里还是旧的」。
 *
 * 容错口径：**单个文件坏掉不让整个预设功能打不开**。坏文件逐条记进 [issues] 并跳过，
 * 其余预设照常可用；[issues] 会一路带到界面上，而不是悄悄吞掉。
 *
 * 结果缓存一次：预设是打包资源，进程生命周期内不会变，没必要每次开弹层都读盘解析。
 */
class AssetPresetCatalog(
    private val assets: AssetManager,
    private val dir: String = "presets",
) : PresetCatalog {

    private val loaded: Loaded by lazy { load() }

    override fun all(): List<Preset> = loaded.presets

    override fun byId(id: String): Preset? = loaded.presets.firstOrNull { it.id == id }

    /** 加载期的问题（含解析告警），点分路径 + 文件名，便于直接去改 JSON。 */
    val issues: List<PresetIssue> get() = loaded.issues

    data class Loaded(val presets: List<Preset>, val issues: List<PresetIssue>)

    private fun load(): Loaded {
        val names = runCatching {
            assets.list(dir)?.filter { it.endsWith(".json") }?.sorted()
        }.getOrNull().orEmpty()

        val presets = mutableListOf<Preset>()
        val issues = mutableListOf<PresetIssue>()
        names.forEach { name ->
            val text = runCatching {
                assets.open("$dir/$name").bufferedReader().use { it.readText() }
            }.getOrElse { throwable ->
                issues += PresetIssue(name, "读取失败：${throwable.message ?: "未知原因"}")
                return@forEach
            }
            when (val parsed = PresetParser.parse(text, PresetOrigin.BUILTIN, name)) {
                is PresetParseResult.Success -> {
                    presets += parsed.preset
                    issues += parsed.warnings
                }

                is PresetParseResult.Invalid -> issues += parsed.issues
            }
        }
        return Loaded(presets, issues)
    }
}
