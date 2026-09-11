package com.pict.metatool.ui.navigation

import android.net.Uri

/**
 * 批量编辑页路由（二级页面：进页面时收起底部导航，与详情页/编辑页同规格）。
 *
 * 一批图没法塞进一个路径段，这里把每个地址单独编码后用 `,` 串起来当**一个**参数传：
 * `Uri.encode` 会把地址里本来就有的逗号（少见但合法）转成 `%2C`，所以剩下的逗号
 * 只可能是分隔符，拆分不会把一条地址劈成两半。
 *
 * 与编辑页同一套编码口径（见 [EditRoute]）：Navigation 取出路径参数时**整体解码一次**，
 * 因此 [parse] 只按分隔符拆、不再二次解码——再解一次会把 `%2520` 这类还原错。
 */
object BatchRoute {

    const val ARG_URIS: String = "uris"

    /** NavHost 里注册用的模式串。 */
    const val PATTERN: String = "batch/{$ARG_URIS}"

    private const val SEPARATOR = ","

    /** 由一批图片地址拼出跳转目标；一张都没有时返回 null（调用方据此不发导航）。 */
    fun build(uris: List<String>): String? {
        val clean = uris.filter { it.isNotBlank() }
        if (clean.isEmpty()) return null
        return "batch/" + clean.joinToString(SEPARATOR) { Uri.encode(it) }
    }

    /**
     * 拆回图片地址列表。
     *
     * 只有地址、没有来源信息：文件名先按地址末段凑一个占位，真正的名字在预览读取时
     * 从文件里读出来（`BatchPreviewer` 会把来源信息换成真实读到的那份）。
     */
    fun parse(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
}
