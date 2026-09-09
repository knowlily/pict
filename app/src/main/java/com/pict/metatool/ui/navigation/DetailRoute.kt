package com.pict.metatool.ui.navigation

import android.net.Uri

/**
 * 详情页路由（二级页面：进页面时收起底部导航）。
 *
 * 参数是图片地址。`content://` 里带 `/`，必须整段编码后再拼进路由，
 * 否则 Navigation 会把它当成多级路径；Navigation 取出参数时自动解码。
 */
object DetailRoute {
    const val ARG_URI: String = "uri"

    /** NavHost 里注册用的模式串。 */
    const val PATTERN: String = "detail/{$ARG_URI}"

    /** 由图片地址拼出跳转目标。 */
    fun build(uri: String): String = "detail/${Uri.encode(uri)}"
}
