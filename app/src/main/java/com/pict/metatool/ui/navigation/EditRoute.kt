package com.pict.metatool.ui.navigation

import android.net.Uri

/**
 * 编辑页路由（二级页面：进页面时收起底部导航，与详情页同规格）。
 *
 * 参数同样是图片地址，`content://` 里的 `/` 必须整段编码后再拼进路由；
 * Navigation 取出参数时自动解码（见 [DetailRoute]）。
 */
object EditRoute {
    const val ARG_URI: String = "uri"

    /** NavHost 里注册用的模式串。 */
    const val PATTERN: String = "edit/{$ARG_URI}"

    /** 由图片地址拼出跳转目标。 */
    fun build(uri: String): String = "edit/${Uri.encode(uri)}"
}
