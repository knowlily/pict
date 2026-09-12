package com.pict.metatool.data.preset

import android.content.Context
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetIssue
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.domain.preset.PresetParseResult
import com.pict.metatool.domain.preset.PresetParser
import com.pict.metatool.domain.preset.PresetWriter
import com.pict.metatool.domain.preset.UserPresetIds
import java.io.File

/**
 * 用户自建预设的落盘位置：`files/presets/user-<id>.json`（`presets/README.md` 的目录约定）。
 *
 * 几个刻意的选择：
 * - **一个预设一个文件**，不是全塞进一个大 JSON：坏掉一个只丢一个，用户也能把文件直接拷进拷出；
 * - **先写 `.tmp` 再改名**：写一半被掐掉，留下的只会是多出来的临时文件，不会毁掉原来那份；
 * - **写完立刻用 [PresetParser] 读回来验一遍**，验不过就不落盘 —— 存进去的一定读得出来，
 *   否则用户会看到「明明保存了，列表里却没有」这种最气人的状态。
 */
class UserPresetStore(private val dir: File) {

    data class Loaded(val presets: List<Preset>, val issues: List<PresetIssue>)

    /** 目录里 `user-*.json` 全部读出来；坏文件逐条记进 issues，其余照常可用。 */
    fun load(): Loaded {
        val files = runCatching {
            dir.listFiles { file -> file.isFile && isUserFile(file.name) }
        }.getOrNull().orEmpty().sortedBy { it.name }

        val presets = mutableListOf<Preset>()
        val issues = mutableListOf<PresetIssue>()
        files.forEach { file ->
            val text = runCatching { file.readText() }.getOrElse { throwable ->
                issues += PresetIssue(file.name, "读取失败：${throwable.message ?: "未知原因"}")
                return@forEach
            }
            when (val parsed = PresetParser.parse(text, PresetOrigin.USER, file.name)) {
                is PresetParseResult.Success -> {
                    presets += parsed.preset
                    issues += parsed.warnings
                }

                is PresetParseResult.Invalid -> parsed.issues.forEach { issue ->
                    issues += PresetIssue("${file.name} · ${issue.path}", issue.message, issue.level)
                }
            }
        }
        return Loaded(presets, issues)
    }

    /** 写一份预设（新建或覆盖同名 id）。成功返回**读回来**的那份。 */
    fun save(preset: Preset): PictResult<Preset> {
        val text = PresetWriter.write(preset, PresetWriter.SOURCE_USER)
        val fileName = UserPresetIds.fileName(preset.id)
        val parsed = PresetParser.parse(text, PresetOrigin.USER, fileName)
        if (parsed is PresetParseResult.Invalid) {
            return failureOf(PictError.FIELD_INVALID, parsed.issues.joinToString())
        }
        val verified = (parsed as PresetParseResult.Success).preset

        if (!dir.isDirectory && !dir.mkdirs()) {
            return failureOf(PictError.IO_WRITE, "建不了目录 ${dir.absolutePath}")
        }
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.tmp")
        return runCatching {
            temp.writeText(text)
            if (target.exists() && !target.delete()) error("删不掉旧文件 ${target.name}")
            if (!temp.renameTo(target)) error("改名失败 ${temp.name} → ${target.name}")
        }.fold(
            onSuccess = { PictResult.Success(verified) },
            onFailure = { throwable -> failureOf(PictError.IO_WRITE, throwable.message, throwable) },
        )
    }

    /** 删掉一份用户预设；文件本来就不在也算成功（用户要的结果已经成立）。 */
    fun delete(id: String): Boolean {
        val target = fileOf(id)
        return !target.exists() || runCatching { target.delete() }.getOrDefault(false)
    }

    fun fileOf(id: String): File = File(dir, UserPresetIds.fileName(id))

    /** 目录里现有的全部 id（给 [UserPresetIds.suggest] 避重）。 */
    fun ids(): Set<String> = load().presets.map { it.id }.toSet()

    val directory: File get() = dir

    private fun isUserFile(name: String): Boolean =
        name.startsWith("${UserPresetIds.PREFIX.dropLast(1)}-") && name.endsWith(".json")

    companion object {

        /** 应用私有目录名（相对 `filesDir`）。 */
        const val DIR_NAME = "presets"

        fun of(context: Context): UserPresetStore =
            UserPresetStore(File(context.applicationContext.filesDir, DIR_NAME))
    }
}
