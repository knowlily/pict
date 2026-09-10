package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.FieldCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T3.1、docs/03 §8：预设 JSON 的解析与逐条校验。
 *
 * 校验口径是「一次报全部问题、每条带路径」，所以断言都落在 issue 的 `path` / `message` 上，
 * 而不是只断言「失败」——只断言失败的话，路径写错也没人发现，作者照样要猜。
 */
class PresetParserTest {

    @Test
    fun `内置预设全部可解析且没有警告`() {
        val files = PresetTestSupport.builtinFiles()
        assertTrue("仓库根 presets/ 下应有内置预设", files.isNotEmpty())

        files.forEach { file ->
            val result = PresetParser.parse(file.readText(), PresetOrigin.BUILTIN, file.name)
            val success = result as? PresetParseResult.Success
                ?: error("${file.name} 解析失败：${(result as PresetParseResult.Invalid).issues.joinToString()}")
            assertTrue("${file.name} 应至少有一个字段", success.preset.keyCount > 0)
            assertEquals("${file.name} 的 origin 应标为内置", PresetOrigin.BUILTIN, success.preset.origin)
            assertTrue(
                "${file.name} 不该有警告（内置预设引用的字段都应在字段目录里）：${success.warnings}",
                success.warnings.isEmpty(),
            )
            assertTrue(
                "${file.name} 的 id 应以类别开头，便于在 UI 里分组",
                success.preset.id.startsWith(success.preset.kind.id),
            )
        }
    }

    @Test
    fun `schemaVersion 高于支持版本时提示升级 App`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 2,
              "id": "device-x", "kind": "device", "name": "未来预设",
              "fields": { "EXIF:Make": { "mode": "fixed", "value": "Apple" } }
            }
            """.trimIndent(),
            file = "future.json",
        )
        val issues = (result as PresetParseResult.Invalid).issues
        assertEquals(1, issues.size)
        assertEquals("future.json → schemaVersion", issues.first().path)
        assertTrue(issues.first().message.contains("升级 App"))
    }

    @Test
    fun `id 不合规时给出实际值`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 1,
              "id": "Device_X", "kind": "device", "name": "样例",
              "fields": { "EXIF:Make": { "mode": "fixed", "value": "Apple" } }
            }
            """.trimIndent(),
        )
        val issues = (result as PresetParseResult.Invalid).issues
        assertEquals(1, issues.size)
        assertEquals("id", issues.first().path)
        assertTrue(issues.first().message.contains("Device_X"))
    }

    @Test
    fun `pool 为空时错误路径指到该字段的 pool`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 1,
              "id": "device-y", "kind": "device", "name": "样例",
              "fields": { "EXIF:Model": { "mode": "pool", "pool": [] } }
            }
            """.trimIndent(),
        )
        val issues = (result as PresetParseResult.Invalid).issues
        assertEquals("fields.EXIF:Model.pool", issues.single().path)
        assertTrue(issues.single().message.contains("不能为空"))
    }

    @Test
    fun `range 的 min 大于 max 时给出两个值`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 1,
              "id": "device-z", "kind": "device", "name": "样例",
              "fields": { "EXIF:FNumber": { "mode": "range", "min": 4, "max": 1.6 } }
            }
            """.trimIndent(),
        )
        val issues = (result as PresetParseResult.Invalid).issues
        assertEquals("fields.EXIF:FNumber.min", issues.single().path)
        assertTrue(issues.single().message.contains("必须 ≤ max"))
        assertTrue(issues.single().message.contains("1.6"))
    }

    @Test
    fun `未知模式与未知约束类型都被拒绝`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 1,
              "id": "device-w", "kind": "device", "name": "样例",
              "fields": { "EXIF:Make": { "mode": "guess" } },
              "constraints": [ { "type": "magic", "fields": ["EXIF:Make", "EXIF:Model"] } ]
            }
            """.trimIndent(),
        )
        val issues = (result as PresetParseResult.Invalid).issues
        assertEquals(2, issues.size)
        assertEquals(setOf("fields.EXIF:Make.mode", "constraints[0].type"), issues.map { it.path }.toSet())
    }

    @Test
    fun `未登记字段只警告不阻断`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 1,
              "id": "device-v", "kind": "device", "name": "样例",
              "fields": {
                "EXIF:Make": { "mode": "fixed", "value": "Apple" },
                "EXIF:NotRegisteredAtAll": { "mode": "fixed", "value": "x" }
              }
            }
            """.trimIndent(),
        )
        val success = result as PresetParseResult.Success
        assertEquals(2, success.preset.keyCount)
        assertEquals(1, success.warnings.size)
        assertEquals(PresetIssueLevel.WARNING, success.warnings.single().level)
        assertEquals("fields.EXIF:NotRegisteredAtAll", success.warnings.single().path)
        assertEquals(null, FieldCatalog.spec(PresetTestSupport.key("EXIF:NotRegisteredAtAll")))
    }

    @Test
    fun `JSON 语法错误与根节点类型错误都被拒绝`() {
        val broken = PresetParser.parse("{ \"schemaVersion\": 1, ")
        assertTrue((broken as PresetParseResult.Invalid).issues.single().message.contains("JSON 语法错误"))

        val array = PresetParser.parse("[1, 2, 3]")
        assertEquals("根节点必须是对象", (array as PresetParseResult.Invalid).issues.single().message)
    }

    @Test
    fun `多个问题一次报全`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 1,
              "id": "bad-id!",
              "kind": "unknown-kind",
              "fields": { "EXIF:Make": { "mode": "pool", "pool": [] } }
            }
            """.trimIndent(),
        )
        val paths = (result as PresetParseResult.Invalid).issues.map { it.path }
        assertEquals(setOf("id", "kind", "name", "fields.EXIF:Make.pool"), paths.toSet())
    }

    @Test
    fun `datetime 规则要求 start 早于 end`() {
        val result = PresetParser.parse(
            """
            {
              "schemaVersion": 1, "id": "time-x", "kind": "time", "name": "样例",
              "fields": {
                "EXIF:DateTimeOriginal": { "mode": "datetime", "start": "2026-01-01T00:00:00", "end": "2024-01-01T00:00:00" }
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            "fields.EXIF:DateTimeOriginal.start",
            (result as PresetParseResult.Invalid).issues.single().path,
        )
    }

    @Test
    fun `内置预设里的固定值与池都能读成字面值`() {
        val iphone = PresetTestSupport.preset("device.iphone-16-pro")
        val make = iphone.rule(PresetTestSupport.key("EXIF:Make"))
        assertEquals(FieldRule.Fixed(PresetValue.Text("Apple")), make)

        val model = iphone.rule(PresetTestSupport.key("EXIF:Model")) as FieldRule.Pool
        assertTrue("权重应保留", model.candidates.all { it.weight > 0.0 })
    }
}
