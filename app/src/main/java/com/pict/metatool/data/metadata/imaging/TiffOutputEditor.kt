package com.pict.metatool.data.metadata.imaging

import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.apache.commons.imaging.common.RationalNumber
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryType
import org.apache.commons.imaging.formats.tiff.fieldtypes.AbstractFieldType
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.nio.ByteOrder
import java.time.format.DateTimeFormatter

/**
 * 把领域值写进 commons-imaging 的 [TiffOutputSet]（docs/07 T2.6）。
 *
 * 标签表反向复用读侧 [CommonsImagingStore] 的映射：键 → [TagInfo] 走同一份常量清单，
 * 所以「读得出来的字段」就是「写得回去的字段」，不存在两套口径。
 *
 * 值编码不做猜测：按 [TagInfo.dataTypes] 声明的类型逐个尝试，全部失败就记入
 * [ImagingWritePlan.dropped]，保留原值——和读侧「不猜 tag 号」的取舍一致。
 * 库里缺常量、只能按规范手写 tag 号的字段（ColorSpace、Pixel*Dimension、OffsetTime*
 * 等）没有 [TagInfo]，本任务不支持写入，同样进 dropped。
 *
 * 删除按 [TagInfo.directoryType] 定位到具体目录，不按 tag 号遍历全部目录：
 * IFD1 缩略图与 IFD0 常共用同一 tag 号，盲删会连缩略图的字段一起清掉。
 */
object TiffOutputEditor {

    private val EXIF_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * 对 [outputSet] 就地施加「删掉目标里没有的键、覆盖值变了的键」，返回本次实际做了什么。
     *
     * @param tagInfoFor 领域键 → 库里的标签常量；查不到即视为不可写
     */
    fun apply(
        outputSet: TiffOutputSet,
        current: MetadataSet,
        target: MetadataSet,
        tagInfoFor: (TagKey) -> TagInfo?,
    ): ImagingWritePlan {
        val removals = LinkedHashSet<TagKey>()
        val assignments = LinkedHashMap<TagKey, TagValue>()
        val dropped = LinkedHashSet<TagKey>()
        val order = outputSet.byteOrder

        (current.entries.keys - target.entries.keys).forEach { key ->
            val info = tagInfoFor(key)
            val dir = info?.let { directoryFor(outputSet, it, create = false) }
            if (info == null || dir == null) {
                dropped += key
            } else {
                dir.removeField(info.tag)
                removals += key
            }
        }

        // 只动值真的变了的键。读得回来但写不回去的字段（ColorSpace、XPTitle 之类）本就在
        // 文件里躺得好好的，不该因为「这次写过一遍」就被报成 dropped，更不该被清掉。
        target.entries.forEach { (key, value) ->
            if (current[key] == value) return@forEach
            val info = tagInfoFor(key)
            val field = info?.let { buildField(it, value, order) }
            val dir = info?.let { directoryFor(outputSet, it, create = true) }
            if (info == null || field == null || dir == null) {
                dropped += key
            } else {
                dir.removeField(info.tag)
                dir.add(field)
                assignments[key] = value
            }
        }

        return ImagingWritePlan(removals, assignments, dropped)
    }

    /** 目录未知时返回 null：宁可不写，也不把值塞进错误的 IFD。 */
    private fun directoryFor(
        set: TiffOutputSet,
        info: TagInfo,
        create: Boolean,
    ): TiffOutputDirectory? {
        if (info.isOffset) return null
        return when (info.directoryType) {
            TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD ->
                if (create) set.getOrCreateExifDirectory() else set.exifDirectory

            TiffDirectoryType.EXIF_DIRECTORY_GPS ->
                if (create) set.getOrCreateGpsDirectory() else set.gpsDirectory

            TiffDirectoryType.EXIF_DIRECTORY_INTEROP_IFD ->
                if (create) set.interoperabilityDirectory ?: set.addInteroperabilityDirectory()
                else set.interoperabilityDirectory

            TiffDirectoryType.EXIF_DIRECTORY_IFD0,
            TiffDirectoryType.TIFF_DIRECTORY_IFD0,
            TiffDirectoryType.TIFF_DIRECTORY_ROOT,
            ->
                if (create) set.getOrCreateRootDirectory() else set.rootDirectory

            else -> null
        }
    }

    /**
     * 按目录声明类型逐个尝试编码，第一个成功的类型即采用。
     *
     * 类型顺序取自 [TagInfo.dataTypes]（如 PixelXDimension 是 SHORT 或 LONG），
     * 因此值形态不必与库里恰好一致，`ISO 400` 既能落成 SHORT 也能落成 LONG。
     */
    private fun buildField(info: TagInfo, value: TagValue, order: ByteOrder): TiffOutputField? {
        info.dataTypes.forEach { type ->
            val raw = javaValue(value, type) ?: return@forEach
            val bytes = runCatching { info.encodeValue(type, raw, order) }.getOrNull() ?: return@forEach
            if (bytes.isEmpty()) return@forEach
            val count = if (type.size > 0) bytes.size / type.size else bytes.size
            return TiffOutputField(info, type, count, bytes)
        }
        return null
    }

    /** 领域值 → commons-imaging 认的 Java 形态；类型不搭返回 null，由调用方换下一种类型。 */
    private fun javaValue(value: TagValue, type: AbstractFieldType): Any? = when (value) {
        is TagValue.Text -> if (isText(type)) value.value else null

        is TagValue.TextList -> if (isText(type)) value.values.toTypedArray() else null

        is TagValue.IntValue -> when {
            isText(type) -> value.value.toString()
            type === AbstractFieldType.BYTE -> value.value.toByte()
            type === AbstractFieldType.SHORT -> value.value.toShort()
            type === AbstractFieldType.LONG -> value.value.toInt()
            type === AbstractFieldType.DOUBLE -> value.value.toDouble()
            type === AbstractFieldType.RATIONAL -> RationalNumber(value.value.toInt(), 1)
            else -> null
        }

        is TagValue.IntList -> when (type) {
            AbstractFieldType.BYTE -> value.values.map { it.toByte() }.toByteArray()
            AbstractFieldType.SHORT -> value.values.map { it.toShort() }.toShortArray()
            AbstractFieldType.LONG -> value.values.map { it.toInt() }.toIntArray()
            AbstractFieldType.RATIONAL -> value.values.map { RationalNumber(it.toInt(), 1) }.toTypedArray()
            else -> null
        }

        is TagValue.RationalValue ->
            if (type === AbstractFieldType.RATIONAL) {
                RationalNumber(value.value.numerator.toInt(), value.value.denominator.toInt())
            } else {
                null
            }

        is TagValue.RationalList ->
            if (type === AbstractFieldType.RATIONAL) {
                value.values
                    .map { RationalNumber(it.numerator.toInt(), it.denominator.toInt()) }
                    .toTypedArray()
            } else {
                null
            }

        is TagValue.DecimalValue -> when (type) {
            AbstractFieldType.DOUBLE -> value.value
            AbstractFieldType.FLOAT -> value.value.toFloat()
            AbstractFieldType.RATIONAL -> RationalNumber.valueOf(value.value)
            else -> null
        }

        is TagValue.DecimalList ->
            if (type === AbstractFieldType.DOUBLE) value.values.toDoubleArray() else null

        is TagValue.Timestamp -> if (isText(type)) EXIF_TIME.format(value.value) else null

        is TagValue.DateValue -> if (isText(type)) value.value.toString() else null

        is TagValue.TimeValue -> if (isText(type)) value.value.toString() else null

        is TagValue.Binary -> if (type === AbstractFieldType.BYTE) hexToBytes(value.hex) else null

        // LangAlt / UriValue 是 XMP 语义，TIFF 容器里没有对应类型。
        is TagValue.LangAlt, is TagValue.UriValue -> null
    }

    private fun isText(type: AbstractFieldType): Boolean =
        type === AbstractFieldType.ASCII || type === AbstractFieldType.UNDEFINED

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
