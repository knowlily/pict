package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.TagKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 预设 JSON → [Preset]（docs/07 T3.1、docs/03 §8 校验顺序）。
 *
 * 校验策略：
 * - 按 docs/03 §8 的顺序走「JSON 语法 → 结构 → 取值」，一次报出**全部**问题而不是遇见第一个就退出，
 *   否则作者要改十几轮；
 * - 每条问题带点分路径（`fields.EXIF:FNumber.min`）并在多文件加载时前缀文件名，便于直接定位；
 * - 「[FieldCatalog] 未登记的字段」只记警告并保留：目录是白名单，但预设允许带未登记字段，
 *   写入时由随机填充器跳过并汇报（docs/03 §8「未知 Key 记警告并保留」）。
 *
 * 本文件属 domain 层，不依赖任何 Android API；JSON 解析用 kotlinx-serialization 的
 * `JsonElement` 树 API，只按需取值、不生成代码，因此**不需要**序列化编译器插件。
 */
object PresetParser {

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
    private val KEY_PATTERN = Regex("^(EXIF|GPS|XMP|IPTC):.+")
    private val KEY_NAMESPACES = listOf("EXIF", "GPS", "XMP", "IPTC")

    private const val MAX_POOL = 64
    private const val MAX_TAGS = 10
    private const val MAX_TAG_LENGTH = 20
    private const val MAX_NAME = 60
    private const val MAX_DESCRIPTION = 300
    private const val MAX_AUTHOR = 60
    private const val MAX_LICENSE = 40
    private const val MAX_PRECISION = 6
    private const val MAX_RADIUS_METERS = 50_000.0
    private const val MAX_PERCENT = 1.0

    private val DATETIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy:MM:dd HH:mm:ss",
    )

    /**
     * @param json 预设文件内容
     * @param origin 来源标记（内置 / 用户 / 导入），只影响展示
     * @param file 文件名，仅用于错误路径前缀；不参与解析
     */
    fun parse(
        json: String,
        origin: PresetOrigin = PresetOrigin.BUILTIN,
        file: String? = null,
    ): PresetParseResult {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrElse { error ->
            return PresetParseResult.Invalid(
                listOf(PresetIssue(path(file, "$"), "JSON 语法错误：${error.message ?: "无法解析"}")),
            )
        }
        val obj = root as? JsonObject
            ?: return PresetParseResult.Invalid(
                listOf(PresetIssue(path(file, "$"), "根节点必须是对象")),
            )

        val issues = mutableListOf<PresetIssue>()
        val warnings = mutableListOf<PresetIssue>()
        val error: (String, String) -> Unit = { p, m -> issues += PresetIssue(path(file, p), m) }
        val warn: (String, String) -> Unit = { p, m ->
            warnings += PresetIssue(path(file, p), m, PresetIssueLevel.WARNING)
        }

        // ---- schemaVersion ----
        val version = obj["schemaVersion"].asDoubleOrNull()
        when {
            version == null -> error("schemaVersion", "缺少或不是数值")
            version > Preset.SCHEMA_VERSION ->
                error("schemaVersion", "预设版本 ${version.toInt()} 高于当前支持的 ${Preset.SCHEMA_VERSION}，请升级 App")
            version < Preset.SCHEMA_VERSION ->
                error("schemaVersion", "预设版本 ${version.toInt()} 低于当前支持的 ${Preset.SCHEMA_VERSION}，无法读取")
        }

        // ---- id / kind / name ----
        val id = obj["id"].asStringOrNull()
        when {
            id == null -> error("id", "缺少或不是字符串")
            !ID_PATTERN.matches(id) -> error("id", "必须匹配 ^[a-z0-9][a-z0-9._-]{2,63}$，实际 \"$id\"")
        }

        val kindText = obj["kind"].asStringOrNull()
        val kind = kindText?.let { PresetKind.fromId(it) }
        if (kind == null) {
            error("kind", "未知类别 \"${kindText ?: ""}\"（支持：${PresetKind.ids.joinToString("/")}）")
        }

        val name = obj["name"].asStringOrNull()
        when {
            name.isNullOrBlank() -> error("name", "缺少或为空")
            name.length > MAX_NAME -> error("name", "长度 ${name.length} 超过 $MAX_NAME")
        }

        val description = obj["description"].asStringOrNull()
        if (description != null && description.length > MAX_DESCRIPTION) {
            error("description", "长度 ${description.length} 超过 $MAX_DESCRIPTION")
        }

        val tags = mutableListOf<String>()
        obj["tags"]?.let { element ->
            val array = element as? JsonArray
            if (array == null) {
                error("tags", "必须是字符串数组")
            } else {
                if (array.size > MAX_TAGS) error("tags", "条目数 ${array.size} 超过 $MAX_TAGS")
                array.forEachIndexed { index, item ->
                    val tag = item.asStringOrNull()
                    when {
                        tag == null -> error("tags[$index]", "必须是字符串")
                        tag.length > MAX_TAG_LENGTH -> error("tags[$index]", "长度 ${tag.length} 超过 $MAX_TAG_LENGTH")
                        else -> tags += tag
                    }
                }
            }
        }

        val author = obj["author"].asStringOrNull()
        if (author != null && author.length > MAX_AUTHOR) {
            error("author", "长度 ${author.length} 超过 $MAX_AUTHOR")
        }
        val license = obj["license"].asStringOrNull()
        if (license != null && license.length > MAX_LICENSE) {
            error("license", "长度 ${license.length} 超过 $MAX_LICENSE")
        }

        // ---- fields ----
        val fields = linkedMapOf<TagKey, FieldRule>()
        when (val element = obj["fields"]) {
            null -> error("fields", "缺少字段定义")
            !is JsonObject -> error("fields", "必须是对象")
            else -> {
                if (element.isEmpty()) error("fields", "至少要有一个字段")
                for ((rawKey, fieldElement) in element) {
                    val parsed = parseField(rawKey, fieldElement, error, warn) ?: continue
                    fields[parsed.first] = parsed.second
                }
            }
        }

        // ---- constraints ----
        val constraints = mutableListOf<PresetConstraint>()
        obj["constraints"]?.let { element ->
            val array = element as? JsonArray
            if (array == null) {
                error("constraints", "必须是数组")
            } else {
                array.forEachIndexed { index, item ->
                    parseConstraint(index, item, error)?.let { constraints += it }
                }
            }
        }

        if (issues.isNotEmpty()) return PresetParseResult.Invalid(issues)

        return PresetParseResult.Success(
            preset = Preset(
                id = id!!,
                kind = kind!!,
                name = name!!,
                description = description,
                tags = tags,
                author = author,
                license = license,
                fields = fields,
                constraints = constraints,
                origin = origin,
            ),
            warnings = warnings,
        )
    }

    private fun parseField(
        rawKey: String,
        element: JsonElement,
        error: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ): Pair<TagKey, FieldRule>? {
        val fieldPath = "fields.$rawKey"
        if (!KEY_PATTERN.matches(rawKey)) {
            error(fieldPath, "字段键必须形如 命名空间:名称，命名空间取 ${KEY_NAMESPACES.joinToString("/")}")
            return null
        }
        val key = TagKey.of(rawKey)
        if (FieldCatalog.spec(key) == null) {
            warn(fieldPath, "字段目录未登记该字段，写入时会被跳过并汇报")
        }

        val obj = element as? JsonObject
        if (obj == null) {
            error(fieldPath, "必须是对象")
            return null
        }

        val modeText = obj["mode"].asStringOrNull()
        val mode = modeText?.let { FieldMode.fromId(it) }
        if (mode == null) {
            error("$fieldPath.mode", "未知模式 \"${modeText ?: ""}\"（支持：${FieldMode.ids.joinToString("/")}）")
            return null
        }

        val rule = when (mode) {
            FieldMode.FIXED -> fixedRule(obj, fieldPath, error)
            FieldMode.POOL -> poolRule(obj, fieldPath, error)
            FieldMode.RANGE -> rangeRule(obj, fieldPath, error)
            FieldMode.DATETIME -> datetimeRule(obj, fieldPath, error)
            FieldMode.GPS -> gpsRule(obj, fieldPath, error)
            FieldMode.FROM_SOURCE -> fromSourceRule(key, obj, fieldPath, error)
            FieldMode.JITTER -> jitterRule(obj, fieldPath, error)
        } ?: return null
        return key to rule
    }

    private fun fixedRule(
        obj: JsonObject,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): FieldRule? {
        val value = obj["value"]?.toPresetValue()
        if (value == null) {
            error("$fieldPath.value", "fixed 模式必须给出 value")
            return null
        }
        return FieldRule.Fixed(value)
    }

    private fun poolRule(
        obj: JsonObject,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): FieldRule? {
        val array = obj["pool"] as? JsonArray
        if (array == null) {
            error("$fieldPath.pool", "pool 模式必须给出数组 pool")
            return null
        }
        if (array.isEmpty()) {
            error("$fieldPath.pool", "取值池不能为空")
            return null
        }
        if (array.size > MAX_POOL) {
            error("$fieldPath.pool", "条目数 ${array.size} 超过 $MAX_POOL")
            return null
        }
        val entries = mutableListOf<PoolEntry>()
        array.forEachIndexed { index, item ->
            val entryPath = "$fieldPath.pool[$index]"
            // 允许两种写法：`{ "value": …, "weight": … }` 与裸字面量（权重按 1 计）
            val entryObj = item as? JsonObject
            if (entryObj == null) {
                val bare = item.toPresetValue()
                if (bare == null) {
                    error(entryPath, "必须是 { value, weight } 对象或字面量")
                } else {
                    entries += PoolEntry(bare)
                }
                return@forEachIndexed
            }
            val value = entryObj["value"]?.toPresetValue()
            if (value == null) {
                error("$entryPath.value", "缺少取值")
                return@forEachIndexed
            }
            val weight = entryObj["weight"].asDoubleOrNull() ?: 1.0
            if (!weight.isFinite() || weight <= 0.0) {
                error("$entryPath.weight", "权重必须是正数，实际 $weight")
                return@forEachIndexed
            }
            entries += PoolEntry(value, weight)
        }
        if (entries.isEmpty()) {
            error("$fieldPath.pool", "取值池里没有有效条目")
            return null
        }
        return FieldRule.Pool(entries)
    }

    private fun rangeRule(
        obj: JsonObject,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): FieldRule? {
        val min = obj["min"].asDoubleOrNull()
        val max = obj["max"].asDoubleOrNull()
        if (min == null) error("$fieldPath.min", "range 模式必须给出数值 min")
        if (max == null) error("$fieldPath.max", "range 模式必须给出数值 max")
        if (min == null || max == null) return null
        if (min > max) {
            error("$fieldPath.min", "必须 ≤ max（min=$min, max=$max）")
            return null
        }
        val precisionElement = obj["precision"]
        val precision = precisionElement?.asIntOrNull() ?: 2
        if (precisionElement != null && precisionElement.asIntOrNull() == null) {
            error("$fieldPath.precision", "必须是整数")
            return null
        }
        if (precision !in 0..MAX_PRECISION) {
            error("$fieldPath.precision", "必须在 0..$MAX_PRECISION，实际 $precision")
            return null
        }
        return FieldRule.Range(min, max, precision)
    }

    private fun datetimeRule(
        obj: JsonObject,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): FieldRule? {
        val startText = obj["start"].asStringOrNull()
        val endText = obj["end"].asStringOrNull()
        if (startText == null) error("$fieldPath.start", "datetime 模式必须给出字符串 start")
        if (endText == null) error("$fieldPath.end", "datetime 模式必须给出字符串 end")
        if (startText == null || endText == null) return null
        val start = parseMoment(startText)
        val end = parseMoment(endText)
        if (start == null) error("$fieldPath.start", "无法解析时间：\"$startText\"")
        if (end == null) error("$fieldPath.end", "无法解析时间：\"$endText\"")
        if (start == null || end == null) return null
        if (!start.isBefore(end)) {
            error("$fieldPath.start", "必须早于 end（start=$startText, end=$endText）")
            return null
        }
        val hourWeights = weightsOf(obj, "hourWeights", 24, fieldPath, error)
        val minuteWeights = weightsOf(obj, "minuteWeights", 60, fieldPath, error)
        return FieldRule.DateTimeRule(start, end, hourWeights, minuteWeights)
    }

    private fun weightsOf(
        obj: JsonObject,
        name: String,
        expectedSize: Int,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): List<Double>? {
        val element = obj[name] ?: return null
        val array = element as? JsonArray
        if (array == null) {
            error("$fieldPath.$name", "必须是数值数组")
            return null
        }
        if (array.size != expectedSize) {
            error("$fieldPath.$name", "必须正好 $expectedSize 个权重，实际 ${array.size}")
            return null
        }
        val weights = array.map { it.asDoubleOrNull() }
        if (weights.any { it == null || !it.isFinite() || it < 0.0 }) {
            error("$fieldPath.$name", "权重必须是非负数值")
            return null
        }
        if (weights.all { it == 0.0 }) {
            error("$fieldPath.$name", "权重不能全为 0")
            return null
        }
        return weights.filterNotNull()
    }

    /**
     * gps 模式：`{ "lat": 31.2304, "lon": 121.4737, "radiusMeters": 5000 }`
     * （字段名以 `presets/schema/preset-v1.schema.json` 为准）。
     * 同时接受 `latitude` / `longitude` 这两种写全的写法，方便手写预设时少踩坑。
     */
    private fun gpsRule(
        obj: JsonObject,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): FieldRule? {
        val latitudeElement = obj["lat"] ?: obj["latitude"]
        val longitudeElement = obj["lon"] ?: obj["longitude"]
        val latitude = latitudeElement.asDoubleOrNull()
        val longitude = longitudeElement.asDoubleOrNull()
        val radius = obj["radiusMeters"].asDoubleOrNull()
        if (latitude == null) error("$fieldPath.lat", "gps 模式必须给出数值 lat")
        if (longitude == null) error("$fieldPath.lon", "gps 模式必须给出数值 lon")
        if (radius == null) error("$fieldPath.radiusMeters", "gps 模式必须给出数值 radiusMeters")
        if (latitude == null || longitude == null || radius == null) return null
        if (latitude !in -90.0..90.0) error("$fieldPath.lat", "必须在 -90..90，实际 $latitude")
        if (longitude !in -180.0..180.0) error("$fieldPath.lon", "必须在 -180..180，实际 $longitude")
        if (radius < 0.0 || radius > MAX_RADIUS_METERS) {
            error("$fieldPath.radiusMeters", "必须在 0..$MAX_RADIUS_METERS 米，实际 $radius")
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            radius < 0.0 || radius > MAX_RADIUS_METERS
        ) {
            return null
        }
        return FieldRule.Gps(latitude, longitude, radius)
    }

    private fun fromSourceRule(
        key: TagKey,
        obj: JsonObject,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): FieldRule? {
        val sourceText = obj["sourceKey"].asStringOrNull() ?: return FieldRule.FromSource(key)
        if (!KEY_PATTERN.matches(sourceText)) {
            error("$fieldPath.sourceKey", "必须是 命名空间:名称 形式，实际 \"$sourceText\"")
            return null
        }
        return FieldRule.FromSource(TagKey.of(sourceText))
    }

    private fun jitterRule(
        obj: JsonObject,
        fieldPath: String,
        error: (String, String) -> Unit,
    ): FieldRule? {
        val radiusElement = obj["radiusMeters"]
        val radius = radiusElement?.asDoubleOrNull()
        if (radiusElement != null && radius == null) {
            error("$fieldPath.radiusMeters", "必须是数值")
            return null
        }
        if (radius != null && (radius < 0.0 || radius > MAX_RADIUS_METERS)) {
            error("$fieldPath.radiusMeters", "必须在 0..$MAX_RADIUS_METERS 米，实际 $radius")
            return null
        }
        val percentElement = obj["percent"]
        val percent = percentElement?.asDoubleOrNull() ?: 0.05
        if (percentElement != null && percentElement.asDoubleOrNull() == null) {
            error("$fieldPath.percent", "必须是数值")
            return null
        }
        if (percent <= 0.0 || percent > MAX_PERCENT) {
            error("$fieldPath.percent", "必须在 (0, $MAX_PERCENT]，实际 $percent")
            return null
        }
        return FieldRule.Jitter(radius, percent)
    }

    private fun parseConstraint(
        index: Int,
        element: JsonElement,
        error: (String, String) -> Unit,
    ): PresetConstraint? {
        val path = "constraints[$index]"
        val obj = element as? JsonObject
        if (obj == null) {
            error(path, "必须是对象")
            return null
        }
        val type = obj["type"].asStringOrNull()
        val keys = mutableListOf<TagKey>()
        val fieldsElement = obj["fields"]
        val array = fieldsElement as? JsonArray
        when {
            fieldsElement == null -> error("$path.fields", "缺少字段列表")
            array == null -> error("$path.fields", "必须是字符串数组")
            array.isEmpty() -> error("$path.fields", "至少要有一个字段")
            else -> array.forEachIndexed { fieldIndex, item ->
                val text = item.asStringOrNull()
                when {
                    text == null -> error("$path.fields[$fieldIndex]", "必须是字符串")
                    !KEY_PATTERN.matches(text) -> error("$path.fields[$fieldIndex]", "字段键不合法：\"$text\"")
                    else -> keys += TagKey.of(text)
                }
            }
        }
        if (keys.isEmpty()) return null
        return when (type) {
            "datetimeOrder" -> {
                if (keys.size < 2) {
                    error("$path.fields", "时间先后约束至少需要两个字段")
                    null
                } else {
                    PresetConstraint.DatetimeOrder(keys)
                }
            }
            "mutex" -> PresetConstraint.Mutex(keys)
            "requires" -> PresetConstraint.Requires(keys)
            "clamp" -> PresetConstraint.Clamp(keys)
            else -> {
                error("$path.type", "未知约束类型 \"${type ?: ""}\"（支持：datetimeOrder/mutex/requires/clamp）")
                null
            }
        }
    }

    private fun parseMoment(text: String): LocalDateTime? {
        DATETIME_PATTERNS.forEach { pattern ->
            runCatching { LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern)) }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
    }

    private fun path(file: String?, inner: String): String =
        if (file.isNullOrBlank()) inner else "$file → $inner"
}

/** 字符串字面量：只认带引号的 JSON 字符串，数字不冒充文本。 */
private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 数值：带引号的数字也接受（预设是人写的，宽容一点比报错有用）。 */
private fun JsonElement?.asDoubleOrNull(): Double? =
    (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun JsonElement?.asIntOrNull(): Int? =
    asDoubleOrNull()?.let { if (it == it.toInt().toDouble()) it.toInt() else null }

/** JSON 值 → [PresetValue]；数组/对象不是合法字面量，返回 null。 */
private fun JsonElement.toPresetValue(): PresetValue? = when (this) {
    is JsonPrimitive -> when {
        isString -> PresetValue.Text(content)
        content == "null" -> PresetValue.Null
        content.toDoubleOrNull() != null -> PresetValue.Number(content.toDouble(), content)
        content == "true" || content == "false" -> PresetValue.Text(content)
        else -> null
    }
    else -> null
}
