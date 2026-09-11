package com.pict.metatool.data.batch

import android.content.ContentResolver
import android.net.Uri
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.map
import com.pict.metatool.data.metadata.MetadataReader
import com.pict.metatool.data.metadata.MetadataWriter
import com.pict.metatool.data.metadata.exif.ExifMetadataStore
import com.pict.metatool.data.metadata.imaging.CommonsImagingStore
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.batch.BatchSourceReader
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.model.MetadataSet

/**
 * 挂在 SAF 上的批量预览读取器：把 [BatchTarget] 接回真实的 `ContentResolver`。
 *
 * 读取走的是与编辑页**同一条路由**（[MetadataReader] 三读取器并行 + 合并），
 * 于是「预览里看到的字段」和「编辑页里看到的字段」不会出现两套答案——
 * 预览要是用了别的读法，用户就会在两处看到不同的「现值」。
 *
 * ### FR-32：这个类没有写路径
 * 不 `openOutputStream`、不建临时文件、不碰备份。可写性只是纯判断——
 * [BatchTarget.writable]（SAF 授权位）+ [MetadataWriter.canWrite]（格式支不支持），
 * 两者都只看已经查好的来源快照，不读文件内容。
 */
class SafBatchSourceReader(
    private val resolver: ContentResolver,
    private val reader: MetadataReader = MetadataReader(),
    private val writers: List<MetadataWriter> = defaultWriters(),
) : BatchSourceReader {

    /** 同一个目标在一次预览里只算一次可写性：路由判断是纯函数，但没必要问两遍。 */
    private val writableCache = mutableMapOf<String, Boolean>()

    /**
     * 授权位与格式支持都得点头：
     * - `writable == false`：SAF 只给了读权限（相册选择器常见），写了会抛 SecurityException；
     * - 格式没有写入器：HEIF / DNG 这类「读得到、原地写不了」。
     * 前者的原因由 `BatchPreviewer` 单独报（只读 ≠ 格式不支持），这里只负责返回 false。
     */
    override fun canWriteTo(target: BatchTarget): Boolean = writableCache.getOrPut(target.uri) {
        target.writable && writers.any { it.canWrite(target.info) }
    }

    override suspend fun read(target: BatchTarget): PictResult<MetadataSet> {
        val uri = runCatching { Uri.parse(target.uri) }.getOrNull()
            ?: return failureOf(PictError.IO_OPEN, "不是合法的图片地址：${target.uri}")
        return reader.read(resolver, ImageSource(uri = uri, info = target.info)).map { it.set }
    }

    companion object {

        /** 与编辑页同一份写入器集合；顺序固定（先 ExifInterface，后 CommonsImaging）。 */
        fun defaultWriters(): List<MetadataWriter> =
            listOf(ExifMetadataStore(), CommonsImagingStore())
    }
}
