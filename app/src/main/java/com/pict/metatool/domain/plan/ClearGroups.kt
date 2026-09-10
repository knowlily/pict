package com.pict.metatool.domain.plan

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey

/**
 * 按类别清空元数据（docs/07 T2.9、docs/01 FR-16 / FR-17、docs/08 验收点）。
 *
 * 为什么不直接复用 [FieldGroup]：
 * - FR-17 的「设备信息」不等于 `FieldGroup.CAMERA` —— MakerNote 和 Make/Model 同组，
 *   但清除风险完全不同（删厂商私有块 vs 删设备字符串），所以拆成独立目标；
 * - 缩略图与 ICC 描述**没有字段键**（分别在 APP1 的 IFD1 与 APP2 段里），字段级折叠
 *   表达不了，只能作为**段级意图**（[ClearSegment]）交给写通道；
 * - 用户勾选的是类别，不是内部枚举：UI 只暴露 [ClearTarget]，分组内有哪些键由本类决定。
 *
 * 未登记键的口径（[keysOf]）：真实文件里总有 [FieldCatalog] 之外的键（厂商自定义、
 * XMP 的 `crs:*` 等）。命名空间能确定的按推断一并清（`GPS:*`、`XMP:*`、`IPTC:*`）；
 * 确定不了的一律保留 —— `EXIF:*` 的未登记键无法判断属于设备还是曝光，猜错就是把用户
 * 没打算动的字段删了，比少删严重。整表清空（[clearAll]）不受此限：FR-16 要求除结构
 * 字段外全清，所以它是「删掉一切未被保护的键」。
 *
 * 本类只算「目标集合长什么样」，不落盘、不修改入参。
 */
enum class ClearTarget(val label: String, val detail: String) {
    GPS("位置", "GPS 全部字段，含 XMP 里的 GPS"),
    DEVICE("设备信息", "制造商 / 机型 / 软件 / 主机 / 镜头 / 序列号，不含 MakerNote"),
    TIME("时间信息", "EXIF 与 XMP 的时间字段"),
    MAKER_NOTE("厂商注释", "MakerNote（厂商私有块，常含序列号与拍摄参数）"),
    THUMBNAIL("内嵌缩略图", "APP1 里的缩略图段（段级，见 ClearSegment）"),
    XMP("XMP", "XMP 包里的字段；已归入其它组（时间/GPS 等）的不在此列"),
    IPTC("IPTC", "IPTC IIM 字段"),
    ICC("ICC 色彩描述", "ICC 描述段（段级，见 ClearSegment）"),
    ;

    /**
     * 段级载体：要删的东西在段上（缩略图 IFD1 / ICC APP2），字段表里没有对应的键。
     *
     * 当前写通道（T2.3 / T2.6）只按目标 [MetadataSet] 重写字段、尚未丢段，所以这两个
     * 目标的意图会出现在 `EditOutcome.segmentClears` 里等写通道消费，**不会**被冒充成
     * 「已经清干净」；字段级目标不受影响。
     */
    val isSegmentLevel: Boolean get() = this == THUMBNAIL || this == ICC
}

/** 段级清除意图（缩略图 / ICC）——字段级折叠表达不了，交给写通道。 */
enum class ClearSegment(val label: String) {
    THUMBNAIL("内嵌缩略图"),
    ICC_PROFILE("ICC 色彩描述"),
}

object ClearGroups {

    /** MakerNote 在 EXIF 里就是一个字段；FR-17 把它单列，所以这里也单列。 */
    val MAKER_NOTE: TagKey = TagKey.of("EXIF:MakerNote")

    /**
     * 整表清空时**必须留下**的字段：结构 + 色彩解释。
     *
     * 结构字段（`FieldCatalog` 里 `isStructural`：FILE 组的只读键，尺寸、位深、光度解释等）删了文件就解析不了；
     * `Orientation` 与 `ColorSpace` 不是结构字段，但 docs/08 的 FR-16 验收明确要求
     * 「方向与尺寸字段保留」，且 FR-16 正文要求「保留像素与色彩信息」—— 丢掉色彩空间会让
     * 剩下的像素不知道该按哪种色彩解释，所以一并保护。
     */
    val PROTECTED_ON_CLEAR_ALL: Set<TagKey> = setOf(
        TagKey.of("EXIF:Orientation"),
        TagKey.of("EXIF:ColorSpace"),
    )

    /** 整表清空（FR-16）还要丢掉的段：缩略图与 ICC。 */
    val ALL_SEGMENTS: Set<ClearSegment> = setOf(ClearSegment.THUMBNAIL, ClearSegment.ICC_PROFILE)

    /** FR-17 的可选分组，顺序与 UI 展示一致（含两个段级目标）。 */
    val targets: List<ClearTarget> = ClearTarget.entries.toList()

    /** [targets] 里的段级意图；结果去重，顺序按 [ClearSegment] 声明顺序。 */
    fun segmentsOf(targets: Iterable<ClearTarget>): Set<ClearSegment> =
        targets.mapNotNullTo(linkedSetOf()) { segmentOf(it) }

    fun segmentOf(target: ClearTarget): ClearSegment? = when (target) {
        ClearTarget.THUMBNAIL -> ClearSegment.THUMBNAIL
        ClearTarget.ICC -> ClearSegment.ICC_PROFILE
        else -> null
    }

    /** [set] 里会被 [target] 清掉的键。 */
    fun keysOf(set: MetadataSet, target: ClearTarget): Set<TagKey> =
        set.entries.keys.filterTo(linkedSetOf()) { matches(it, target) }

    /** 多个目标合起来会清掉的键（并集）。 */
    fun keysOf(set: MetadataSet, targets: Iterable<ClearTarget>): Set<TagKey> =
        targets.flatMapTo(linkedSetOf()) { keysOf(set, it) }

    /** 单个键是否属于 [target]；未登记的键按命名空间推断。 */
    fun matches(key: TagKey, target: ClearTarget): Boolean = when (target) {
        ClearTarget.GPS -> groupOf(key) == FieldGroup.LOCATION
        ClearTarget.DEVICE -> groupOf(key) == FieldGroup.CAMERA && !isMakerNote(key)
        ClearTarget.TIME -> groupOf(key) == FieldGroup.TIME
        ClearTarget.MAKER_NOTE -> isMakerNote(key)
        ClearTarget.XMP -> groupOf(key) == FieldGroup.XMP
        ClearTarget.IPTC -> groupOf(key) == FieldGroup.IPTC
        ClearTarget.THUMBNAIL -> isThumbnail(key)
        // ICC 没有字段键：能删的只有段，走 ClearSegment
        ClearTarget.ICC -> false
    }

    /** 清掉 [target] 覆盖的键，返回新集合（原集合不变）。 */
    fun clear(set: MetadataSet, target: ClearTarget): MetadataSet =
        clear(set, listOf(target))

    fun clear(set: MetadataSet, targets: Iterable<ClearTarget>): MetadataSet =
        keysOf(set, targets).fold(set) { acc, key -> acc.without(key) }

    /**
     * 整表清空（FR-16）：删掉一切未被保护的键。
     *
     * @param protectStructural true（默认）= 保留结构字段与 `Orientation`/`ColorSpace`；
     *   false 用于「导出/转码时彻底剥掉元数据」，此时连结构字段一起删 —— 那些键由转码
     *   输出重新生成，不会让新文件缺字段
     */
    fun clearAll(set: MetadataSet, protectStructural: Boolean = true): MetadataSet =
        keysToClearAll(set, protectStructural).fold(set) { acc, key -> acc.without(key) }

    /** 整表清空会删掉的键。 */
    fun keysToClearAll(set: MetadataSet, protectStructural: Boolean = true): Set<TagKey> =
        set.entries.keys.filterTo(linkedSetOf()) { !protectStructural || !isProtected(it) }

    /** 该键是否受整表清空保护。 */
    fun isProtected(key: TagKey): Boolean {
        if (FieldCatalog.spec(key)?.isStructural == true) return true
        return key in PROTECTED_ON_CLEAR_ALL
    }

    // ---------- 内部 ----------

    /**
     * 键所属的分组：已登记的用 [FieldCatalog] 的答案（唯一事实源），未登记的按命名空间
     * 推断 —— `GPS:*` 与含 GPS 的 XMP 键归位置组，其余 `XMP:*` 归 XMP，`IPTC:*` 归 IPTC；
     * 其它命名空间的未登记键返回 null（保守保留）。
     */
    private fun groupOf(key: TagKey): FieldGroup? {
        FieldCatalog.spec(key)?.let { return it.group }
        return when (key.namespace) {
            "GPS" -> FieldGroup.LOCATION
            "IPTC" -> FieldGroup.IPTC
            "XMP" -> if (isXmpGps(key)) FieldGroup.LOCATION else FieldGroup.XMP
            else -> null
        }
    }

    private fun isXmpGps(key: TagKey): Boolean = key.namespace == "XMP" && key.name.contains("GPS")

    private fun isMakerNote(key: TagKey): Boolean = key.name.contains("MakerNote", ignoreCase = true)

    /** 缩略图口径与 `ExifSegmentBudget` 一致：命名空间或名字里带 thumbnail。 */
    private fun isThumbnail(key: TagKey): Boolean =
        key.namespace == "THUMBNAIL" || key.name.contains("thumbnail", ignoreCase = true)
}
