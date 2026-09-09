package com.pict.metatool.data.metadata.imaging

import android.content.ContentResolver
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.metadata.MetadataStore
import com.pict.metatool.data.metadata.MetadataWriter
import com.pict.metatool.data.metadata.WriteResult
import com.pict.metatool.data.metadata.exif.ExifValueCodec
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.imaging.FormatCompliance
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.ImagingException
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.common.RationalNumber
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.TiffField
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata
import org.apache.commons.imaging.formats.tiff.TiffReader
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants
import org.apache.commons.imaging.formats.tiff.constants.MicrosoftTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffEpTagConstants
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo
import org.apache.commons.imaging.formats.tiff.write.TiffImageWriterLossless
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * TIFF / RAW 元数据读取（docs/07 T1.6）。
 *
 * 分工：ExifInterface 覆盖 JPEG/PNG/WebP/HEIF，本 store 专攻 TIFF 容器（`.tif/.tiff/.dng`
 * 与 RAW 扩展名），由读取路由合并。相对 ExifInterface 的独有能力是 Windows XP* 标签
 * （`EXIF:XPTitle` 等）与 `EXIF:Rating`——androidx exifinterface 1.4.2 没有 `TAG_XP_*`
 * 常量，只能从这里读到。
 *
 * 关键事实（javap + 真实样张实测，样张见 `tools/make-tiff-sample.py`）：
 * - `Imaging.getMetadata(InputStream, String)` 对 TIFF 返回 [TiffImageMetadata]；
 * - IFD0 的 `TiffField.directoryType` 为 `0`，Exif 子 IFD 为 `-2`，GPS IFD 为 `-3`，
 *   Interop IFD 为 `-4`；同一 tag 号会同时出现在 IFD0 与 IFD1（缩略图），
 *   因此必须按 (tag, directoryType) 定位，否则缩略图的 Compression/XResolution 会串位；
 * - 值形态：ASCII/XP* → `String`，SHORT/INT → `Short`/`Integer`，RATIONAL → [RationalNumber]，
 *   GPS 经纬度/时间 → `RationalNumber[]`，ExifVersion/ComponentsConfiguration → `byte[]`；
 * - 1.0.0-alpha6 的常量类缺 ColorSpace(0xA001)、PixelXDimension(0xA002)、
 *   PixelYDimension(0xA003)、SensitivityType(0x8830)、RecommendedExposureIndex(0x8831)、
 *   OffsetTime*(0x9010-0x9012)，这些按 EXIF 规范的 tag 号手写（见 [MANUAL]）；
 *   `EXIF:SpatialFrequencyResponse` 既无常量也无法可靠推定 tag 号，本任务不读。
 *
 * 已知取舍：只认 TIFF 容器，不引用 `JpegImageMetadata`（其签名含 `java.awt.*`，Android 上
 * 加载有风险）；JPEG 的 XP* 由后续任务按需补。
 */
class CommonsImagingStore : MetadataStore, MetadataWriter {

    override val id: String = ID

    override fun supports(info: SourceInfo): Boolean = info.format in SUPPORTED

    /**
     * 可写格式：TIFF 容器（[TiffImageWriterLossless] 重排 IFD、像素数据原样保留）
     * 与 JPEG（[ExifRewriter] 只换 APP1 段）。
     *
     * 与 ExifMetadataStore 在 JPEG 上能力重叠：那个走 ExifInterface 的原地改写，覆盖面更广
     * （PNG/WebP 与 XMP）；本 store 的 JPEG 通道保证「除 APP1 外一个字节都不动」，
     * 留给需要字节级无损的流程。路由时应优先 ExifMetadataStore。
     *
     * RAW 不声明可写：容器同为 TIFF，但厂商私有 IFD 未验证，不拿用户的原片冒险。
     */
    override fun canWrite(info: SourceInfo): Boolean = info.format in WRITABLE

    override suspend fun read(
        resolver: ContentResolver,
        source: ImageSource,
    ): PictResult<MetadataSet> = withContext(Dispatchers.IO) {
        val stream = try {
            resolver.openInputStream(source.uri)
        } catch (e: IOException) {
            return@withContext failureOf(PictError.IO_OPEN, source.displayName, e)
        } catch (e: SecurityException) {
            return@withContext failureOf(PictError.IO_OPEN, source.displayName, e)
        } ?: return@withContext failureOf(PictError.IO_OPEN, source.displayName)

        try {
            stream.use { input ->
                when (val metadata = Imaging.getMetadata(input, source.displayName)) {
                    null -> successOf(MetadataSet(source.info))
                    is TiffImageMetadata -> successOf(readFrom(metadata, source.info))
                    // 非 TIFF 容器（JPEG/PNG…）不归本 store，交给路由链上的其他实现。
                    else -> successOf(MetadataSet(source.info))
                }
            }
        } catch (e: ImagingException) {
            failureOf(PictError.META_PARSE, source.displayName, e)
        } catch (e: IOException) {
            failureOf(PictError.IO_READ, source.displayName, e)
        } catch (e: RuntimeException) {
            failureOf(PictError.META_PARSE, source.displayName, e)
        }
    }

    override suspend fun write(
        resolver: ContentResolver,
        source: ImageSource,
        target: MetadataSet,
    ): PictResult<WriteResult> = withContext(Dispatchers.IO) {
        if (!canWrite(source.info)) {
            return@withContext failureOf(
                PictError.ENCODE_UNSUPPORTED,
                "${source.info.format.label} 不支持本通道重写，需要重编码（docs/02 §5）",
            )
        }
        try {
            val original = resolver.openInputStream(source.uri)?.use { it.readBytes() }
                ?: return@withContext failureOf(PictError.IO_OPEN, "无法打开输入流：${source.uri}")
            // 先把重写结果整块算出来再落盘：TIFF/JPEG 的重写都要同时看到旧内容与新内容，
            // 而且一次性写入比边算边写少一个「写一半失败」的窗口。
            val output = ByteArrayOutputStream(original.size + HEADROOM_BYTES)
            val result = if (source.info.format == ImageFormatHint.JPEG) {
                rewriteJpeg(original, target, output)
            } else {
                rewriteTiff(original, target, output)
            }
            resolver.openOutputStream(source.uri, "wt")?.use { it.write(output.toByteArray()) }
                ?: return@withContext failureOf(PictError.IO_WRITE, "无法打开输出流：${source.uri}")
            successOf(result)
        } catch (e: ImagingException) {
            failureOf(PictError.META_WRITE, source.displayName, e)
        } catch (e: IOException) {
            failureOf(PictError.IO_WRITE, source.displayName, e)
        } catch (e: RuntimeException) {
            failureOf(PictError.META_WRITE, source.displayName, e)
        }
    }

    /**
     * TIFF 无损重写：IFD 重排后整体写出，像素数据按原始字节搬运（不解码、不再编码）。
     *
     * 纯函数，进出都是字节流，JVM 单测可直接喂样本。
     */
    fun rewriteTiff(original: ByteArray, target: MetadataSet, output: OutputStream): WriteResult {
        val metadata = Imaging.getMetadata(original) as? TiffImageMetadata
            ?: throw ImagingException("不是 TIFF 容器，无法无损重写")
        // 这里用 metadata.outputSet 而不是 outputSetOf：Imaging.getMetadata 走的是会填充 items
        // 的构造，outputSet 里的偏移类字段（strip offsets 之类）已是库自己算好的形态；手动重建
        // 会把它们当普通字段照搬旧值，写出后 strip 定位就没了。
        val outputSet = metadata.outputSet
        val plan = TiffOutputEditor.apply(
            outputSet,
            readFrom(metadata, target.source),
            target,
            ::tagInfoFor,
        )
        TiffImageWriterLossless(original).write(output, outputSet)
        return WriteResult(plan.assignments.keys + plan.removals, plan.dropped)
    }

    /**
     * JPEG 无损 EXIF 重写：只换 APP1 段，段外字节原样搬运。
     *
     * 原 APP1 里已有的字段先整份读进 `TiffOutputSet` 再改，所以没在 [target] 里的字段
     * 不会因为「EXIF 块重新编码」而丢失——这是 `updateExifMetadataLossless` 的语义要求：
     * 它按传入的 set 生成新 APP1，段内没提到的字段就是没有。
     */
    fun rewriteJpeg(original: ByteArray, target: MetadataSet, output: OutputStream): WriteResult {
        val metadata = exifMetadataOf(original)
        val outputSet = metadata?.let { outputSetOf(it) } ?: TiffOutputSet()
        val current = metadata?.let { readFrom(it, target.source) } ?: MetadataSet(target.source)
        val plan = TiffOutputEditor.apply(outputSet, current, target, ::tagInfoFor)
        if (outputSet.isEmpty()) {
            // 原图没有 EXIF、目标也没新增字段：没有目录可写，rewriter 会直接抛
            // "No directories."。原样搬字节比抛异常合理。
            output.write(original)
            return WriteResult(emptySet(), plan.dropped)
        }
        ExifRewriter().updateExifMetadataLossless(original, output, outputSet)
        return WriteResult(plan.assignments.keys + plan.removals, plan.dropped)
    }

    /**
     * 用读出来的字段重建一个可写的输出集。
     *
     * 不能走 [TiffImageMetadata] 的公开访问器：`TiffImageMetadata(TiffContents)` 这个构造只存
     * contents、不填 items 也不填自己的字段列表，`getOutputSet()` / `getAllFields()` 双双为空。
     * 真正装着数据的是 `contents.directories`，所以从那里取，并且沿用原文件的字节序——否则
     * 新建的 set 默认大端，重写出来的 EXIF 会莫名其妙换序。
     */
    private fun outputSetOf(metadata: TiffImageMetadata): TiffOutputSet {
        val contents = metadata.contents
        val byteOrder = contents.header.byteOrder
        val set = TiffOutputSet(byteOrder)
        contents.directories.forEach { raw ->
            val dir = TiffOutputDirectory(raw.type, byteOrder)
            set.addDirectory(dir)
            raw.directoryEntries.forEach { field ->
                dir.add(TiffOutputField(field.tagInfo, field.fieldType, field.count.toInt(), field.byteArrayValue))
            }
        }
        return set
    }

    /** 领域键 → commons-imaging 常量；读写共用同一份 [MAPPING]，口径不会漂。 */
    internal fun tagInfoFor(key: TagKey): TagInfo? = TAG_INFO_BY_KEY[key]

    /**
     * 从 JPEG 字节里取出 APP1 的 EXIF 并解析成 TIFF 元数据；没有 EXIF 段返回 null。
     *
     * 测试要用同一条读路径做回读断言，所以是 internal 而不是 private——用别的方式读回来
     * 再比对，比的就不是同一套归一化结果了。
     */
    internal fun exifMetadataOf(jpeg: ByteArray): TiffImageMetadata? =
        exifSegmentOf(jpeg)?.let { tiffMetadataOf(it) }

    /**
     * 从 JPEG 段表里取 APP1 的 EXIF 载荷（跳过 `Exif\0\0` 六字节头）。
     *
     * 自己扫段是为了不碰 `JpegImageMetadata`：它的方法签名带 `java.awt.*`，Android 上加载有风险。
     * 段表走到 SOS 就停——再往后是压缩后的图像数据，不是段。
     */
    private fun exifSegmentOf(jpeg: ByteArray): ByteArray? {
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos].toInt() and 0xFF != MARKER_PREFIX) return null
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == MARKER_SOI || marker == MARKER_EOI || marker == MARKER_SOS) return null
            if (marker == MARKER_TEM || marker in MARKER_RST_FIRST..MARKER_RST_LAST) {
                pos += 2
                continue
            }
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (length < 2 || pos + 2 + length > jpeg.size) return null
            if (marker == MARKER_APP1) {
                val payload = pos + 4
                val payloadEnd = pos + 2 + length
                val hasExifHeader = payloadEnd - payload >= EXIF_HEADER.size &&
                    EXIF_HEADER.indices.all { jpeg[payload + it] == EXIF_HEADER[it] }
                if (hasExifHeader) return jpeg.copyOfRange(payload + EXIF_HEADER.size, payloadEnd)
            }
            pos += 2 + length
        }
        return null
    }

    private fun tiffMetadataOf(tiff: ByteArray): TiffImageMetadata {
        val contents = TiffReader(false)
            .readDirectories(ByteSource.array(tiff), true, FormatCompliance.getDefault())
        return TiffImageMetadata(contents)
    }

    /** 纯函数：把已解析的 TIFF 元数据映射成领域模型，便于单测直接喂样本。 */
    fun readFrom(tiff: TiffImageMetadata, info: SourceInfo): MetadataSet {
        // 不用 allFields：TiffImageMetadata(TiffContents) 那条构造路径不填它，
        // contents.directories 才是解析器实打实填过的。
        val all = tiff.contents.directories.flatMap { it.directoryEntries }
        val fields = HashMap<Long, TiffField>(all.size)
        all.forEach { field ->
            fields.putIfAbsent(location(field.tag, field.directoryType), field)
        }
        val entries = LinkedHashMap<TagKey, TagValue>()
        REFS.forEach { ref ->
            val field = if (ref.dir != null) {
                fields[location(ref.tag, ref.dir)]
            } else {
                findByTag(fields, ref.tag)
            } ?: return@forEach
            valueOf(field)?.let { entries.putIfAbsent(ref.key, coerce(ref.key, it)) }
        }
        return MetadataSet(info, entries)
    }

    /** 目录未知时的兜底：取同 tag 的第一个非 IFD1（缩略图）字段。 */
    private fun findByTag(fields: Map<Long, TiffField>, tag: Int): TiffField? =
        fields.values.firstOrNull { it.tag == tag && it.directoryType != IFD1 }
            ?: fields.values.firstOrNull { it.tag == tag }

    private fun location(tag: Int, directoryType: Int): Long =
        (tag.toLong() shl 32) or (directoryType.toLong() and 0xFFFF_FFFFL)

    private fun valueOf(field: TiffField): TagValue? = runCatching {
        when (val raw = field.value) {
            null -> null
            is String -> text(raw)
            is Byte -> TagValue.IntValue(raw.toLong())
            is Short -> TagValue.IntValue(raw.toLong())
            is Int -> TagValue.IntValue(raw.toLong())
            is Long -> TagValue.IntValue(raw)
            is Float -> TagValue.DecimalValue(raw.toDouble())
            is Double -> TagValue.DecimalValue(raw)
            is RationalNumber -> TagValue.RationalValue(raw.toRational())
            is ByteArray -> bytes(raw)
            is ShortArray -> TagValue.IntList(raw.map { it.toLong() })
            is IntArray -> TagValue.IntList(raw.map { it.toLong() })
            is LongArray -> TagValue.IntList(raw.toList())
            is Array<*> -> arrayValue(raw)
            else -> null
        }
    }.getOrNull()

    private fun arrayValue(raw: Array<*>): TagValue? {
        val rationals = raw.mapNotNull { it as? RationalNumber }
        if (rationals.size == raw.size && rationals.isNotEmpty()) {
            return TagValue.RationalList(rationals.map { it.toRational() })
        }
        val strings = raw.mapNotNull { it as? String }.map { it.trimEnd('\u0000', ' ') }
        if (strings.size == raw.size && strings.isNotEmpty()) {
            return if (strings.size == 1) TagValue.Text(strings.single()) else TagValue.TextList(strings)
        }
        return null
    }

    private fun RationalNumber.toRational(): Rational =
        Rational(numerator, divisor)

    /** ASCII / XP* 文本：去掉尾随 NUL 与空格，空串视为无值。 */
    private fun text(raw: String): TagValue? {
        val s = raw.trimEnd('\u0000', ' ').trim()
        return s.takeIf { it.isNotEmpty() }?.let(TagValue::Text)
    }

    /**
     * 二进制：全部可打印 ASCII 时按文本处理（ExifVersion 的 `0232` 与 ExifInterface 输出一致），
     * 否则退回十六进制（ComponentsConfiguration、GPSProcessingMethod 等）。
     */
    private fun bytes(raw: ByteArray): TagValue? {
        if (raw.isEmpty()) return null
        if (raw.all { it in 0x20..0x7E }) {
            return text(String(raw, Charsets.US_ASCII))
        }
        return TagValue.Binary(raw.joinToString("") { "%02x".format(it) }, raw.size)
    }

    /**
     * 按 [FieldCatalog] 声明的类型校正值形态：commons-imaging 的自然形态与目录声明并不总一致
     * （例如 ISO 在目录里是 INT_LIST、库里是单个 Short）。
     */
    private fun coerce(key: TagKey, value: TagValue): TagValue = when (FieldCatalog.spec(key)?.type) {
        ValueType.INT -> when (value) {
            is TagValue.IntList -> value.values.singleOrNull()?.let(TagValue::IntValue) ?: value
            is TagValue.RationalValue -> TagValue.IntValue(value.value.asDouble.toLong())
            else -> value
        }

        ValueType.INT_LIST -> when (value) {
            is TagValue.IntValue -> TagValue.IntList(listOf(value.value))
            is TagValue.RationalList -> TagValue.IntList(value.values.map { it.asDouble.toLong() })
            else -> value
        }

        ValueType.RATIONAL -> when (value) {
            is TagValue.IntValue -> TagValue.RationalValue(Rational(value.value, 1L))
            is TagValue.RationalList -> value.values.firstOrNull()?.let(TagValue::RationalValue) ?: value
            else -> value
        }

        ValueType.RATIONAL_LIST -> when (value) {
            is TagValue.RationalValue -> TagValue.RationalList(listOf(value.value))
            else -> value
        }

        ValueType.DECIMAL -> when (value) {
            is TagValue.RationalValue -> TagValue.DecimalValue(value.value.asDouble)
            is TagValue.IntValue -> TagValue.DecimalValue(value.value.toDouble())
            else -> value
        }

        ValueType.DATETIME -> if (value is TagValue.Text) {
            ExifValueCodec.parseDateTime(value.value)?.let { TagValue.Timestamp(it) } ?: value
        } else {
            value
        }

        ValueType.DATE -> if (value is TagValue.Text) {
            ExifValueCodec.parseDate(value.value)?.let { TagValue.DateValue(it) } ?: value
        } else {
            value
        }

        ValueType.TIME -> if (value is TagValue.Text) {
            ExifValueCodec.parseTime(value.value)?.let { TagValue.TimeValue(it) } ?: value
        } else {
            value
        }

        else -> value
    }

    private data class FieldRef(val tag: Int, val dir: Int?, val key: TagKey)

    companion object {

        const val ID: String = "imaging"

        /** 缩略图 IFD 的 directoryType。 */
        private const val IFD1: Int = 1

        /** 只接 TIFF 容器；JPEG/PNG/WebP 由 ExifInterface / metadata-extractor 负责。 */
        val SUPPORTED: Set<ImageFormatHint> = setOf(ImageFormatHint.TIFF, ImageFormatHint.RAW)

        /**
         * 可写格式（docs/07 T2.6）。TIFF 走 `TiffImageWriterLossless` 重排 IFD，
         * JPEG 走 `ExifRewriter` 只换 APP1 段——两条路都不解码像素。
         *
         * 不声明 RAW：容器同为 TIFF，但厂商私有 IFD 没验证过，不拿用户原片冒险。
         */
        val WRITABLE: Set<ImageFormatHint> = setOf(ImageFormatHint.TIFF, ImageFormatHint.JPEG)

        /** 重写后的字节数预留：改元数据只会让文件变大一点，不是翻倍。 */
        const val HEADROOM_BYTES: Int = 64 * 1024

        /** JPEG 段表标记（ITU T.81）。 */
        const val MARKER_PREFIX: Int = 0xFF
        const val MARKER_SOI: Int = 0xD8
        const val MARKER_EOI: Int = 0xD9
        const val MARKER_SOS: Int = 0xDA
        const val MARKER_APP1: Int = 0xE1
        const val MARKER_TEM: Int = 0x01
        const val MARKER_RST_FIRST: Int = 0xD0
        const val MARKER_RST_LAST: Int = 0xD7

        /** APP1 的 EXIF 头：`Exif` 加两个 0 字节。 */
        val EXIF_HEADER: ByteArray = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

        /**
         * 目录键 → commons-imaging 常量。常量自带 (tag, directoryType)，运行期据此定位字段，
         * 不依赖库里的名字（例如 34855 库内叫 `PhotographicSensitivity`，目录键叫 `ISOSpeedRatings`）。
         */
        private val MAPPING: List<Pair<TagInfo, String>> = listOf(
        TiffTagConstants.TIFF_TAG_IMAGE_WIDTH to "EXIF:ImageWidth",
        TiffTagConstants.TIFF_TAG_IMAGE_LENGTH to "EXIF:ImageLength",
        TiffTagConstants.TIFF_TAG_BITS_PER_SAMPLE to "EXIF:BitsPerSample",
        TiffTagConstants.TIFF_TAG_COMPRESSION to "EXIF:Compression",
        TiffTagConstants.TIFF_TAG_PHOTOMETRIC_INTERPRETATION to "EXIF:PhotometricInterpretation",
        TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION to "EXIF:ImageDescription",
        TiffTagConstants.TIFF_TAG_MAKE to "EXIF:Make",
        TiffTagConstants.TIFF_TAG_MODEL to "EXIF:Model",
        TiffTagConstants.TIFF_TAG_ORIENTATION to "EXIF:Orientation",
        TiffTagConstants.TIFF_TAG_SAMPLES_PER_PIXEL to "EXIF:SamplesPerPixel",
        TiffTagConstants.TIFF_TAG_XRESOLUTION to "EXIF:XResolution",
        TiffTagConstants.TIFF_TAG_YRESOLUTION to "EXIF:YResolution",
        TiffTagConstants.TIFF_TAG_RESOLUTION_UNIT to "EXIF:ResolutionUnit",
        ExifTagConstants.EXIF_TAG_SOFTWARE to "EXIF:Software",
        TiffTagConstants.TIFF_TAG_DATE_TIME to "EXIF:DateTime",
        TiffTagConstants.TIFF_TAG_ARTIST to "EXIF:Artist",
        TiffTagConstants.TIFF_TAG_HOST_COMPUTER to "EXIF:HostComputer",
        TiffTagConstants.TIFF_TAG_COPYRIGHT to "EXIF:Copyright",
        TiffTagConstants.TIFF_TAG_WHITE_POINT to "EXIF:WhitePoint",
        TiffTagConstants.TIFF_TAG_PRIMARY_CHROMATICITIES to "EXIF:PrimaryChromaticities",
        TiffTagConstants.TIFF_TAG_YCBCR_COEFFICIENTS to "EXIF:YCbCrCoefficients",
        TiffTagConstants.TIFF_TAG_YCBCR_POSITIONING to "EXIF:YCbCrPositioning",
        TiffTagConstants.TIFF_TAG_REFERENCE_BLACK_WHITE to "EXIF:ReferenceBlackWhite",
        MicrosoftTagConstants.EXIF_TAG_XPTITLE to "EXIF:XPTitle",
        MicrosoftTagConstants.EXIF_TAG_XPCOMMENT to "EXIF:XPComment",
        MicrosoftTagConstants.EXIF_TAG_XPAUTHOR to "EXIF:XPAuthor",
        MicrosoftTagConstants.EXIF_TAG_XPKEYWORDS to "EXIF:XPKeywords",
        MicrosoftTagConstants.EXIF_TAG_XPSUBJECT to "EXIF:XPSubject",
        MicrosoftTagConstants.EXIF_TAG_RATING to "EXIF:Rating",
        TiffTagConstants.TIFF_TAG_DOCUMENT_NAME to "EXIF:DocumentName",
        ExifTagConstants.EXIF_TAG_EXPOSURE_TIME to "EXIF:ExposureTime",
        ExifTagConstants.EXIF_TAG_FNUMBER to "EXIF:FNumber",
        ExifTagConstants.EXIF_TAG_EXPOSURE_PROGRAM to "EXIF:ExposureProgram",
        ExifTagConstants.EXIF_TAG_ISO to "EXIF:ISOSpeedRatings",
        ExifTagConstants.EXIF_TAG_EXIF_VERSION to "EXIF:ExifVersion",
        ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL to "EXIF:DateTimeOriginal",
        ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED to "EXIF:DateTimeDigitized",
        ExifTagConstants.EXIF_TAG_COMPONENTS_CONFIGURATION to "EXIF:ComponentsConfiguration",
        ExifTagConstants.EXIF_TAG_COMPRESSED_BITS_PER_PIXEL to "EXIF:CompressedBitsPerPixel",
        ExifTagConstants.EXIF_TAG_SHUTTER_SPEED_VALUE to "EXIF:ShutterSpeedValue",
        ExifTagConstants.EXIF_TAG_APERTURE_VALUE to "EXIF:ApertureValue",
        ExifTagConstants.EXIF_TAG_BRIGHTNESS_VALUE to "EXIF:BrightnessValue",
        ExifTagConstants.EXIF_TAG_EXPOSURE_COMPENSATION to "EXIF:ExposureBiasValue",
        ExifTagConstants.EXIF_TAG_MAX_APERTURE_VALUE to "EXIF:MaxApertureValue",
        ExifTagConstants.EXIF_TAG_SUBJECT_DISTANCE to "EXIF:SubjectDistance",
        ExifTagConstants.EXIF_TAG_METERING_MODE to "EXIF:MeteringMode",
        ExifTagConstants.EXIF_TAG_LIGHT_SOURCE to "EXIF:LightSource",
        ExifTagConstants.EXIF_TAG_FLASH to "EXIF:Flash",
        ExifTagConstants.EXIF_TAG_FOCAL_LENGTH to "EXIF:FocalLength",
        ExifTagConstants.EXIF_TAG_SUBJECT_AREA to "EXIF:SubjectArea",
        ExifTagConstants.EXIF_TAG_MAKER_NOTE to "EXIF:MakerNote",
        ExifTagConstants.EXIF_TAG_USER_COMMENT to "EXIF:UserComment",
        ExifTagConstants.EXIF_TAG_SUB_SEC_TIME to "EXIF:SubSecTime",
        ExifTagConstants.EXIF_TAG_SUB_SEC_TIME_ORIGINAL to "EXIF:SubSecTimeOriginal",
        ExifTagConstants.EXIF_TAG_SUB_SEC_TIME_DIGITIZED to "EXIF:SubSecTimeDigitized",
        ExifTagConstants.EXIF_TAG_FLASHPIX_VERSION to "EXIF:FlashpixVersion",
        ExifTagConstants.EXIF_TAG_RELATED_SOUND_FILE to "EXIF:RelatedSoundFile",
        TiffEpTagConstants.EXIF_TAG_FLASH_ENERGY to "EXIF:FlashEnergy",
        ExifTagConstants.EXIF_TAG_FOCAL_PLANE_XRESOLUTION_EXIF_IFD to "EXIF:FocalPlaneXResolution",
        ExifTagConstants.EXIF_TAG_FOCAL_PLANE_YRESOLUTION_EXIF_IFD to "EXIF:FocalPlaneYResolution",
        TiffEpTagConstants.EXIF_TAG_FOCAL_PLANE_RESOLUTION_UNIT to "EXIF:FocalPlaneResolutionUnit",
        ExifTagConstants.EXIF_TAG_SUBJECT_LOCATION to "EXIF:SubjectLocation",
        TiffEpTagConstants.EXIF_TAG_EXPOSURE_INDEX to "EXIF:ExposureIndex",
        TiffEpTagConstants.EXIF_TAG_SENSING_METHOD to "EXIF:SensingMethod",
        ExifTagConstants.EXIF_TAG_FILE_SOURCE to "EXIF:FileSource",
        ExifTagConstants.EXIF_TAG_SCENE_TYPE to "EXIF:SceneType",
        ExifTagConstants.EXIF_TAG_CFAPATTERN to "EXIF:CFAPattern",
        ExifTagConstants.EXIF_TAG_CUSTOM_RENDERED to "EXIF:CustomRendered",
        ExifTagConstants.EXIF_TAG_EXPOSURE_MODE to "EXIF:ExposureMode",
        ExifTagConstants.EXIF_TAG_WHITE_BALANCE_1 to "EXIF:WhiteBalance",
        ExifTagConstants.EXIF_TAG_DIGITAL_ZOOM_RATIO to "EXIF:DigitalZoomRatio",
        ExifTagConstants.EXIF_TAG_FOCAL_LENGTH_IN_35MM_FORMAT to "EXIF:FocalLengthIn35mmFilm",
        ExifTagConstants.EXIF_TAG_SCENE_CAPTURE_TYPE to "EXIF:SceneCaptureType",
        ExifTagConstants.EXIF_TAG_GAIN_CONTROL to "EXIF:GainControl",
        ExifTagConstants.EXIF_TAG_CONTRAST_1 to "EXIF:Contrast",
        ExifTagConstants.EXIF_TAG_SATURATION_1 to "EXIF:Saturation",
        ExifTagConstants.EXIF_TAG_SHARPNESS_1 to "EXIF:Sharpness",
        ExifTagConstants.EXIF_TAG_DEVICE_SETTING_DESCRIPTION to "EXIF:DeviceSettingDescription",
        ExifTagConstants.EXIF_TAG_SUBJECT_DISTANCE_RANGE to "EXIF:SubjectDistanceRange",
        ExifTagConstants.EXIF_TAG_IMAGE_UNIQUE_ID to "EXIF:ImageUniqueID",
        ExifTagConstants.EXIF_TAG_CAMERA_OWNER_NAME to "EXIF:CameraOwnerName",
        ExifTagConstants.EXIF_TAG_BODY_SERIAL_NUMBER to "EXIF:BodySerialNumber",
        ExifTagConstants.EXIF_TAG_LENS_SPECIFICATION to "EXIF:LensSpecification",
        ExifTagConstants.EXIF_TAG_LENS_MAKE to "EXIF:LensMake",
        ExifTagConstants.EXIF_TAG_LENS_MODEL to "EXIF:LensModel",
        ExifTagConstants.EXIF_TAG_LENS_SERIAL_NUMBER to "EXIF:LensSerialNumber",
        ExifTagConstants.EXIF_TAG_GAMMA to "EXIF:Gamma",
        GpsTagConstants.GPS_TAG_GPS_VERSION_ID to "GPS:GPSVersionID",
        GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF to "GPS:GPSLatitudeRef",
        GpsTagConstants.GPS_TAG_GPS_LATITUDE to "GPS:GPSLatitude",
        GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF to "GPS:GPSLongitudeRef",
        GpsTagConstants.GPS_TAG_GPS_LONGITUDE to "GPS:GPSLongitude",
        GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF to "GPS:GPSAltitudeRef",
        GpsTagConstants.GPS_TAG_GPS_ALTITUDE to "GPS:GPSAltitude",
        GpsTagConstants.GPS_TAG_GPS_TIME_STAMP to "GPS:GPSTimeStamp",
        GpsTagConstants.GPS_TAG_GPS_DATE_STAMP to "GPS:GPSDateStamp",
        GpsTagConstants.GPS_TAG_GPS_SATELLITES to "GPS:GPSSatellites",
        GpsTagConstants.GPS_TAG_GPS_STATUS to "GPS:GPSStatus",
        GpsTagConstants.GPS_TAG_GPS_MEASURE_MODE to "GPS:GPSMeasureMode",
        GpsTagConstants.GPS_TAG_GPS_DOP to "GPS:GPSDOP",
        GpsTagConstants.GPS_TAG_GPS_SPEED_REF to "GPS:GPSSpeedRef",
        GpsTagConstants.GPS_TAG_GPS_SPEED to "GPS:GPSSpeed",
        GpsTagConstants.GPS_TAG_GPS_TRACK_REF to "GPS:GPSTrackRef",
        GpsTagConstants.GPS_TAG_GPS_TRACK to "GPS:GPSTrack",
        GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION_REF to "GPS:GPSImgDirectionRef",
        GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION to "GPS:GPSImgDirection",
        GpsTagConstants.GPS_TAG_GPS_MAP_DATUM to "GPS:GPSMapDatum",
        GpsTagConstants.GPS_TAG_GPS_DEST_LATITUDE_REF to "GPS:GPSDestLatitudeRef",
        GpsTagConstants.GPS_TAG_GPS_DEST_LATITUDE to "GPS:GPSDestLatitude",
        GpsTagConstants.GPS_TAG_GPS_DEST_LONGITUDE_REF to "GPS:GPSDestLongitudeRef",
        GpsTagConstants.GPS_TAG_GPS_DEST_LONGITUDE to "GPS:GPSDestLongitude",
        GpsTagConstants.GPS_TAG_GPS_DEST_BEARING_REF to "GPS:GPSDestBearingRef",
        GpsTagConstants.GPS_TAG_GPS_DEST_BEARING to "GPS:GPSDestBearing",
        GpsTagConstants.GPS_TAG_GPS_DEST_DISTANCE_REF to "GPS:GPSDestDistanceRef",
        GpsTagConstants.GPS_TAG_GPS_DEST_DISTANCE to "GPS:GPSDestDistance",
        GpsTagConstants.GPS_TAG_GPS_PROCESSING_METHOD to "GPS:GPSProcessingMethod",
        GpsTagConstants.GPS_TAG_GPS_AREA_INFORMATION to "GPS:GPSAreaInformation",
        GpsTagConstants.GPS_TAG_GPS_DIFFERENTIAL to "GPS:GPSDifferential",
        GpsTagConstants.GPS_TAG_GPS_HOR_POSITIONING_ERROR to "GPS:GPSHPositioningError",
        ExifTagConstants.EXIF_TAG_INTEROPERABILITY_INDEX to "EXIF:InteroperabilityIndex",
        ExifTagConstants.EXIF_TAG_INTEROPERABILITY_VERSION to "EXIF:InteroperabilityVersion",
        )

        /** 库里没有常量、但 EXIF 规范 tag 号确定的手写字段 (tag, directoryType, 目录键)。 */
        private val MANUAL: List<Triple<Int, Int, String>> = listOf(
        Triple(34864, -2, "EXIF:SensitivityType"),
        Triple(34865, -2, "EXIF:RecommendedExposureIndex"),
        Triple(36880, -2, "EXIF:OffsetTime"),
        Triple(36881, -2, "EXIF:OffsetTimeOriginal"),
        Triple(36882, -2, "EXIF:OffsetTimeDigitized"),
        Triple(40961, -2, "EXIF:ColorSpace"),
        Triple(40962, -2, "EXIF:PixelXDimension"),
        Triple(40963, -2, "EXIF:PixelYDimension"),
        )

        /**
         * 少数常量在 1.0.0-alpha6 里 `directoryType` 为 null（TiffEp 的 FlashEnergy/
         * FocalPlaneResolutionUnit/ExposureIndex/SensingMethod、Exif 的 DeviceSettingDescription）。
         * 按 EXIF 规范它们都位于 Exif 子 IFD (-2)，在此显式补上，避免 class init 时空指针。
         */
        private val DIR_OVERRIDE: Map<Int, Int> = mapOf(
            37387 to -2, // FlashEnergy
            37392 to -2, // FocalPlaneResolutionUnit
            37397 to -2, // ExposureIndex
            37399 to -2, // SensingMethod
            41995 to -2, // DeviceSettingDescription
        )

        private val REFS: List<FieldRef> = buildList {
            MAPPING.forEach { (info, key) ->
                add(FieldRef(info.tag, info.directoryType?.directoryType ?: DIR_OVERRIDE[info.tag], TagKey.of(key)))
            }
            MANUAL.forEach { (tag, dir, key) -> add(FieldRef(tag, dir, TagKey.of(key))) }
        }

        /**
         * 反向索引：领域键 → 标签常量，写侧复用（保证「读得出来的字段就是写得回去的字段」）。
         *
         * 手写字段（[MANUAL]）没有常量，不进这张表——写侧遇到它们会记入 dropped 而不是猜 tag 号。
         */
        private val TAG_INFO_BY_KEY: Map<TagKey, TagInfo> =
            MAPPING.associate { (info, key) -> TagKey.of(key) to info }
    }
}
