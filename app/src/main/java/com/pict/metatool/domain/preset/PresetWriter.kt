package com.pict.metatool.domain.preset

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 预设 → preset-v1 JSON 文本（T3.1 的写侧，读侧是 [PresetParser]）。
 *
 * 为什么需要写侧：用户自建预设要落盘（`files/presets/user-*.json`，见 `presets/README.md`），
 * 那份文件将来还要被 [PresetParser] 读回来。两条路共用一个格式，不另开「用户预设格式」——
 * 否则迟早出现「UI 存得进去、重开读不回来」。[PresetWriterTest] 用 round-trip 钉住这条。
 *
 * 数值按 [PresetValue.Number.literal] 原样吐出（`JsonUnquotedLiteral`）：
 * 文本字段里的 `"1.78"` 不能被写成 `1.78`，否则回读就成了数字再转字符串，尾数会变。
 */
@OptIn(ExperimentalSerializationApi::class)
object PresetWriter {

    const val SOURCE_BUILTIN = "builtin"
    const val SOURCE_USER = "user"

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val momentFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** 序列化一个预设；[source] 写进 `source` 字段（内置 / user）。 */
    fun write(preset: Preset, source: String = SOURCE_USER): String {
        val root = buildJsonObject {
            put("schemaVersion", Preset.SCHEMA_VERSION)
            put("id", preset.id)
            put("kind", preset.kind.id)
            put("name", preset.name)
            preset.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            if (preset.tags.isNotEmpty()) {
                putJsonArray("tags") { preset.tags.forEach { add(it) } }
            }
            put("source", source)
            preset.author?.takeIf { it.isNotBlank() }?.let { put("author", it) }
            preset.license?.takeIf { it.isNotBlank() }?.let { put("license", it) }
            putJsonObject("fields") {
                preset.fields.forEach { (key, rule) -> put(key.full, ruleJson(rule)) }
            }
            if (preset.constraints.isNotEmpty()) {
                putJsonArray("constraints") { preset.constraints.forEach { add(constraintJson(it)) } }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun ruleJson(rule: FieldRule): JsonObject = buildJsonObject {
        when (rule) {
            is FieldRule.Fixed -> {
                put("mode", FieldMode.FIXED.id)
                put("value", valueJson(rule.value))
            }

            is FieldRule.Pool -> {
                put("mode", FieldMode.POOL.id)
                putJsonArray("pool") {
                    rule.candidates.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("value", valueJson(entry.value))
                                put("weight", entry.weight)
                            },
                        )
                    }
                }
            }

            is FieldRule.Range -> {
                put("mode", FieldMode.RANGE.id)
                put("min", rule.min)
                put("max", rule.max)
                put("precision", rule.precision)
            }

            is FieldRule.DateTimeRule -> {
                put("mode", FieldMode.DATETIME.id)
                put("start", momentFormat.format(rule.start))
                put("end", momentFormat.format(rule.end))
                rule.hourWeights?.let { weights ->
                    putJsonArray("hourWeights") { weights.forEach { add(it) } }
                }
                rule.minuteWeights?.let { weights ->
                    putJsonArray("minuteWeights") { weights.forEach { add(it) } }
                }
            }

            is FieldRule.Gps -> {
                put("mode", FieldMode.GPS.id)
                put("lat", rule.latitude)
                put("lon", rule.longitude)
                put("radiusMeters", rule.radiusMeters)
            }

            is FieldRule.FromSource -> {
                put("mode", FieldMode.FROM_SOURCE.id)
                put("sourceKey", rule.sourceKey.full)
            }

            is FieldRule.Jitter -> {
                put("mode", FieldMode.JITTER.id)
                rule.radiusMeters?.let { put("radiusMeters", it) }
                put("percent", rule.percent)
            }
        }
    }

    private fun valueJson(value: PresetValue): JsonElement = when (value) {
        is PresetValue.Text -> JsonPrimitive(value.text)
        is PresetValue.Number -> JsonUnquotedLiteral(value.literal)
        PresetValue.Null -> JsonNull
    }

    private fun constraintJson(constraint: PresetConstraint): JsonObject = buildJsonObject {
        when (constraint) {
            is PresetConstraint.DatetimeOrder -> {
                put("type", TYPE_DATETIME_ORDER)
                putJsonArray("fields") { constraint.keys.forEach { add(it.full) } }
            }

            is PresetConstraint.Mutex -> {
                put("type", TYPE_MUTEX)
                putJsonArray("fields") { constraint.keys.forEach { add(it.full) } }
            }

            is PresetConstraint.Requires -> {
                put("type", TYPE_REQUIRES)
                putJsonArray("fields") { constraint.keys.forEach { add(it.full) } }
            }

            is PresetConstraint.Clamp -> {
                put("type", TYPE_CLAMP)
                putJsonArray("fields") { constraint.keys.forEach { add(it.full) } }
            }
        }
    }

    /** 约束类型串，与 [PresetParser] 读取时用的字面量一致（presets/schema/preset-v1.schema.json）。 */
    private const val TYPE_DATETIME_ORDER = "datetimeOrder"
    private const val TYPE_MUTEX = "mutex"
    private const val TYPE_REQUIRES = "requires"
    private const val TYPE_CLAMP = "clamp"

    /** 供测试与调用方使用的 ISO 时刻格式化。 */
    fun moment(value: LocalDateTime): String = momentFormat.format(value)
}
