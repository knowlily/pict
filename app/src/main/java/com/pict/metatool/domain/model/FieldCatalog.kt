package com.pict.metatool.domain.model

/**
 * 字段目录（docs/03 §3、附录 A、docs/07 T1.2）。
 *
 * 这里是**唯一事实源**：UI 分组、可编辑性、值类型、随机预设可用字段全部由它派生。
 * 新增字段 = 在 [all] 里加一行 + 在附录 A 同步；[FieldCatalogTest] 会守住重复键与完整性。
 *
 * 说明：`XMP:crs:*`（Camera Raw 设置）为通配命名空间，不逐条枚举，读取时归入 XMP 组展示。
 */
data class FieldSpec(
    val key: TagKey,
    val label: String,
    val type: ValueType,
    val writability: Writability,
    val group: FieldGroup,
    val note: String? = null,
    /** 隐私字段：随机填充/预设默认不写，需显式开启（附录 A.7）。 */
    val privacySensitive: Boolean = false,
    /** 枚举字段的可选值（仅用于 UI 下拉与校验）。 */
    val options: List<Pair<Long, String>> = emptyList(),
) {
    val canEdit: Boolean get() = writability.canEdit
    val isStructural: Boolean get() = writability == Writability.READ_ONLY && group == FieldGroup.FILE
}

object FieldCatalog {

    /** 全部字段，顺序 = 附录 A 的排列顺序（IFD0 → Exif IFD → GPS → Interop → XMP → IPTC）。 */
    val all: List<FieldSpec> = listOf(
        // ---------- A.1 IFD0 / TIFF 主标签 ----------
        f("EXIF:ImageWidth", "图像宽度", ValueType.INT, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:ImageLength", "图像高度", ValueType.INT, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:BitsPerSample", "每样本位数", ValueType.INT_LIST, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:Compression", "压缩方式", ValueType.INT, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:PhotometricInterpretation", "光度解释", ValueType.INT, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:ImageDescription", "图像描述", ValueType.TEXT, Writability.WRITABLE, FieldGroup.BASIC),
        f("EXIF:Make", "制造商", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA, "如 Apple"),
        f("EXIF:Model", "机型", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA, "如 iPhone 16 Pro"),
        f("EXIF:Orientation", "方向", ValueType.INT, Writability.CONDITIONAL, FieldGroup.FILE, "转码后须为 1；仅改元数据时不动"),
        f("EXIF:SamplesPerPixel", "样本数", ValueType.INT, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:XResolution", "X 分辨率", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.FILE, "默认 72"),
        f("EXIF:YResolution", "Y 分辨率", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.FILE, "默认 72"),
        f("EXIF:ResolutionUnit", "分辨率单位", ValueType.INT, Writability.WRITABLE, FieldGroup.FILE, "2=英寸，3=厘米",
            options = listOf(2L to "英寸", 3L to "厘米")),
        f("EXIF:Software", "软件", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA, "如 Adobe Lightroom 13.2"),
        f("EXIF:DateTime", "修改时间", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME, "需与时间组同步"),
        f("EXIF:Artist", "作者", ValueType.TEXT, Writability.WRITABLE, FieldGroup.BASIC),
        f("EXIF:HostComputer", "主机", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("EXIF:Copyright", "版权", ValueType.TEXT, Writability.WRITABLE, FieldGroup.BASIC),
        f("EXIF:WhitePoint", "白点", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.FILE, "少见"),
        f("EXIF:PrimaryChromaticities", "主色度", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.FILE, "少见"),
        f("EXIF:YCbCrCoefficients", "YCbCr 系数", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.FILE, "少见"),
        f("EXIF:YCbCrPositioning", "YCbCr 定位", ValueType.INT, Writability.WRITABLE, FieldGroup.FILE, "少见"),
        f("EXIF:ReferenceBlackWhite", "黑白参考", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.FILE, "少见"),
        f("EXIF:XPTitle", "标题（XP）", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.XMP, "UTF-16LE，Android 上慎用"),
        f("EXIF:XPComment", "注释（XP）", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.XMP, "同上"),
        f("EXIF:XPAuthor", "作者（XP）", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.XMP, "同上"),
        f("EXIF:XPKeywords", "关键词（XP）", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.XMP, "同上"),
        f("EXIF:XPSubject", "主题（XP）", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.XMP, "同上"),
        f("EXIF:Rating", "评分", ValueType.INT, Writability.WRITABLE, FieldGroup.BASIC, "Windows 常见"),
        f("EXIF:DocumentName", "文档名", ValueType.TEXT, Writability.WRITABLE, FieldGroup.BASIC),

        // ---------- A.2 Exif IFD ----------
        f("EXIF:ExposureTime", "曝光时间", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE, "如 1/250"),
        f("EXIF:FNumber", "光圈", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE, "如 1.78"),
        f("EXIF:ExposureProgram", "曝光程序", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "未定义", 1L to "手动", 2L to "程序自动", 3L to "光圈优先", 4L to "快门优先")),
        f("EXIF:ISOSpeedRatings", "ISO", ValueType.INT_LIST, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:SensitivityType", "感光度类型", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:RecommendedExposureIndex", "推荐曝光指数", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:ExifVersion", "EXIF 版本", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.FILE, "通常保持 0232"),
        f("EXIF:DateTimeOriginal", "拍摄时间", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME, "核心字段"),
        f("EXIF:DateTimeDigitized", "数字化时间", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME, "通常同 Original"),
        f("EXIF:OffsetTime", "时区偏移（修改）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.TIME, "如 +08:00"),
        f("EXIF:OffsetTimeOriginal", "时区偏移（拍摄）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.TIME, "如 +08:00"),
        f("EXIF:OffsetTimeDigitized", "时区偏移（数字化）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.TIME, "如 +08:00"),
        f("EXIF:ComponentsConfiguration", "分量配置", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.FILE, "保持默认"),
        f("EXIF:CompressedBitsPerPixel", "压缩位/像素", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.FILE, "少见"),
        f("EXIF:ShutterSpeedValue", "快门速度（APEX）", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE, "与曝光时间二选一"),
        f("EXIF:ApertureValue", "光圈（APEX）", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE, "与 FNumber 二选一"),
        f("EXIF:BrightnessValue", "亮度", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:ExposureBiasValue", "曝光补偿", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE, "如 -1/3"),
        f("EXIF:MaxApertureValue", "最大光圈", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:SubjectDistance", "被摄距离", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE, "米"),
        f("EXIF:MeteringMode", "测光模式", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "未知", 1L to "平均", 2L to "中央重点", 3L to "点测光", 5L to "模式测光", 6L to "局部")),
        f("EXIF:LightSource", "光源", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:Flash", "闪光灯", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE, "位域"),
        f("EXIF:FocalLength", "焦距", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE, "mm"),
        f("EXIF:SubjectArea", "被摄区域", ValueType.INT_LIST, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:MakerNote", "厂商注释", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.CAMERA, "默认保留原样，不解析不修改"),
        f("EXIF:UserComment", "用户注释", ValueType.BYTES, Writability.WRITABLE, FieldGroup.BASIC, "需带字符集前缀"),
        f("EXIF:SubSecTime", "亚秒（修改）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.TIME),
        f("EXIF:SubSecTimeOriginal", "亚秒（拍摄）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.TIME),
        f("EXIF:SubSecTimeDigitized", "亚秒（数字化）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.TIME),
        f("EXIF:FlashpixVersion", "FlashPix 版本", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.FILE, "保持 0100"),
        f("EXIF:ColorSpace", "色彩空间", ValueType.INT, Writability.CONDITIONAL, FieldGroup.FILE, "1=sRGB，65535=未校准",
            options = listOf(1L to "sRGB", 65535L to "未校准")),
        f("EXIF:PixelXDimension", "有效宽", ValueType.INT, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:PixelYDimension", "有效高", ValueType.INT, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:RelatedSoundFile", "关联音频", ValueType.TEXT, Writability.WRITABLE, FieldGroup.BASIC),
        f("EXIF:FlashEnergy", "闪光能量", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:SpatialFrequencyResponse", "空间频率响应", ValueType.TEXT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:FocalPlaneXResolution", "焦平面 X 分辨率", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:FocalPlaneYResolution", "焦平面 Y 分辨率", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:FocalPlaneResolutionUnit", "焦平面单位", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:SubjectLocation", "主体位置", ValueType.INT_LIST, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:ExposureIndex", "曝光指数", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:SensingMethod", "感光方式", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:FileSource", "文件来源", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.FILE),
        f("EXIF:SceneType", "场景类型", ValueType.BYTES, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:CFAPattern", "CFA 图案", ValueType.BYTES, Writability.READ_ONLY, FieldGroup.FILE, "结构字段"),
        f("EXIF:CustomRendered", "自定义渲染", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "常规", 1L to "自定义")),
        f("EXIF:ExposureMode", "曝光模式", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "自动", 1L to "手动", 2L to "包围曝光")),
        f("EXIF:WhiteBalance", "白平衡", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "自动", 1L to "手动")),
        f("EXIF:DigitalZoomRatio", "数码变焦", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:FocalLengthIn35mmFilm", "等效 35mm 焦距", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:SceneCaptureType", "场景拍摄类型", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "标准", 1L to "风景", 2L to "人像", 3L to "夜景")),
        f("EXIF:GainControl", "增益控制", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:Contrast", "对比度", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "标准", 1L to "柔和", 2L to "强烈")),
        f("EXIF:Saturation", "饱和度", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "标准", 1L to "低", 2L to "高")),
        f("EXIF:Sharpness", "锐度", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "标准", 1L to "柔和", 2L to "强烈")),
        f("EXIF:DeviceSettingDescription", "设备设置描述", ValueType.BYTES, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("EXIF:SubjectDistanceRange", "被摄距离范围", ValueType.INT, Writability.WRITABLE, FieldGroup.EXPOSURE,
            options = listOf(0L to "未知", 1L to "微距", 2L to "近景", 3L to "远景")),
        f("EXIF:ImageUniqueID", "图像唯一 ID", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA,
            "默认不写", privacySensitive = true),
        f("EXIF:CameraOwnerName", "相机所有者", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("EXIF:BodySerialNumber", "机身序列号", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA,
            "默认不写（隐私）", privacySensitive = true),
        f("EXIF:LensSpecification", "镜头规格", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.CAMERA),
        f("EXIF:LensMake", "镜头厂商", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("EXIF:LensModel", "镜头型号", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("EXIF:LensSerialNumber", "镜头序列号", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA,
            "默认不写", privacySensitive = true),
        f("EXIF:Gamma", "伽马", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.FILE),

        // ---------- A.3 GPS IFD ----------
        f("GPS:GPSVersionID", "GPS 版本", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.LOCATION, "通常 2.3.0.0"),
        f("GPS:GPSLatitudeRef", "纬度参考", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "N/S"),
        f("GPS:GPSLatitude", "纬度", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.LOCATION, "度/分/秒"),
        f("GPS:GPSLongitudeRef", "经度参考", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "E/W"),
        f("GPS:GPSLongitude", "经度", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.LOCATION, "度/分/秒"),
        f("GPS:GPSAltitudeRef", "海拔参考", ValueType.INT, Writability.WRITABLE, FieldGroup.LOCATION, "0=海平面以上"),
        f("GPS:GPSAltitude", "海拔", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION, "米"),
        f("GPS:GPSTimeStamp", "GPS 时间", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.LOCATION, "UTC 时:分:秒"),
        f("GPS:GPSDateStamp", "GPS 日期", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "YYYY:MM:DD"),
        f("GPS:GPSSatellites", "卫星", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSStatus", "状态", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "A/V"),
        f("GPS:GPSMeasureMode", "测量模式", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "2/3"),
        f("GPS:GPSDOP", "精度因子", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSSpeedRef", "速度单位", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "K/M/N"),
        f("GPS:GPSSpeed", "速度", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSTrackRef", "方向参考", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "T/M"),
        f("GPS:GPSTrack", "方向", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSImgDirectionRef", "图像方向参考", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "T/M"),
        f("GPS:GPSImgDirection", "图像方向", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSMapDatum", "大地基准", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION, "WGS-84"),
        f("GPS:GPSDestLatitudeRef", "目的地纬度参考", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDestLatitude", "目的地纬度", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDestLongitudeRef", "目的地经度参考", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDestLongitude", "目的地经度", ValueType.RATIONAL_LIST, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDestBearingRef", "目的地方位参考", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDestBearing", "目的地方位", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDestDistanceRef", "目的地距离单位", ValueType.TEXT, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDestDistance", "目的地距离", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSProcessingMethod", "定位方式", ValueType.BYTES, Writability.WRITABLE, FieldGroup.LOCATION, "如 GPS/NETWORK"),
        f("GPS:GPSAreaInformation", "区域信息", ValueType.BYTES, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSDifferential", "差分校正", ValueType.INT, Writability.WRITABLE, FieldGroup.LOCATION),
        f("GPS:GPSHPositioningError", "水平定位误差", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.LOCATION, "米"),

        // ---------- A.4 Interoperability IFD ----------
        f("EXIF:InteroperabilityIndex", "互操作索引", ValueType.TEXT, Writability.CONDITIONAL, FieldGroup.FILE),
        f("EXIF:InteroperabilityVersion", "互操作版本", ValueType.BYTES, Writability.CONDITIONAL, FieldGroup.FILE),

        // ---------- A.5 XMP ----------
        f("XMP:dc:title", "标题", ValueType.LANG_ALT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:dc:description", "描述", ValueType.LANG_ALT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:dc:creator", "作者", ValueType.TEXT_SEQ, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:dc:rights", "版权", ValueType.LANG_ALT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:dc:subject", "关键词", ValueType.TEXT_BAG, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:dc:format", "格式", ValueType.TEXT, Writability.CONDITIONAL, FieldGroup.XMP),
        f("XMP:xmp:CreateDate", "创建时间", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME),
        f("XMP:xmp:ModifyDate", "修改时间", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME),
        f("XMP:xmp:MetadataDate", "元数据时间", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME),
        f("XMP:xmp:CreatorTool", "创建工具", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("XMP:xmp:Rating", "评分", ValueType.DECIMAL, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:xmp:Label", "标签", ValueType.TEXT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:photoshop:Credit", "来源署名", ValueType.TEXT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:photoshop:Source", "来源", ValueType.TEXT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:photoshop:City", "城市", ValueType.TEXT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:photoshop:Country", "国家", ValueType.TEXT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:photoshop:Headline", "标题（新闻）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:photoshop:DateCreated", "创作日期", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME),
        f("XMP:tiff:Make", "制造商", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("XMP:tiff:Model", "机型", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("XMP:exif:GPSLatitude", "纬度（十进制）", ValueType.DECIMAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("XMP:exif:GPSLongitude", "经度（十进制）", ValueType.DECIMAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("XMP:exif:GPSAltitude", "海拔", ValueType.DECIMAL, Writability.WRITABLE, FieldGroup.LOCATION),
        f("XMP:exif:DateTimeOriginal", "拍摄时间", ValueType.DATETIME, Writability.WRITABLE, FieldGroup.TIME),
        f("XMP:exif:ExposureTime", "曝光时间", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("XMP:exif:FNumber", "光圈", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("XMP:exif:ISOSpeedRatings", "ISO", ValueType.INT_LIST, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("XMP:exif:FocalLength", "焦距", ValueType.RATIONAL, Writability.WRITABLE, FieldGroup.EXPOSURE),
        f("XMP:exif:LensModel", "镜头", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("XMP:aux:Lens", "镜头（aux）", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("XMP:aux:LensInfo", "镜头参数", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA),
        f("XMP:aux:SerialNumber", "序列号", ValueType.TEXT, Writability.WRITABLE, FieldGroup.CAMERA,
            "默认不写", privacySensitive = true),
        f("XMP:aux:ImageNumber", "图像编号", ValueType.TEXT, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:xmpMM:DocumentID", "文档 ID", ValueType.URI, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:xmpMM:InstanceID", "实例 ID", ValueType.URI, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:xmpMM:OriginalDocumentID", "原始文档 ID", ValueType.URI, Writability.WRITABLE, FieldGroup.XMP),
        f("XMP:xmpMM:History", "编辑历史", ValueType.TEXT_SEQ, Writability.CONDITIONAL, FieldGroup.XMP),

        // ---------- A.6 IPTC IIM（V1 只读） ----------
        f("IPTC:2:5", "对象名称", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:25", "关键词", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:40", "特殊指令", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:55", "日期", ValueType.DATE, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:60", "时间", ValueType.TIME, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:80", "作者", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:85", "作者职位", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:90", "城市", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:92", "子位置", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:95", "省份/州", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:100", "国家代码", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:101", "国家名", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:105", "标题", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:110", "来源署名", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:115", "来源", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:116", "版权声明", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:118", "联系方式", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:120", "标题（长）", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
        f("IPTC:2:122", "撰写者", ValueType.TEXT, Writability.READ_ONLY, FieldGroup.IPTC),
    )

    private val byKey: Map<TagKey, FieldSpec> = all.associateBy { it.key }

    /** 按附录 A 顺序分组，用于详情页 Tab。 */
    private val byGroup: Map<FieldGroup, List<FieldSpec>> = all.groupBy { it.group }

    val writableCount: Int = all.count { it.writability == Writability.WRITABLE }

    fun spec(key: TagKey): FieldSpec? = byKey[key]

    fun spec(full: String): FieldSpec? = runCatching { spec(TagKey.of(full)) }.getOrNull()

    fun group(group: FieldGroup): List<FieldSpec> = byGroup[group].orEmpty()

    /**
     * 字段在附录 A 里的次序；未收录的键（自定义、或解析不出来的）排在最后。
     *
     * 为什么需要它：diff 预览（编辑页）与批量预览（T5.5）都要把变更列表按
     * 「用户熟悉的顺序」排——按 `Map` 迭代顺序排会出现同一批字段每次顺序都不同，
     * 看上去像数据在抖。
     */
    fun order(key: TagKey): Int = orderIndex[key] ?: Int.MAX_VALUE

    private val orderIndex: Map<TagKey, Int> by lazy {
        all.withIndex().associate { (index, spec) -> spec.key to index }
    }

    /** 字段名 / 中文名 / 备注的模糊匹配（详情页搜索，T1.11）。 */
    fun search(query: String): List<FieldSpec> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        return all.filter {
            it.key.full.lowercase().contains(lower) ||
                it.label.contains(q) ||
                (it.note?.contains(q) == true)
        }
    }

    private fun f(
        key: String,
        label: String,
        type: ValueType,
        writability: Writability,
        group: FieldGroup,
        note: String? = null,
        privacySensitive: Boolean = false,
        options: List<Pair<Long, String>> = emptyList(),
    ) = FieldSpec(TagKey.of(key), label, type, writability, group, note, privacySensitive, options)
}
