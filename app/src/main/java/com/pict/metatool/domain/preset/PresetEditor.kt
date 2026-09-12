package com.pict.metatool.domain.preset

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf

/**
 * 用户自建预设的读写口子（编辑页与批量页共用）。
 *
 * 为什么是接口而不是直接用存储类：`ui/` 只依赖 `domain/`，可写目录是 `data/` 的事
 * （`files/presets/`），单测里换成内存实现就不用碰真实文件系统。
 * 不接存储时用 [NONE]：读得到（走目录），写会明确失败，而不是假装成功。
 */
interface PresetEditor {

    /** 用户目录里读不动的那几个文件的问题清单（UI 折一行提示，不挡用内置预设）。 */
    val userIssues: List<PresetIssue>

    fun isUserPreset(id: String): Boolean

    /** 给新建的预设挑一个没被占用的 id。 */
    fun nextId(name: String): String

    /** 新建（`input.id == null`）或覆盖保存；成功时返回**落盘后**读回来的预设。 */
    fun save(input: UserPresetInput): PictResult<Preset>

    fun delete(id: String): Boolean

    companion object {

        /** 没接存储时的空实现：能看不能写。 */
        val NONE: PresetEditor = object : PresetEditor {
            override val userIssues: List<PresetIssue> = emptyList()
            override fun isUserPreset(id: String): Boolean = false
            override fun nextId(name: String): String = UserPresetIds.suggest(name, emptySet())
            override fun save(input: UserPresetInput): PictResult<Preset> =
                failureOf(PictError.IO_WRITE, "没有可写的预设目录")
            override fun delete(id: String): Boolean = false
        }
    }
}

/**
 * 用户预设的 id 与文件名（`presets/README.md`：用户目录里的文件叫 `user-<id>.json`）。
 *
 * id 必须过 `PresetParser` 的 `^[a-z0-9][a-z0-9._-]{2,63}$`，
 * 所以中文名会退化成 `user.preset`，再靠 `-2`、`-3` 保持唯一 —— 可读性让位给「一定能存进去」。
 */
object UserPresetIds {

    const val PREFIX = "user."
    const val FALLBACK_SLUG = "preset"
    private const val MAX_SLUG = 40

    /** 名字里能当 slug 的部分：ASCII 字母数字保留下划线连字符，其余折成 `-`。 */
    fun slug(name: String): String {
        val raw = name.lowercase()
            .map { char -> if (char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_') char else '-' }
            .joinToString("")
            .trim('-', '_')
            .replace(Regex("-{2,}"), "-")
            .take(MAX_SLUG)
            .trim('-', '_')
        val candidate = if (raw.firstOrNull()?.isLetterOrDigit() == true) raw else raw.dropWhile { !it.isLetterOrDigit() }
        return candidate.takeIf { it.length >= 2 } ?: FALLBACK_SLUG
    }

    /** `user.<slug>`；被占了就补 `-2`、`-3`…（[taken] 传现有全部 id）。 */
    fun suggest(name: String, taken: Set<String>): String {
        val base = "$PREFIX${slug(name)}"
        if (base !in taken) return base
        var index = 2
        while ("$base-$index" in taken) index++
        return "$base-$index"
    }

    /**
     * id → 文件名：`user.iphone-15-pro` → `user-iphone-15-pro.json`。
     *
     * 注意前缀是 **`user-`** 不是 `user.`：磁盘上的命名空间用连字符（`presets/README.md`），
     * id 里的 `.` 是层级分隔符。两处混了的话，写出去的文件会认不出来——
     * `UserPresetStore.load()` 只认 `user-` 开头，用户就会看到「明明保存了，列表里却没有」。
     */
    fun fileName(id: String): String {
        val slug = id.removePrefix(PREFIX).replace('.', '-').trim('-')
            .takeIf { it.isNotBlank() } ?: FALLBACK_SLUG
        return "${PREFIX.dropLast(1)}-$slug.json"
    }
}
