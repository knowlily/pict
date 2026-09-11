package com.pict.metatool.data.source

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 从树 URI 里抠出目录名（FR-03「最近目录」的兜底名字）。
 *
 * 名字的正主是 Provider（`DocumentsContract` 的 `COLUMN_DISPLAY_NAME`，见
 * [SafSource.treeDisplayName]）；这里只在问不到的时候用——授权失效、Provider 不支持
 * 查询、或用户选的是云端盘。**纯字符串处理、不碰 Android API**，所以能直接单测：
 * 真机上的 URI 样子（小米 / 原生 / 下载 Provider）都按样本钉在测试里。
 *
 * 形如 `content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera`
 * 的 URI：取 `/tree/` 之后那段、百分号解码、去掉卷标（`primary:`）、取最后一段目录名。
 * 根目录（`primary:`、`downloads`）没有末段，返回 null——那时候叫「内部存储」还是
 * 「下载」，该由界面上的文案决定，不该由这里猜。
 */
object TreeDocumentName {

    private const val TREE_MARKER = "/tree/"

    fun fromUri(rawUri: String): String? {
        val start = rawUri.indexOf(TREE_MARKER)
        if (start < 0) return null
        val raw = rawUri
            .substring(start + TREE_MARKER.length)
            .substringBefore('?')
            .substringBefore('#')
        val decoded = percentDecode(raw)
        // 去掉卷标前缀（`primary:DCIM` → `DCIM`）；没有冒号就是无卷标的 Provider（`downloads`）
        val path = decoded.substringAfter(':', decoded)
        return path.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    /**
     * 只解 `%XX`，不做 `+` → 空格那套表单规则：URI 里的 `+` 就是加号本身，
     * 按表单规矩解会把 `a+b` 变成 `a b`，那是另一套编码的语义。
     * 坏序列（`%` 后面不是两位十六进制）原样保留，不吞字符。
     */
    private fun percentDecode(raw: String): String {
        if ('%' !in raw) return raw
        val bytes = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val hex = raw.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(StandardCharsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }
}
