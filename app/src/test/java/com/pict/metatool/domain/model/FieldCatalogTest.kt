package com.pict.metatool.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字段目录完整性（docs/07 T1.12）。目录是唯一事实源，必须守住不变量。 */
class FieldCatalogTest {

    @Test
    fun `目录规模符合附录 A 预期`() {
        assertTrue("字段数应 >= 180，实际 ${FieldCatalog.all.size}", FieldCatalog.all.size >= 180)
        assertTrue("可写字段应 >= 120，实际 ${FieldCatalog.writableCount}", FieldCatalog.writableCount >= 120)
    }

    @Test
    fun `键唯一且命名空间合法`() {
        val keys = FieldCatalog.all.map { it.key }
        assertEquals("存在重复键", keys.size, keys.toSet().size)
        val allowed = setOf("EXIF", "GPS", "XMP", "IPTC")
        keys.forEach { assertTrue("非法命名空间：$it", it.namespace in allowed) }
    }

    @Test
    fun `标签非空且分组自洽`() {
        FieldCatalog.all.forEach { spec ->
            assertTrue("空标签：${spec.key.full}", spec.label.isNotBlank())
            assertTrue(
                "分组索引缺失：${spec.key.full}",
                FieldCatalog.group(spec.group).contains(spec),
            )
        }
        FieldGroup.entries.forEach { g ->
            assertTrue("分组 ${g.label} 为空", FieldCatalog.group(g).isNotEmpty())
        }
    }

    @Test
    fun `只读的文件结构字段被标记为 structural`() {
        FieldCatalog.all
            .filter { it.writability == Writability.READ_ONLY && it.group == FieldGroup.FILE }
            .forEach { assertTrue("${it.key.full} 应为结构字段", it.isStructural) }
    }

    @Test
    fun `枚举字段的选项键不重复`() {
        FieldCatalog.all.filter { it.options.isNotEmpty() }.forEach { spec ->
            val ids = spec.options.map { it.first }
            assertEquals("${spec.key.full} 枚举键重复", ids.size, ids.toSet().size)
            assertTrue("${spec.key.full} 枚举文案为空", spec.options.all { it.second.isNotBlank() })
        }
    }

    @Test
    fun `按规范名查得到字段`() {
        val dt = FieldCatalog.spec("EXIF:DateTimeOriginal")
        assertNotNull(dt)
        assertEquals(FieldGroup.TIME, dt!!.group)
        assertEquals(ValueType.DATETIME, dt.type)
        assertTrue(dt.canEdit)

        val lat = FieldCatalog.spec(TagKey.of("GPS:GPSLatitude"))
        assertEquals(ValueType.RATIONAL_LIST, lat!!.type)

        val iptc = FieldCatalog.spec("IPTC:2:5")
        assertNotNull("IPTC 键含冒号，应能解析", iptc)
        assertEquals(FieldGroup.IPTC, iptc!!.group)
        assertEquals(Writability.READ_ONLY, iptc.writability)
    }

    @Test
    fun `未知字段返回 null 而不抛异常`() {
        assertNull(FieldCatalog.spec("EXIF:NotARealTag"))
        assertNull(FieldCatalog.spec("非法键"))
        assertNull(FieldCatalog.spec(""))
    }

    @Test
    fun `隐私字段被标注`() {
        val privacy = FieldCatalog.all.filter { it.privacySensitive }.map { it.key.full }
        assertTrue("机身序列号应标隐私", "EXIF:BodySerialNumber" in privacy)
        assertTrue("镜头序列号应标隐私", "EXIF:LensSerialNumber" in privacy)
        assertTrue("图像唯一 ID 应标隐私", "EXIF:ImageUniqueID" in privacy)
    }

    @Test
    fun `搜索能按中文名与键名命中`() {
        assertTrue(FieldCatalog.search("光圈").any { it.key.full == "EXIF:FNumber" })
        assertTrue(FieldCatalog.search("datetimeoriginal").any { it.key.full == "EXIF:DateTimeOriginal" })
        assertTrue(FieldCatalog.search("焦距").any { it.key.full == "EXIF:FocalLength" })
        assertTrue("空查询应返回空集", FieldCatalog.search("   ").isEmpty())
        assertTrue("无结果应返回空集", FieldCatalog.search("zzz不存在zzz").isEmpty())
    }

    @Test
    fun `TagKey 解析与还原`() {
        assertEquals(TagKey("EXIF", "FNumber"), TagKey.of("EXIF:FNumber"))
        assertEquals(TagKey("XMP", "dc:title"), TagKey.of("XMP:dc:title"))
        assertEquals("XMP:dc:title", TagKey.of("XMP:dc:title").full)
        listOf("", "没有冒号", ":开头", "结尾:").forEach {
            runCatching { TagKey.of(it) }.let { r ->
                assertTrue("非法键应抛 IllegalArgumentException：$it", r.isFailure)
            }
        }
    }
}
