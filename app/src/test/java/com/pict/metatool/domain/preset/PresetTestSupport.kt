package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import java.io.File

/**
 * 预设相关单测的公共夹具（docs/07 T3.1/T3.4）。
 *
 * 预设 JSON 的真相在仓库根 `presets/`，所以测试直接读文件而不是复制一份进 `resources/`：
 * 复制出来的那份改不动真文件，久而久之就会出现「测试全绿、APK 里的预设是坏的」。
 * 单测的工作目录是模块目录（`app/`），因此先看 `../presets`。
 */
object PresetTestSupport {

    private const val MARKER = "device-iphone-16-pro.json"

    val presetsDir: File = listOf(File("../presets"), File("presets"))
        .firstOrNull { it.isDirectory && it.resolve(MARKER).isFile }
        ?: error("找不到 presets/ 目录（工作目录：${File(".").absolutePath}）")

    fun builtinFiles(): List<File> =
        presetsDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 全部内置预设；解析失败即让测试失败并带上文件名与逐条问题。 */
    fun builtinPresets(): List<Preset> = builtinFiles().map { file ->
        when (val result = PresetParser.parse(file.readText(), PresetOrigin.BUILTIN, file.name)) {
            is PresetParseResult.Success -> result.preset
            is PresetParseResult.Invalid -> error("${file.name} 解析失败：${result.issues.joinToString()}")
        }
    }

    fun preset(id: String): Preset =
        builtinPresets().firstOrNull { it.id == id } ?: error("没有内置预设 $id")

    /** 内存预设目录，供 `PresetResolver` 使用。 */
    fun catalog(vararg presets: Preset): PresetCatalog = object : PresetCatalog {
        private val items = presets.toList()
        override fun all(): List<Preset> = items
        override fun byId(id: String): Preset? = items.firstOrNull { it.id == id }
    }

    /** 空元数据集（只有来源信息，没有任何字段）。 */
    fun emptySource(): MetadataSet = MetadataSet(
        source = SourceInfo("test.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG),
    )

    /** 解析内联预设 JSON；失败直接崩测试。 */
    fun parse(json: String, fileName: String = "inline.json"): Preset =
        when (val result = PresetParser.parse(json, PresetOrigin.BUILTIN, fileName)) {
            is PresetParseResult.Success -> result.preset
            is PresetParseResult.Invalid -> error("内联预设解析失败：${result.issues.joinToString()}")
        }

    fun key(full: String): TagKey = TagKey.of(full)
}
