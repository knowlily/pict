package com.pict.metatool.data.metadata.extractor

import android.content.ContentResolver
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.ImageProcessingException
import com.drew.metadata.Directory
import com.drew.metadata.Metadata
import com.drew.metadata.iptc.IptcDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.metadata.MetadataStore
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Date

/**
 * IPTC / 文件结构读取（docs/07 T1.5，docs/02 §5）。
 *
 * 用 metadata-extractor 2.19.0 补 ExifInterface 读不到的东西：
 * - **IPTC IIM**（APP13 / TIFF 里的 8BIM 0x0404）：标题、关键词、作者、版权、城市、说明等，
 *   这些是图库/新闻流程里的核心字段，ExifInterface 完全不碰；
 * - 顺带把 JPEG/PNG 的结构信息（宽高、位深、通道数）当作 EXIF 缺失时的兜底。
 *
 * 为什么不用它的 XMP 目录：XMP 已经由 [com.pict.metatool.data.metadata.xmp.XmpParser]
 * 从 ExifInterface 的 `TAG_XMP` 原文解析，重复解析只会让「谁覆盖谁」变复杂。
 *
 * 只保留 [FieldCatalog] 里有定义的键——目录是字段的唯一事实源，读进来不认识的键没有展示口径。
 * 失败一律走 [PictResult.Failure]，不抛异常。
 */
class MetadataExtractorStore : MetadataStore {

    override val id: String = "extractor"

    override fun supports(info: SourceInfo): Boolean = info.format in SUPPORTED

    override suspend fun read(
        resolver: ContentResolver,
        source: ImageSource,
    ): PictResult<MetadataSet> = withContext(Dispatchers.IO) {
        try {
            val stream = resolver.openInputStream(source.uri)
                ?: return@withContext failureOf(PictError.IO_OPEN, "无法打开输入流：${source.uri}")
            stream.use { successOf(readFrom(ImageMetadataReader.readMetadata(it), source.info)) }
        } catch (e: ImageProcessingException) {
            // 文件损坏/格式不认识：不是 IO 问题，单独归类，UI 提示「这张图的元数据读不出来」
            failureOf(PictError.META_PARSE, e.message, e)
        } catch (e: IOException) {
            failureOf(PictError.IO_READ, e.message, e)
        } catch (e: SecurityException) {
            failureOf(PictError.IO_OPEN, "URI 授权已失效，请重新选择图片", e)
        }
    }

    /** 抽出来便于 JVM 单测直接喂样张（metadata-extractor 是纯 Java，不依赖 Android）。 */
    fun readFrom(metadata: Metadata, info: SourceInfo): MetadataSet {
        val entries = LinkedHashMap<TagKey, TagValue>()

        metadata.getDirectoriesOfType(IptcDirectory::class.java).forEach { iptc ->
            iptc.tags.forEach { tag ->
                // IptcDirectory 的常量值就是 record<<8 | dataset（如 517 = 2:5）
                val key = TagKey.of("IPTC:${tag.tagType shr 8}:${tag.tagType and 0xFF}")
                val spec = FieldCatalog.spec(key) ?: return@forEach
                valueOf(iptc, tag.tagType, spec.type)?.let { entries.putIfAbsent(key, it) }
            }
        }

        readStructure(metadata, entries)

        return MetadataSet(info, entries)
    }

    /**
     * 结构信息兜底：EXIF 缺失（例如 PNG/WebP 没写 IFD0）时，用容器自带的宽高/位深/通道数
     * 补上 [FieldCatalog] 里的 FILE 组字段。已有值不覆盖——EXIF 更权威。
     */
    private fun readStructure(metadata: Metadata, entries: MutableMap<TagKey, TagValue>) {
        metadata.getFirstDirectoryOfType(JpegDirectory::class.java)?.let { jpeg ->
            putInt(entries, WIDTH, jpeg, JpegDirectory.TAG_IMAGE_WIDTH)
            putInt(entries, HEIGHT, jpeg, JpegDirectory.TAG_IMAGE_HEIGHT)
            putIntOrArray(entries, BITS_PER_SAMPLE, jpeg, JpegDirectory.TAG_DATA_PRECISION)
            putInt(entries, SAMPLES_PER_PIXEL, jpeg, JpegDirectory.TAG_NUMBER_OF_COMPONENTS)
        }
        metadata.getFirstDirectoryOfType(PngDirectory::class.java)?.let { png ->
            putInt(entries, WIDTH, png, PngDirectory.TAG_IMAGE_WIDTH)
            putInt(entries, HEIGHT, png, PngDirectory.TAG_IMAGE_HEIGHT)
            putIntOrArray(entries, BITS_PER_SAMPLE, png, PngDirectory.TAG_BITS_PER_SAMPLE)
        }
    }

    private fun putInt(
        entries: MutableMap<TagKey, TagValue>,
        key: TagKey,
        dir: Directory,
        tagType: Int,
    ) {
        dir.getInteger(tagType)?.let { entries.putIfAbsent(key, TagValue.IntValue(it.toLong())) }
    }

    /** 位深在不同容器里可能是单值或数组（PNG 多通道），两种都接住。 */
    private fun putIntOrArray(
        entries: MutableMap<TagKey, TagValue>,
        key: TagKey,
        dir: Directory,
        tagType: Int,
    ) {
        val array = dir.getIntArray(tagType)
        val value = when {
            array != null && array.isNotEmpty() -> TagValue.IntList(array.map { it.toLong() })
            else -> dir.getInteger(tagType)?.let { TagValue.IntList(listOf(it.toLong())) }
        }
        value?.let { entries.putIfAbsent(key, it) }
    }

    /**
     * 按目录声明的值类型取值。用 `getStringArray` 而不是 `getString`：
     * 同一数据集重复出现（关键词最常见）时 metadata-extractor 会存成数组，
     * 该方法内部已兼容 String / StringValue / StringValue[] / int[]，不会抛类型转换异常。
     */
    private fun valueOf(dir: IptcDirectory, tagType: Int, type: ValueType): TagValue? = when (type) {
        ValueType.DATE -> dir.getDate(tagType)?.let { TagValue.DateValue(it.toLocalDate()) }

        ValueType.TIME -> dir.getString(tagType)?.let(::parseTime)?.let(TagValue::TimeValue)

        ValueType.INT -> dir.getInteger(tagType)?.let { TagValue.IntValue(it.toLong()) }

        ValueType.INT_LIST -> dir.getIntArray(tagType)
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.toLong() }
            ?.let(TagValue::IntList)

        ValueType.TEXT_SEQ, ValueType.TEXT_BAG -> dir.getStringArray(tagType)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let(TagValue::TextList)

        ValueType.URI -> dir.getString(tagType)?.takeIf { it.isNotBlank() }?.let(TagValue::UriValue)

        else -> dir.getStringArray(tagType)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            ?.let(TagValue::Text)
    }

    /**
     * IPTC 时间形如 `101530+0800`（有些写入器会写成 `10:15:30+08:00`）。
     * 用正则抓 HH[:]MM[:]SS 三段，忽略时区与分隔符。
     */
    private fun parseTime(raw: String): LocalTime? {
        val match = Regex("""(\d{2}):?(\d{2}):?(\d{2})""").find(raw) ?: return null
        return runCatching {
            LocalTime.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }.getOrNull()
    }

    /**
     * metadata-extractor 的 `Date` 是按本地时区拼的「墙上时间」，
     * 转 [java.time.LocalDate] 时用 UTC 偏移量取回原本的年月日，避免时区回退一天。
     */
    private fun Date.toLocalDate() = toInstant().atZone(ZoneOffset.UTC).toLocalDate()

    private companion object {
        val WIDTH = TagKey.of("EXIF:ImageWidth")
        val HEIGHT = TagKey.of("EXIF:ImageLength")
        val BITS_PER_SAMPLE = TagKey.of("EXIF:BitsPerSample")
        val SAMPLES_PER_PIXEL = TagKey.of("EXIF:SamplesPerPixel")

        /**
         * metadata-extractor 2.19.0 覆盖的格式（jar 内 reader 实证）：
         * JPEG/PNG/WebP/TIFF/HEIF/BMP，TIFF 一脉顺带覆盖 DNG 与 CR2/NEF/ARW/ORF/RW2，
         * RAF 有独立 reader。GIF/PSD/PCX/ICO/EPS 不在本应用的处理范围。
         */
        val SUPPORTED = setOf(
            ImageFormatHint.JPEG,
            ImageFormatHint.PNG,
            ImageFormatHint.WEBP,
            ImageFormatHint.TIFF,
            ImageFormatHint.HEIF,
            ImageFormatHint.BMP,
            ImageFormatHint.RAW,
        )
    }
}
