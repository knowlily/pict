package com.pict.metatool.data.metadata.exif

import android.content.ContentResolver
import androidx.exifinterface.media.ExifInterface
import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.data.metadata.MetadataStore
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
 * EXIF / GPS / XMP 读取（docs/07 T1.4，docs/02 §5）。
 *
 * 用 androidx ExifInterface：JPEG/PNG/WebP/HEIF/DNG 都能读，官方维护，且**不需要任何存储权限**
 * （走调用方给的流）。XMP 原文由 `TAG_XMP` 直接给出，交给 [XmpParser] 解析（不需要第二个库）。
 *
 * 注意：ExifInterface 1.4.x **没有** `getAttributes()`，只能按 `TAG_*` 常量逐个取，
 * 所以下面的 [TAGS] 就是「我们会读哪些标签」的唯一清单——新增字段时同步它和 FieldCatalog。
 *
 * 失败一律走 [PictResult.Failure]，不抛异常。
 */
class ExifMetadataStore : MetadataStore {

    override val id: String = "exif"

    override fun supports(info: SourceInfo): Boolean = info.format in SUPPORTED

    override suspend fun read(
        resolver: ContentResolver,
        source: ImageSource,
    ): PictResult<MetadataSet> = withContext(Dispatchers.IO) {
        try {
            val stream = resolver.openInputStream(source.uri)
                ?: return@withContext failureOf(PictError.IO_OPEN, "无法打开输入流：${source.uri}")
            stream.use { successOf(readFrom(ExifInterface(it), source.info)) }
        } catch (e: IOException) {
            failureOf(PictError.IO_READ, e.message, e)
        } catch (e: SecurityException) {
            failureOf(PictError.IO_OPEN, "URI 授权已失效，请重新选择图片", e)
        }
    }

    /**
     * 从已构造好的 [ExifInterface] 提取（抽出来便于 JVM 单测直接喂真实相机样张）。
     *
     * 顺序：先按 [TAGS] 收 EXIF/GPS 标量，再用 GPS 专用 API 补齐十进制坐标与海拔，
     * 最后解析 XMP 包（已存在的键不覆盖 EXIF，EXIF 更权威）。
     */
    fun readFrom(exif: ExifInterface, info: SourceInfo): MetadataSet {
        val entries = LinkedHashMap<TagKey, TagValue>()

        TAGS.forEach { tag ->
            if (tag == ExifInterface.TAG_XMP) return@forEach
            val raw = exif.getAttribute(tag) ?: return@forEach
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

        private val LATITUDE = TagKey.of("GPS:GPSLatitude")
        private val LATITUDE_REF = TagKey.of("GPS:GPSLatitudeRef")
        private val LONGITUDE = TagKey.of("GPS:GPSLongitude")
        private val LONGITUDE_REF = TagKey.of("GPS:GPSLongitudeRef")
        private val ALTITUDE = TagKey.of("GPS:GPSAltitude")
        private val ALTITUDE_REF = TagKey.of("GPS:GPSAltitudeRef")
    }
}
