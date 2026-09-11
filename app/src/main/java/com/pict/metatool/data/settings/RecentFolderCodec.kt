package com.pict.metatool.data.settings

import com.pict.metatool.domain.settings.RecentFolder

/**
 * 「最近目录」的存盘编码（FR-03）。
 *
 * 格式：一条一行，行内三段用 `\u001F`（单元分隔符）隔开 —— `名字 ␟ URI ␟ 时间戳`。
 * 不用逗号或制表符：URI 里逗号本来就合法（`%2C` 解码出来就是逗号），目录名里也可能被人
 * 塞进制表符。`\u001F` 与换行在写入前会被 [sanitize] 换成空格，于是读到什么就是存了什么。
 *
 * 解码对坏数据宽容：段数不对、时间戳不是数字的行**整行丢掉**，不牵连后面正常的记录、
 * 也不抛异常——pref 被手改过不该让图库页打不开（与 `AppSettings.normalized` 一个态度）。
 */
object RecentFolderCodec {

    private const val FIELD_SEPARATOR = '\u001F'

    fun encode(folders: List<RecentFolder>): String = folders.joinToString(separator = "\n") { folder ->
        listOf(
            sanitize(folder.name),
            sanitize(folder.uri),
            folder.usedAtMillis.toString(),
        ).joinToString(separator = FIELD_SEPARATOR.toString())
    }

    fun decode(raw: String?): List<RecentFolder> = raw
        ?.split('\n')
        ?.mapNotNull(::decodeLine)
        ?: emptyList()

    private fun decodeLine(line: String): RecentFolder? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != 3) return null
        val name = parts[0].trim()
        val uri = parts[1].trim()
        val usedAtMillis = parts[2].trim().toLongOrNull() ?: return null
        if (name.isEmpty() || uri.isEmpty()) return null
        return RecentFolder(uri = uri, name = name, usedAtMillis = usedAtMillis)
    }

    /** 分隔符与控制字符换成空格：它们是编码自己的骨架，不能出现在内容里。 */
    private fun sanitize(raw: String): String = raw
        .replace(FIELD_SEPARATOR, ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
}
