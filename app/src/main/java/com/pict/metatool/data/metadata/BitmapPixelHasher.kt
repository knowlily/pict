package com.pict.metatool.data.metadata

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.error.PictFailure
import com.pict.metatool.core.result.PictResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * 解码像素后算 SHA-256 指纹（docs/07 T2.4）。
 *
 * 元数据写入只该动 APP1 之类的元数据段，像素必须一字不差；这里用「解码结果哈希」近似这件事——
 * 元数据不影响解码像素，像素被破坏则哈希必变。比逐字节比对图像数据段更宽容（重新编码也能比对），
 * 代价是像素级细微改动可能被降采样吃掉，所以默认只在超过 [maxDimension] 时才降采样。
 *
 * 大图解码有 OOM 风险，宁可返回失败也不要让进程挂掉，所以这里捕获 [OutOfMemoryError]。
 */
class BitmapPixelHasher(private val maxDimension: Int = DEFAULT_MAX_DIMENSION) : PixelHasher {

    override suspend fun hashOf(resolver: ContentResolver, uri: Uri): PictResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val boundsStream = resolver.openInputStream(uri)
                    ?: return@withContext failure(PictError.IO_READ, "打不开图像：$uri")
                // inJustDecodeBounds 下 decodeStream 一定返回 null，别拿它的返回值判断成败
                boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext failure(PictError.DECODE, "读不到图像尺寸：$uri")
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@withContext failure(PictError.DECODE, "解码失败：$uri")

                try {
                    PictResult.Success(digest(bitmap))
                } finally {
                    bitmap.recycle()
                }
            } catch (e: IOException) {
                failure(PictError.IO_READ, e.message, e)
            } catch (e: SecurityException) {
                failure(PictError.IO_READ, e.message, e)
            } catch (e: OutOfMemoryError) {
                failure(PictError.OOM, "解码像素时内存不足：$uri")
            }
        }

    /** [BitmapFactory.Options.inSampleSize] 只接受 2 的幂。 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    /** 逐行读像素喂进摘要，避免一次性拿整个像素数组。 */
    private fun digest(bitmap: Bitmap): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(bitmap.width).putInt(bitmap.height).array(),
        )
        val row = IntArray(bitmap.width)
        val buffer = ByteBuffer.allocate(bitmap.width * 4).order(ByteOrder.BIG_ENDIAN)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            buffer.clear()
            row.forEach { buffer.putInt(it) }
            md.update(buffer.array())
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun failure(error: PictError, detail: String?, cause: Throwable? = null): PictResult.Failure =
        PictResult.Failure(PictFailure(error, detail, cause))

    companion object {
        /** 超过这个边长就降采样，兼顾大图内存与指纹敏感度。 */
        const val DEFAULT_MAX_DIMENSION: Int = 4096
    }
}
