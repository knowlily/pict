package com.pict.metatool.data.metadata

import android.content.ContentResolver
import android.net.Uri
import com.pict.metatool.core.result.PictResult

/**
 * 像素指纹（docs/07 T2.4）。
 *
 * 约定：同一份像素必须给出同一个值，像素有任何变化必须给出不同的值。
 * 抽成接口是为了让比对逻辑能在 JVM 单测里跑——真实实现要解码图像，只有设备上才有。
 */
fun interface PixelHasher {

    /** 读取 [uri] 指向的图像像素指纹；打不开或解不了码时返回失败。 */
    suspend fun hashOf(resolver: ContentResolver, uri: Uri): PictResult<String>
}
