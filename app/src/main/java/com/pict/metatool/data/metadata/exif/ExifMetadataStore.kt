package com.pict.metatool.data.metadata.exif

import android.content.ContentResolver
import androidx.exifinterface.media.ExifInterface
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.metadata.MetadataStore
import com.pict.metatool.data.metadata.MetadataWriter
import com.pict.metatool.data.metadata.WriteResult
import com.pict.metatool.data.metadata.xmp.XmpParser
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.format.GpsCoordinate
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * EXIF / GPS / XMP 读写（docs/07 T1.4 读、T2.3 写；docs/02 §5）。
 *
 * 用 androidx ExifInterface：JPEG/PNG/WebP/HEIF/DNG 都能读，官方维护，且**不需要任何存储权限**
 * （走调用方给的流）。XMP 原文由 `TAG_XMP` 直接给出，交给 [XmpParser] 解析（不需要第二个库）。
 *
 * 注意：ExifInterface 1.4.x **没有** `getAttributes()`，只能按 `TAG_*` 常量逐个取，
 * 所以下面的 [TAGS] 就是「我们会读哪些标签」的唯一清单——新增字段时同步它和 FieldCatalog。
 *
 * 写入走 [writeTo]：目标全量元数据与文件现状求差，删掉目标里没有的键、覆盖其余键，
 * 最后 `saveAttributes()` 一次落盘。能读不代表能写（HEIF/TIFF 只读），所以读写各有一个
 * 能力判断——[supports] 管读、[canWrite] 管写。
 *
 * 失败一律走 [PictResult.Failure]，不抛异常。
 */
class ExifMetadataStore : MetadataStore, MetadataWriter {

    override val id: String = "exif"

    override fun supports(info: SourceInfo): Boolean = info.format in SUPPORTED

    /** 写入能力比读取窄：ExifInterface 能重写 JPEG/PNG/WebP 的元数据块，改不了 HEIF/TIFF。 */
    override fun canWrite(info: SourceInfo): Boolean = info.format in WRITABLE

    override suspend fun read(
        resolver: ContentResolver,
        source: ImageSource,
    ): PictResult<MetadataSet> = withContext(Dispatchers.IO) {
        try {
            val stream = resolver.openInputStream(source.uri)
                ?: return@withContext failureOf(PictError.IO_OPEN, "无法打开输入流：${source.uri}")
            // 文件字节要一并留给读取层：判断「库报出来的标签文件里到底有没有」必须回字节层看 IFD 目录
            // （R-19），没有字节就只能照单全收。代价是整份文件会读进内存——元数据编辑器本来就要读整份，
            // 这里只是把它留到判定用完为止。
            stream.use { input ->
                val bytes = input.readBytes()
                successOf(readFrom(ExifInterface(bytes.inputStream()), source.info, bytes))
            }
        } catch (e: IOException) {
            failureOf(PictError.IO_READ, e.message, e)
        } catch (e: SecurityException) {
            failureOf(PictError.IO_OPEN, "URI 授权已失效，请重新选择图片", e)
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
                "${source.info.format.label} 不支持原地写入，需要重编码（docs/02 §5）",
            )
        }
        try {
            val fd = resolver.openFileDescriptor(source.uri, "rw")
                ?: return@withContext failureOf(PictError.IO_OPEN, "无法以读写方式打开：${source.uri}")
            fd.use { successOf(writeTo(ExifInterface(it.fileDescriptor), target)) }
        } catch (e: IOException) {
            failureOf(PictError.META_WRITE, writeFailureDetail(e), e)
        } catch (e: SecurityException) {
            failureOf(PictError.STORAGE_READONLY, "这个来源只读，写不进去", e)
        }
    }

    /**
     * 把 [target] 落盘到已打开的文件。
     *
     * 差异计算交给 [planWrites]（纯数据、JVM 可测），这里只负责把计划喂给 ExifInterface
     * 再 `saveAttributes()`。**注意**：`ExifInterface.setAttribute` 内部用 `android.util.Pair`
     * 传值，JVM 单测里那个 Pair 是空实现、字段拿不到值——落盘这一步只能在设备上验证，
     * 见 androidTest 下的 ExifMetadataStoreWriteInstrumentedTest。
     *
     * IO 异常向上抛，由 [write] 统一映射成错误码。
     */
    fun writeTo(exif: ExifInterface, target: MetadataSet): WriteResult {
        // 先过段大小预算：超限时按「缩略图 → XMP → 非关键」丢，避免 saveAttributes 直接抛
        val fit = ExifSegmentBudget.fit(target)
        // 这里**故意**不传字节：写侧要的是库报出来的全部属性（含它自己补的兼容默认值），
        // 那些「文件里根本没有」的键才会落进 removals、被 setAttribute(tag, null) 清掉。
        // 读侧诚实之后，读出来的目标集不再含这些键，幽灵标签就在这里被拦下，不会再被写回文件（R-19）。
        val plan = planWrites(readFrom(exif, target.source).entries, fit.kept)
        plan.removals.forEach { key ->
            TAG_BY_KEY[key]?.let { exif.setAttribute(it, null) }
        }
        plan.assignments.forEach { (key, raw) ->
            TAG_BY_KEY[key]?.let { exif.setAttribute(it, raw) }
        }
        exif.saveAttributes()
        return WriteResult(
            writtenKeys = plan.assignments.keys,
            droppedKeys = plan.dropped + fit.dropped.keys,
        )
    }

    /**
     * 算出「删什么、写什么、什么写不了」，全程不碰文件——JVM 单测直接喂数据。
     *
     * 目标里没有的键从 [current] 里挑出来删除；目标里的每个值先查反向标签表再序列化，
     * 查不到标签或序列化不了（二进制块、多语言文本）就记入 [ExifWritePlan.dropped]，
     * 保留文件里的原值不动，而不是写一个编造的内容进去。
     */
    fun planWrites(current: Map<TagKey, TagValue>, target: MetadataSet): ExifWritePlan {
        val assignments = LinkedHashMap<TagKey, String>()
        val dropped = LinkedHashSet<TagKey>()

        target.entries.forEach { (key, value) ->
            val tag = TAG_BY_KEY[key]
            val raw = if (tag == null) null else ExifValueWriter.format(value)
            if (tag == null || raw == null) {
                dropped += key
            } else {
                assignments[key] = raw
            }
        }

        return ExifWritePlan(
            removals = current.keys - target.entries.keys,
            assignments = assignments,
            dropped = dropped,
        )
    }

    /** JPEG 的 APP1 段上限 64 KB，ExifInterface 超限时抛 IOException——把提示说清楚。 */
    private fun writeFailureDetail(e: IOException): String {
        val message = e.message.orEmpty()
        val tooLarge = message.contains("too large", ignoreCase = true) ||
            message.contains("too long", ignoreCase = true)
        return when {
            tooLarge -> "EXIF 段超过 64 KB 上限，请减少字段（丢弃策略见 T2.5）：$message"
            message.isNotEmpty() -> message
            else -> "写入失败"
        }
    }

    /**
     * 从已构造好的 [ExifInterface] 提取（抽出来便于 JVM 单测直接喂真实相机样张）。
     *
     * [raw] 是文件原始字节：给了才能用 [IfdTagIndex] 判断「库报出来的这个标签，文件里到底有没有」。
     * 不给就只能照单全收（证不了），所以生产路径一定要给——见 [read]，以及写入路径的相反用法见 [writeTo]。
     */
    fun readFrom(exif: ExifInterface, info: SourceInfo, raw: ByteArray? = null): MetadataSet =
        collectTags(exif, info, raw?.let { IfdTagIndex.of(info.format, it) })

    /**
     * 按 [TAGS] 收 EXIF/GPS 标量，再补 GPS 十进制坐标与海拔，最后解析 XMP 包（已存在的键不覆盖 EXIF）。
     *
     * [index] 为 null 表示**证不了**（没给字节 / 容器不支持 / 字节结构读不通），此时一律保留：
     * 宁可多显示一个字段，也不要把文件里真有的元数据藏起来。
     */
    private fun collectTags(exif: ExifInterface, info: SourceInfo, index: IfdTagIndex?): MetadataSet {
        val entries = LinkedHashMap<TagKey, TagValue>()

        TAGS.forEach { tag ->
            if (tag == ExifInterface.TAG_XMP) return@forEach
            val raw = exif.getAttribute(tag) ?: return@forEach
            // ExifInterface 会为了兼容性自己补默认值（androidx `addDefaultValuesForCompatibility`：
            // 文件里没有 LightSource 时它也返回 "0"），这些补出来的属性在文件里没有落点。
            // 判据只能是字节层的 IFD 目录（docs/09 R-19 第七轮）：`getAttributeRange` 的负偏移**不能**用——
            // 本工具写过的文件里 LightSource 物理存在（exiftool 可见），库报的区间**还是** [-1, 4]。
            // 不挡掉的话，编辑页会凭空显示「光源 0」，保存时又把这个源文件没有的标签写回去。
            if (index != null && index.provesAbsence(tag)) return@forEach
            // ExifInterface 在 PNG/WebP/HEIF 上会把缺失的 IFD0 尺寸读成 "0"；
            // 0 尺寸没有意义，丢掉它，容器读取器才能补上真实宽高
            if (tag in ZERO_SIZE_TAGS && raw.trim() == "0") return@forEach
            val key = ExifValueCodec.keyFor(tag)
            val type = ExifValueCodec.typeFor(key, raw)
            ExifValueCodec.parse(raw, type)?.let { entries[key] = it }
        }

        readGps(exif, entries)

        exif.getAttribute(ExifInterface.TAG_XMP)?.let { packet ->
            XmpParser.parse(packet).forEach { (key, value) -> entries.putIfAbsent(key, value) }
        }

        return MetadataSet(info, entries)
    }

    private fun readGps(exif: ExifInterface, entries: MutableMap<TagKey, TagValue>) {
        // getLatLong() 返回 null 表示没有 GPS；比 float[] 版本精度高，也不会把缺失读成 0
        val latLong = exif.getLatLong()
        if (latLong != null) {
            val latitude = latLong[0]
            val longitude = latLong[1]
            entries.putIfAbsent(
                LATITUDE_REF,
                TagValue.Text(GpsCoordinate.refFor(latitude, isLatitude = true)),
            )
            entries.putIfAbsent(LATITUDE, TagValue.RationalList(GpsCoordinate.decimalToDms(latitude)))
            entries.putIfAbsent(
                LONGITUDE_REF,
                TagValue.Text(GpsCoordinate.refFor(longitude, isLatitude = false)),
            )
            entries.putIfAbsent(LONGITUDE, TagValue.RationalList(GpsCoordinate.decimalToDms(longitude)))
        }

        val altitude = exif.getAltitude(Double.NaN)
        if (!altitude.isNaN()) {
            entries.putIfAbsent(ALTITUDE_REF, TagValue.IntValue(if (altitude < 0) 1 else 0))
            entries.putIfAbsent(
                ALTITUDE,
                TagValue.RationalValue(Rational((altitude * 1000).toLong(), 1000)),
            )
        }
    }

    companion object {
        /** ExifInterface 能读 EXIF 的容器格式（BMP/RAW 走别的读取器）。 */
        val SUPPORTED: Set<ImageFormatHint> = setOf(
            ImageFormatHint.JPEG,
            ImageFormatHint.PNG,
            ImageFormatHint.WEBP,
            ImageFormatHint.HEIF,
            ImageFormatHint.TIFF,
        )

        /**
         * ExifInterface 能原地重写的容器格式。
         *
         * HEIF 的元数据在 ISO-BMFF box 里，TIFF 的 IFD 布局也不受支持——两者只读不写，
         * 要改就得走 CommonsImaging 重编码路径（docs/02 §5）。
         */
        val WRITABLE: Set<ImageFormatHint> = setOf(
            ImageFormatHint.JPEG,
            ImageFormatHint.PNG,
            ImageFormatHint.WEBP,
        )

        /**
         * 缺失时要当作「没有」而不是「0」的标签。
         *
         * ExifInterface 对 PNG/WebP/HEIF 的 eXIf 块不写 IFD0 尺寸时返回的是字符串 "0"，
         * 而不是 null。若照原样写入，就会覆盖容器读取器给出的真实宽高，界面上显示成 0 × 0。
         */
        val ZERO_SIZE_TAGS: Set<String> = setOf(
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
        )

        /**
         * 读取清单：FieldCatalog 里 EXIF/GPS 段的标签 + 少量目录外的有用标签。
         * 刻意排除缩略图/JPEGInterchange/Strip/ORF/RW2 这类结构标签——它们不是用户要看的元数据。
         */
        val TAGS: List<String> = listOf(
            // IFD0
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_BITS_PER_SAMPLE,
            ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_SAMPLES_PER_PIXEL,
            ExifInterface.TAG_X_RESOLUTION,
            ExifInterface.TAG_Y_RESOLUTION,
            ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_WHITE_POINT,
            ExifInterface.TAG_PRIMARY_CHROMATICITIES,
            ExifInterface.TAG_Y_CB_CR_COEFFICIENTS,
            ExifInterface.TAG_Y_CB_CR_POSITIONING,
            ExifInterface.TAG_REFERENCE_BLACK_WHITE,
            // XP* 系列（XPTitle 等）1.4.2 未暴露常量，暂不读取；Windows 兼容字段后续按需补
            // Exif IFD
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_SENSITIVITY_TYPE,
            ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
            ExifInterface.TAG_EXIF_VERSION,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_COMPONENTS_CONFIGURATION,
            ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_SUBJECT_DISTANCE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_SUBJECT_AREA,
            ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_FLASHPIX_VERSION,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_RELATED_SOUND_FILE,
            ExifInterface.TAG_FLASH_ENERGY,
            ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE,
            ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
            ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
            ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
            ExifInterface.TAG_SUBJECT_LOCATION,
            ExifInterface.TAG_EXPOSURE_INDEX,
            ExifInterface.TAG_SENSING_METHOD,
            ExifInterface.TAG_FILE_SOURCE,
            ExifInterface.TAG_SCENE_TYPE,
            ExifInterface.TAG_CFA_PATTERN,
            ExifInterface.TAG_CUSTOM_RENDERED,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_GAIN_CONTROL,
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
            ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_GAMMA,
            // Interop IFD
            ExifInterface.TAG_INTEROPERABILITY_INDEX,
            // GPS
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
            // XMP 原文（交给 XmpParser）
            ExifInterface.TAG_XMP,
        )

        /**
         * 领域键 → ExifInterface 标签名，[TAGS] 的反向表（写入时用）。
         *
         * 只登记能写回标量的标签：XMP 原文由独立的包处理，缩略图尺寸是派生属性，都不参与写回。
         * 查不到的键（XMP 字段、目录外字段）由 [writeTo] 记入丢弃集合。
         */
        private val TAG_BY_KEY: Map<TagKey, String> = TAGS
            .filter { it != ExifInterface.TAG_XMP && !ExifValueCodec.isSkipped(it) }
            .associateBy { ExifValueCodec.keyFor(it) }

        private val LATITUDE = TagKey.of("GPS:GPSLatitude")
        private val LATITUDE_REF = TagKey.of("GPS:GPSLatitudeRef")
        private val LONGITUDE = TagKey.of("GPS:GPSLongitude")
        private val LONGITUDE_REF = TagKey.of("GPS:GPSLongitudeRef")
        private val ALTITUDE = TagKey.of("GPS:GPSAltitude")
        private val ALTITUDE_REF = TagKey.of("GPS:GPSAltitudeRef")
    }
}
