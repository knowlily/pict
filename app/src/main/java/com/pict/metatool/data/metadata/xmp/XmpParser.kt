package com.pict.metatool.data.metadata.xmp

import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.pict.metatool.data.metadata.exif.ExifValueCodec
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.model.ValueType
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * XMP 解析（docs/07 T1.4 的 XMP 部分）。
 *
 * 输入是 ExifInterface 给出的原始 XMP 包（`TAG_XMP`），输出按 [FieldCatalog] 的 XMP 段映射。
 * 只读我们目录里登记的属性——`crs:*` 这类厂商私有前缀故意不读（附录 A 已注明）。
 *
 * 解析失败不抛异常，返回空表：XMP 是「锦上添花」，坏了不该让整张图读不出来。
 */
object XmpParser {

    /** 目录里用的前缀 → 官方命名空间 URI。 */
    private val PREFIX_TO_NS: Map<String, String> = mapOf(
        "dc" to "http://purl.org/dc/elements/1.1/",
        "xmp" to "http://ns.adobe.com/xap/1.0/",
        "photoshop" to "http://ns.adobe.com/photoshop/1.0/",
        "tiff" to "http://ns.adobe.com/tiff/1.0/",
        "exif" to "http://ns.adobe.com/exif/1.0/",
        "aux" to "http://ns.adobe.com/exif/1.0/aux/",
        "xmpMM" to "http://ns.adobe.com/xap/1.0/mm/",
    )

    fun parse(packet: String): Map<TagKey, TagValue> {
        val meta = runCatching { XMPMetaFactory.parseFromString(packet) }.getOrNull()
            ?: return emptyMap()
        val out = LinkedHashMap<TagKey, TagValue>()
        FieldCatalog.group(FieldGroup.XMP).forEach { spec ->
            val parts = spec.key.name.split(':', limit = 2)
            if (parts.size != 2) return@forEach
            val namespace = PREFIX_TO_NS[parts[0]] ?: return@forEach
            read(meta, namespace, parts[1], spec.type)?.let { out[spec.key] = it }
        }
        return out
    }

    private fun read(meta: XMPMeta, ns: String, local: String, type: ValueType): TagValue? =
        runCatching {
            when (type) {
                ValueType.LANG_ALT -> readLangAlt(meta, ns, local)
                ValueType.TEXT_SEQ, ValueType.TEXT_BAG -> readArray(meta, ns, local)
                ValueType.INT_LIST -> readArray(meta, ns, local)?.let { value ->
                    (value as? TagValue.TextList)?.values?.mapNotNull { it.toLongOrNull() }
                        ?.let { TagValue.IntList(it) }
                }
                ValueType.DATETIME -> meta.getProperty(ns, local)?.value
                    ?.let { parseDateTimeWithOffset(it) }
                ValueType.DATE -> meta.getProperty(ns, local)?.value
                    ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                    ?.let { TagValue.DateValue(it) }
                ValueType.TIME -> meta.getProperty(ns, local)?.value
                    ?.let { ExifValueCodec.parseTime(it) }?.let { TagValue.TimeValue(it) }
                ValueType.INT -> meta.getPropertyLong(ns, local)?.let { TagValue.IntValue(it) }
                ValueType.DECIMAL -> meta.getPropertyDouble(ns, local)?.let { TagValue.DecimalValue(it) }
                ValueType.RATIONAL -> meta.getProperty(ns, local)?.value
                    ?.let { ExifValueCodec.parseRational(it) }
                    ?.let { TagValue.RationalValue(it) }
                ValueType.URI -> meta.getProperty(ns, local)?.value?.let { TagValue.UriValue(it) }
                else -> meta.getProperty(ns, local)?.value?.let { TagValue.Text(it) }
            }
        }.getOrNull()

    private fun readLangAlt(meta: XMPMeta, ns: String, local: String): TagValue? {
        val exact = runCatching { meta.getLocalizedText(ns, local, null, "x-default") }.getOrNull()
        val fallback = exact ?: runCatching { meta.getLocalizedText(ns, local, null, null) }.getOrNull()
        fallback?.value?.let { return TagValue.LangAlt(mapOf("x-default" to it)) }
        return readArray(meta, ns, local)
    }

    private fun readArray(meta: XMPMeta, ns: String, local: String): TagValue? {
        val count = runCatching { meta.countArrayItems(ns, local) }.getOrDefault(0)
        if (count <= 0) return null
        val values = (1..count).mapNotNull { index ->
            runCatching { meta.getArrayItem(ns, local, index)?.value }.getOrNull()
        }
        return values.takeIf { it.isNotEmpty() }?.let { TagValue.TextList(it) }
    }

    /** XMP 日期常带时区（`2008-07-31T10:38:11+02:00`），时区解析失败时退回本地时间。 */
    fun parseDateTimeWithOffset(raw: String): TagValue.Timestamp? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let {
            return TagValue.Timestamp(it.toLocalDateTime(), it.offset)
        }
        return ExifValueCodec.parseDateTime(text)?.let { TagValue.Timestamp(it) }
    }
}
