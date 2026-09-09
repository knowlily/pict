package com.pict.metatool.data.metadata.exif

import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue

/**
 * EXIF 段大小预算与字段丢弃策略（docs/07 T2.5、docs/04 §6.1）。
 *
 * JPEG 的 APP1 段长度字段只有 16 位，单个 EXIF 段最多 [MAX_APP1_BYTES] 字节。塞不下的
 * 情形是真会发生的：大缩略图、超长 XMP、几百个字段叠加。ExifInterface 1.4.x 遇到超限
 * 会拒绝写入（抛 IOException），用户拿到的是一句「写入失败」——文件没坏，但也没改成。
 *
 * 这里在落盘**之前**先算一遍，超了就从最不值钱的开始丢：
 *
 * 1. [DropTier.THUMBNAIL] —— 内嵌缩略图，删掉只影响相册列表的小图预览
 * 2. [DropTier.XMP] —— XMP 包，EXIF 才是本工具的主战场
 * 3. [DropTier.NON_CRITICAL] —— 拍摄参数之外的零碎字段
 *
 * [ESSENTIAL] 里的字段永不丢弃：丢了就改变了「这张照片拍的是什么」这一层信息。全丢完
 * 还是超限时返回 [SegmentFitResult.overBudget]，由调用方决定报错还是转码。
 *
 * **估算是近似值**：真实字节数取决于 IFD 布局、标签类型和 4 字节对齐，这里按类型给
 * 出保守上界，只用来判断「会不会超」和「先丢谁」，不用于校验实际写入长度。
 */
object ExifSegmentBudget {

    /** APP1 段长度字段 16 位，能表达的最大字节数（docs/04 §6.1）。 */
    const val MAX_APP1_BYTES: Int = 65_533

    /** Exif 头（6 字节）+ TIFF 头 + IFD0/ExifIFD/GPS IFD 的偏移表，取经验保守值。 */
    private const val FIXED_OVERHEAD_BYTES: Int = 200

    /** 单个 IFD 条目的固定开销：标签号 + 类型 + 数量 + 值或偏移。 */
    private const val IFD_ENTRY_BYTES: Int = 12

    /**
     * 永不丢弃的字段：拍摄时间、设备、方向、曝光核心参数、GPS 坐标。
     *
     * 判断口径是「删掉之后这张照片的语义是否变了」。厂商序列号、镜头型号这类虽然也有用，
     * 但属于可牺牲范围，所以不在表里。
     */
    private val ESSENTIAL: Set<String> = setOf(
        "EXIF:Make",
        "EXIF:Model",
        "EXIF:Orientation",
        "EXIF:DateTime",
        "EXIF:DateTimeOriginal",
        "EXIF:DateTimeDigitized",
        "EXIF:FNumber",
        "EXIF:ExposureTime",
        "EXIF:ISOSpeedRatings",
        "EXIF:FocalLength",
        "GPS:GPSLatitude",
        "GPS:GPSLatitudeRef",
        "GPS:GPSLongitude",
        "GPS:GPSLongitudeRef",
        "GPS:GPSAltitude",
        "GPS:GPSAltitudeRef",
    )

    /** 估算一组字段序列化成 EXIF 段后的字节数（含固定开销）。 */
    fun estimate(entries: Map<TagKey, TagValue>): Int =
        FIXED_OVERHEAD_BYTES + entries.entries.sumOf { (key, value) -> align4(sizeOf(key, value)) }

    /** 单个字段的估算字节数：IFD 条目开销 + 值本身。 */
    fun sizeOf(key: TagKey, value: TagValue): Int = IFD_ENTRY_BYTES + payloadBytes(value)

    /**
     * 按优先级丢弃直到落进 [limit]，返回实际保留的字段与被丢弃的字段。
     *
     * 不修改入参：返回的 [SegmentFitResult.kept] 是新对象。
     */
    fun fit(target: MetadataSet, limit: Int = MAX_APP1_BYTES): SegmentFitResult {
        val kept = LinkedHashMap(target.entries)
        var size = estimate(kept)
        if (size <= limit) return SegmentFitResult(target, emptyMap(), size, limit)

        val dropped = LinkedHashMap<TagKey, DropTier>()
        DropTier.ORDER.forEach { tier ->
            if (size <= limit) return@forEach
            // 先取快照再删，否则迭代中修改 map 会抛 ConcurrentModificationException
            kept.entries.filter { tierOf(it.key) == tier }.toList().forEach { (key, value) ->
                if (size <= limit) return@forEach
                kept.remove(key)
                dropped[key] = tier
                size -= align4(sizeOf(key, value))
            }
        }

        return SegmentFitResult(target.copy(entries = kept), dropped, size, limit)
    }

    /** 字段属于哪一级；[ESSENTIAL] 返回 null，表示不参与丢弃。 */
    private fun tierOf(key: TagKey): DropTier? = when {
        isThumbnail(key) -> DropTier.THUMBNAIL
        key.namespace == "XMP" -> DropTier.XMP
        key.full !in ESSENTIAL -> DropTier.NON_CRITICAL
        else -> null
    }

    private fun isThumbnail(key: TagKey): Boolean =
        key.namespace == "THUMBNAIL" || key.name.contains("thumbnail", ignoreCase = true)

    /** 值的估算字节数，按 EXIF 存储类型取值。 */
    private fun payloadBytes(value: TagValue): Int = when (value) {
        is TagValue.Text -> utf8(value.value) + 1
        is TagValue.UriValue -> utf8(value.value) + 1
        is TagValue.IntValue -> 4
        is TagValue.DecimalValue -> 8
        is TagValue.RationalValue -> 8
        is TagValue.IntList -> 4 * value.values.size
        is TagValue.DecimalList -> 8 * value.values.size
        is TagValue.RationalList -> 8 * value.values.size
        // EXIF 时间字段是 ASCII 字面量："YYYY:MM:DD HH:MM:SS" + NUL
        is TagValue.Timestamp -> 20 + if (value.offset != null) 7 else 0
        is TagValue.DateValue -> 11
        is TagValue.TimeValue -> 9
        is TagValue.Binary -> value.byteCount
        is TagValue.LangAlt -> 8 + value.values.entries.sumOf { (lang, text) ->
            utf8(lang) + utf8(text) + 2
        }
        is TagValue.TextList -> value.values.sumOf { utf8(it) + 1 }
    }

    /** 字符串按 UTF-8 计长——XMP 里的中文一个字 3 字节，按字符数算会低估三倍。 */
    private fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun align4(n: Int): Int = (n + 3) and 3.inv()
}

/** 丢弃优先级，[ORDER] 就是实际丢弃顺序。 */
enum class DropTier(val label: String) {
    THUMBNAIL("缩略图"),
    XMP("XMP"),
    NON_CRITICAL("非关键字段"),
    ;

    companion object {
        /** 从最不值钱的开始丢。 */
        val ORDER: List<DropTier> = listOf(THUMBNAIL, XMP, NON_CRITICAL)
    }
}

/**
 * 段大小预算的结果。
 *
 * @param kept 实际能写进去的字段（[MetadataSet] 的其他部分原样保留）
 * @param dropped 被丢弃的字段 → 丢弃级别
 * @param estimatedBytes 保留字段的估算字节数
 * @param limitBytes 本次使用的上限
 */
data class SegmentFitResult(
    val kept: MetadataSet,
    val dropped: Map<TagKey, DropTier>,
    val estimatedBytes: Int,
    val limitBytes: Int,
) {

    val hasDropped: Boolean get() = dropped.isNotEmpty()

    /** 丢完还是超限：说明 [ESSENTIAL] 自己就装不下，只能报错或转码。 */
    val overBudget: Boolean get() = estimatedBytes > limitBytes

    /** 报告用的一行摘要。 */
    fun summary(): String {
        val base = "估算 $estimatedBytes / $limitBytes 字节"
        if (!hasDropped) return "$base，无需丢弃"
        val byTier = dropped.values.groupingBy { it }.eachCount()
            .entries.joinToString("、") { "${it.key.label} ${it.value} 项" }
        return "$base，丢弃 $byTier"
    }
}
