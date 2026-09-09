package com.pict.metatool.ui.detail

import com.pict.metatool.domain.format.GpsCoordinate
import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagValue

/** 概览页一行（docs/06 §3.2：相机 / 镜头 / 拍摄时间 / 位置 / 尺寸）。 */
data class OverviewRow(val label: String, val value: String)

/** GPS 页要的三种坐标写法。 */
data class CoordinateDisplay(
    /** 十进制度，如 `31.2304°N 121.4737°E`。 */
    val decimal: String,
    /** 度分秒，如 `31°13'49.44"N 121°28'25.32"E`。 */
    val dms: String,
    /** 复制用，不带方向字母，如 `31.2304, 121.4737`。 */
    val raw: String,
)

/**
 * 概览页取值（纯函数，可 JVM 单测）。
 *
 * 规则：
 * - 每项按「EXIF 优先、XMP 兜底」的键序列取第一个可用值；
 * - **取不到就整行不显示**——概览是摘要，缺项留空只会拉长页面，
 *   完整清单在 EXIF / GPS / XMP 页；
 * - 一行都没有时返回空列表，由界面显示空状态。
 */
object OverviewBuilder {

    private val CAMERA_LABEL = "相机"
    private val LENS_LABEL = "镜头"
    private val TIME_LABEL = "拍摄时间"
    private val LOCATION_LABEL = "位置"
    private val SIZE_LABEL = "尺寸"

    fun build(set: MetadataSet): List<OverviewRow> = buildList {
        cameraRow(set)?.let(::add)
        lensRow(set)?.let(::add)
        timeRow(set)?.let(::add)
        locationRow(set)?.let(::add)
        sizeRow(set)?.let(::add)
    }

    /** 厂商 + 机型；机型已包含厂商前缀时不再重复（`Apple` + `Apple iPhone 16 Pro`）。 */
    private fun cameraRow(set: MetadataSet): OverviewRow? {
        val make = set.firstText("EXIF:Make", "XMP:tiff:Make")
        val model = set.firstText("EXIF:Model", "XMP:tiff:Model")
        val text = when {
            make == null -> model
            model == null -> make
            model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
        return text?.let { OverviewRow(CAMERA_LABEL, it) }
    }

    /** 镜头型号优先；没有就退回 `LensSpecification`（焦距 + 光圈）或焦距/光圈单项。 */
    private fun lensRow(set: MetadataSet): OverviewRow? {
        set.firstText("EXIF:LensModel", "XMP:exif:LensModel", "XMP:aux:Lens")
            ?.let { return OverviewRow(LENS_LABEL, it) }

        val spec = (set["EXIF:LensSpecification"] as? TagValue.RationalList)?.values.orEmpty()
        if (spec.size >= 2) {
            val focal = TagValueFormatter.focalLength(TagValue.RationalValue(spec[0]))
            val aperture = TagValueFormatter.aperture(TagValue.RationalValue(spec[1]))
            return OverviewRow(LENS_LABEL, "$focal $aperture")
        }

        val focal = set["EXIF:FocalLength"]?.let(TagValueFormatter::focalLength)
        val aperture = set["EXIF:FNumber"]?.let(TagValueFormatter::aperture)
        val text = listOfNotNull(focal, aperture).joinToString(" ")
        return text.ifBlank { null }?.let { OverviewRow(LENS_LABEL, it) }
    }

    /** 拍摄时间 → 数字化时间 → 修改时间；EXIF 优先，XMP 兜底。 */
    private fun timeRow(set: MetadataSet): OverviewRow? =
        set.firstValue(
            "EXIF:DateTimeOriginal",
            "XMP:exif:DateTimeOriginal",
            "EXIF:DateTimeDigitized",
            "EXIF:DateTime",
        )?.let { OverviewRow(TIME_LABEL, TagValueFormatter.format(it)) }

    /**
     * 经纬度的三种写法；EXIF 的 DMS + Ref 优先，XMP 的十进制度兜底。
     * 两者都要凑齐才返回（只有纬度画不出位置）。
     */
    fun coordinates(set: MetadataSet): CoordinateDisplay? {
        val (lat, lon) = decimalPair(set) ?: return null
        val rawLat = "%.4f".format(lat)
        val rawLon = "%.4f".format(lon)
        return CoordinateDisplay(
            decimal = "${GpsCoordinate.decimalLabel(lat, true)} ${GpsCoordinate.decimalLabel(lon, false)}",
            dms = "${GpsCoordinate.format(lat, true)} ${GpsCoordinate.format(lon, false)}",
            raw = "$rawLat, $rawLon",
        )
    }

    private fun decimalPair(set: MetadataSet): Pair<Double, Double>? {
        val latDms = (set["GPS:GPSLatitude"] as? TagValue.RationalList)?.values
        val lonDms = (set["GPS:GPSLongitude"] as? TagValue.RationalList)?.values
        if (latDms != null && lonDms != null) {
            return GpsCoordinate.applyRef(
                GpsCoordinate.dmsToDecimal(latDms),
                set.firstText("GPS:GPSLatitudeRef"),
            ) to GpsCoordinate.applyRef(
                GpsCoordinate.dmsToDecimal(lonDms),
                set.firstText("GPS:GPSLongitudeRef"),
            )
        }

        val lat = (set["XMP:exif:GPSLatitude"] as? TagValue.DecimalValue)?.value
        val lon = (set["XMP:exif:GPSLongitude"] as? TagValue.DecimalValue)?.value
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun locationRow(set: MetadataSet): OverviewRow? =
        coordinates(set)?.let { OverviewRow(LOCATION_LABEL, it.decimal) }

    /** 宽 × 高；ImageWidth/ImageLength 优先，PixelXDimension/PixelYDimension 兜底。 */
    private fun sizeRow(set: MetadataSet): OverviewRow? {
        val width = set.firstLong("EXIF:ImageWidth", "EXIF:PixelXDimension")
        val height = set.firstLong("EXIF:ImageLength", "EXIF:PixelYDimension")
        return if (width != null && height != null) {
            OverviewRow(SIZE_LABEL, "$width × $height")
        } else {
            null
        }
    }

    // ---------- 取值工具 ----------

    private fun MetadataSet.firstValue(vararg keys: String): TagValue? =
        keys.firstNotNullOfOrNull { key -> this[key]?.takeIf(::isDisplayable) }

    private fun MetadataSet.firstText(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key].asText()?.ifBlank { null } }

    private fun MetadataSet.firstLong(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            when (val v = this[key]) {
                is TagValue.IntValue -> v.value
                is TagValue.DecimalValue -> v.value.toLong()
                else -> null
            }
        }

    /** 空文本、空数组、零字节二进制都当作「没有值」。 */
    private fun isDisplayable(value: TagValue): Boolean = when (value) {
        is TagValue.Text -> value.value.isNotBlank()
        is TagValue.TextList -> value.values.any { it.isNotBlank() }
        is TagValue.LangAlt -> value.values.values.any { it.isNotBlank() }
        is TagValue.Binary -> value.byteCount > 0
        is TagValue.IntList -> value.values.isNotEmpty()
        is TagValue.DecimalList -> value.values.isNotEmpty()
        is TagValue.RationalList -> value.values.isNotEmpty()
        else -> true
    }

    private fun TagValue?.asText(): String? = when (this) {
        is TagValue.Text -> value
        is TagValue.LangAlt -> values["x-default"] ?: values.values.firstOrNull()
        is TagValue.UriValue -> value
        else -> null
    }
}
