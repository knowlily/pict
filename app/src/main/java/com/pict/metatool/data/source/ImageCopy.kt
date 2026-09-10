package com.pict.metatool.data.source

import android.content.ContentResolver
import android.net.Uri
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import java.io.IOException

/**
 * 把一张图的字节整份复制到另一处：导出副本的第一步（docs/06 §3.3「导出」）。
 *
 * 为什么导出要先复制字节：源文件只读是 V1 的硬约束（docs/05 §5），
 * 「另存一份带改动的图」只能在副本上做写入 —— 复制的这份是「源的副本」，
 * 之后的元数据写入发生在副本上，源文件全程不打开写通道。
 *
 * 复制走 64 KB buffer 流式读写，不把整张图读进内存：手机出的 200 MP 图动辄 20 MB+，
 * `readBytes()` 这种写法会在低端机上直接 OOM（docs/09 的 OOM 风险条）。
 */
object ImageCopy {

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * @return 成功时是复制的字节数；失败返回 [PictError.IO_OPEN] / [PictError.IO_WRITE] / [PictError.STORAGE_FULL]
     */
    fun copy(resolver: ContentResolver, from: Uri, to: Uri): PictResult<Long> {
        val input = runCatching { resolver.openInputStream(from) }.getOrNull()
            ?: return failureOf(PictError.IO_OPEN, "读不到源文件：$from")
        val output = runCatching { resolver.openOutputStream(to, "wt") }.getOrNull()
            ?: return run {
                runCatching { input.close() }
                failureOf(PictError.IO_WRITE, "目标文件不可写：$to")
            }

        return try {
            val total = output.use { dst ->
                input.use { src ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var sum = 0L
                    while (true) {
                        val read = src.read(buffer)
                        if (read <= 0) break
                        dst.write(buffer, 0, read)
                        sum += read
                    }
                    dst.flush()
                    sum
                }
            }
            if (total <= 0L) {
                failureOf(PictError.IO_READ, "源文件是空的：$from")
            } else {
                successOf(total)
            }
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            val full = message.contains("ENOSPC", ignoreCase = true) ||
                message.contains("No space left", ignoreCase = true)
            failureOf(if (full) PictError.STORAGE_FULL else PictError.IO_WRITE, "复制失败：$from", e)
        } finally {
            runCatching { input.close() }
        }
    }
}
