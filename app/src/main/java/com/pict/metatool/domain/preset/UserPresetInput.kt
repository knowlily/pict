package com.pict.metatool.domain.preset

import com.pict.metatool.domain.model.TagKey
import java.time.LocalDate
import java.time.LocalDateTime

/** 编辑器里的一行：一个字段 + 用户输入的值文本（[key] 为 null = 还没选字段）。 */
data class UserFieldInput(val key: TagKey?, val valueText: String)

/**
 * 「自己加一个预设」的输入（编辑页/批量页共用的那一张表单）。
 *
 * 值文本的三条写法，UI 的提示行要照着说：
 * - `iPhone 16 Pro` —— 单个值，套用时照写（固定值）；
 * - `iPhone 16 Pro, iPhone 16 Pro Max` —— 逗号分开，套用/随机时抽一个（取值池）；
 * - `2026-01-01~2026-12-31` 或 `31.23~31.25` —— `~` 两端都能认出时间或数字时当**区间**。
 *
 * [preserved] 放编辑器表达不了的规则（坐标圆面、就近抖动、沿用原值）：
 * 编辑一个老预设时它们**原样保留**，不然「改个名字」会顺手把坐标字段洗掉。
 */
data class UserPresetInput(
    val id: String? = null,
    val name: String = "",
    val kind: PresetKind = PresetKind.DEVICE,
    val description: String = "",
    val rows: List<UserFieldInput> = emptyList(),
    val preserved: Map<TagKey, FieldRule> = emptyMap(),
) {

    val isNew: Boolean get() = id == null

    /** 表单的行操作（都是小拷贝，UI 直接 setState）。 */
    fun withRowKey(index: Int, key: TagKey): UserPresetInput =
        copy(rows = rows.mapIndexed { i, row -> if (i == index) row.copy(key = key) else row })

    fun withRowValue(index: Int, text: String): UserPresetInput =
        copy(rows = rows.mapIndexed { i, row -> if (i == index) row.copy(valueText = text) else row })

    fun addRow(): UserPresetInput =
        if (rows.size >= MAX_ROWS) this else copy(rows = rows + UserFieldInput(null, ""))

    fun removeRow(index: Int): UserPresetInput =
        copy(rows = rows.filterIndexed { i, _ -> i != index })

    /** 填完了的行（字段和值都非空）；行只开了一半不算错，直接忽略。 */
    fun filledRows(): List<UserFieldInput> =
        rows.filter { it.key != null && it.valueText.isNotBlank() }

    /** 返回第一条问题给 UI 显示；null = 可以保存。 */
    fun validate(): String? = when {
        name.isBlank() -> "给预设起个名字"
        name.length > MAX_NAME -> "名字最长 $MAX_NAME 个字"
        description.length > MAX_DESCRIPTION -> "说明最长 $MAX_DESCRIPTION 个字"
        rows.size > MAX_ROWS -> "字段最多 $MAX_ROWS 行"
        filledRows().isEmpty() -> "至少填一个字段（字段 + 值）"
        filledRows().any { ruleOf(it.valueText) == null } -> "有字段的值看不懂，改一下那行"
        else -> null
    }

    /** 转成可落盘/可套用的 [Preset]；[id] 由调用方（`PresetEditor.nextId`）给。 */
    fun toPreset(id: String): Preset {
        val fields = LinkedHashMap<TagKey, FieldRule>()
        preserved.forEach { (key, rule) -> fields[key] = rule }
        filledRows().forEach { row ->
            val key = row.key ?: return@forEach
            ruleOf(row.valueText)?.let { rule -> fields[key] = rule }
        }
        return Preset(
            id = id,
            kind = kind,
            name = name.trim(),
            description = description.trim().ifBlank { null },
            tags = listOf(kind.label),
            fields = fields,
            origin = PresetOrigin.USER,
        )
    }

    companion object {

        const val MAX_NAME = 60
        const val MAX_DESCRIPTION = 300
        const val MAX_ROWS = 40

        /** 值文本 → 规则；看不懂给 null（[validate] 会因此拦下保存）。 */
        fun ruleOf(text: String): FieldRule? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val tilde = trimmed.split('~')
            if (tilde.size == 2) {
                val left = tilde[0].trim()
                val right = tilde[1].trim()
                val from = moment(left)
                val to = moment(right)
                if (from != null && to != null) {
                    return FieldRule.DateTimeRule(minOf(from, to), maxOf(from, to))
                }
                val min = left.toDoubleOrNull()
                val max = right.toDoubleOrNull()
                if (min != null && max != null) {
                    return FieldRule.Range(minOf(min, max), maxOf(max, min), precisionOf(left, right))
                }
            }
            val values = valuesOf(trimmed)
            return when (values.size) {
                0 -> null
                1 -> FieldRule.Fixed(valueOf(values.first()))
                else -> FieldRule.Pool(values.map { PoolEntry(valueOf(it)) })
            }
        }

        /** 逗号 / 顿号 / 分号分开，去空白、去重、保序。 */
        fun valuesOf(text: String): List<String> =
            text.split(',', '，', '、', ';', '；')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /** 规则 → 值文本（供「编辑已有预设」回填）；编辑器表达不了的给 null。 */
        fun textOf(rule: FieldRule): String? = when (rule) {
            is FieldRule.Fixed -> textOf(rule.value)
            is FieldRule.Pool -> rule.candidates.joinToString(", ") { textOf(it.value) }
            is FieldRule.Range -> "${plain(rule.min)}~${plain(rule.max)}"
            is FieldRule.DateTimeRule ->
                "${PresetWriter.moment(rule.start)}~${PresetWriter.moment(rule.end)}"
            is FieldRule.Gps, is FieldRule.Jitter, is FieldRule.FromSource -> null
        }

        /** 已有预设 → 表单初值（固定值/池/区间/时间可编，坐标那些进 [preserved]）。 */
        fun from(preset: Preset): UserPresetInput {
            val rows = mutableListOf<UserFieldInput>()
            val preserved = LinkedHashMap<TagKey, FieldRule>()
            preset.fields.forEach { (key, rule) ->
                val text = textOf(rule)
                if (text == null) preserved[key] = rule else rows += UserFieldInput(key, text)
            }
            return UserPresetInput(
                id = preset.id,
                name = preset.name,
                kind = preset.kind,
                description = preset.description.orEmpty(),
                rows = rows,
                preserved = preserved,
            )
        }

        private fun textOf(value: PresetValue): String = when (value) {
            is PresetValue.Text -> value.text
            is PresetValue.Number -> value.literal
            PresetValue.Null -> ""
        }

        private fun valueOf(text: String): PresetValue =
            text.toDoubleOrNull()?.let { PresetValue.of(it) } ?: PresetValue.Text(text)

        /** 区间两端里小数位更多的那位决定精度（`31.23~31.25` → 2 位）。 */
        private fun precisionOf(vararg texts: String): Int =
            texts.maxOfOrNull { text -> text.substringAfter('.', "").length.coerceAtMost(6) } ?: 0

        private fun plain(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

        private fun moment(text: String): LocalDateTime? =
            runCatching { LocalDateTime.parse(text) }.getOrNull()
                ?: runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
    }
}
