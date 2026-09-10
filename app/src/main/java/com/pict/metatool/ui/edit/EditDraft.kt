package com.pict.metatool.ui.edit

import com.pict.metatool.domain.format.TagValueFormatter
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditOutcome
import com.pict.metatool.domain.plan.EditPlan

/**
 * 单文件编辑的草稿模型（docs/07 T2.10）。
 *
 * 为什么要有草稿：docs/06 §3.3 要求「改动字段高亮未保存 + 返回时提示有几项未保存」，
 * 也就是编辑页**不能边改边落盘**。草稿攒够了一次性折叠成 `EditPlan` 交给写通道，
 * 用户在返回前随时能整体放弃。
 *
 * 纯数据 + 纯函数，无 Android 依赖，JVM 单测直接覆盖。
 */
sealed interface DraftValue {

    data class Value(val value: TagValue) : DraftValue

    /** 清除该字段（输入框留空 = 清除）。 */
    data object Cleared : DraftValue
}

data class EditDraft(val entries: Map<TagKey, DraftValue> = emptyMap()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    val size: Int get() = entries.size

    val keys: Set<TagKey> get() = entries.keys

    fun contains(key: TagKey): Boolean = entries.containsKey(key)

    fun of(key: TagKey): DraftValue? = entries[key]

    /** 草稿里该字段当前显示的值（清除时为 null）。 */
    fun valueOf(key: TagKey): TagValue? = (entries[key] as? DraftValue.Value)?.value

    fun set(key: TagKey, value: TagValue): EditDraft =
        copy(entries = entries + (key to DraftValue.Value(value)))

    fun clear(key: TagKey): EditDraft = copy(entries = entries + (key to DraftValue.Cleared))

    /**
     * 批量写入（套用预设 / 随机填充的结果一次性进草稿）。
     *
     * 用「并入」而不是「替换」：用户手改过的那几项不该被一次批量填充抹掉——
     * 批量填充只负责它算出来的键。
     */
    fun setAll(values: Map<TagKey, TagValue>): EditDraft =
        if (values.isEmpty()) this else copy(entries = entries + values.mapValues { DraftValue.Value(it.value) })

    /** 撤销单个字段的改动，回到源文件的值。 */
    fun revert(key: TagKey): EditDraft = copy(entries = entries - key)

    /** 全部放弃。 */
    fun revertAll(): EditDraft = EditDraft()

    /**
     * 与源比较后**真正生效**的键。
     *
     * 草稿值等于源值 = 用户改了又改回来，不该算「未保存」，也不该写盘。
     * 这条规则让「应用 N」上的 N 与真实改动一致。
     */
    fun effectiveKeys(source: MetadataSet): Set<TagKey> = entries
        .filter { (key, draft) ->
            when (draft) {
                is DraftValue.Value -> source[key] != draft.value
                DraftValue.Cleared -> source[key] != null
            }
        }
        .keys

    fun effectiveCount(source: MetadataSet): Int = effectiveKeys(source).size

    /**
     * 折叠成操作序列。
     * 顺序按字段目录（未登记键排最后，再按命名空间/名称），保证同一份草稿每次折叠结果一致。
     */
    fun toOperations(): List<EditOperation> = entries.entries
        .sortedWith(
            compareBy(
                { orderOf(it.key) },
                { it.key.namespace },
                { it.key.name },
            ),
        )
        .map { (key, draft) ->
            when (draft) {
                is DraftValue.Value -> EditOperation.SetField(key, draft.value)
                DraftValue.Cleared -> EditOperation.ClearField(key)
            }
        }

    fun toPlan(dryRun: Boolean = true, backupBeforeOverwrite: Boolean = true): EditPlan = EditPlan(
        operations = toOperations(),
        dryRun = dryRun,
        backupBeforeOverwrite = backupBeforeOverwrite,
    )

    companion object {
        val EMPTY: EditDraft = EditDraft()
    }
}

/** 变更类型（diff 预览的一行）。 */
enum class EditDiffKind(val label: String) {
    ADDED("新增"),
    CHANGED("修改"),
    CLEARED("清除"),
}

/**
 * diff 预览的一行：`原值 → 新值`（docs/06 §3.3「显示将写入的字段 diff」）。
 *
 * @param before 源文件里的展示值；新增字段为 null
 * @param after 目标值的展示值；清除字段为 null
 */
data class EditDiffRow(
    val key: TagKey,
    val label: String,
    val before: String?,
    val after: String?,
    val kind: EditDiffKind,
) {

    /** 无 UI 也能读懂的一行文本（日志、测试断言、无障碍朗读）。 */
    val summary: String
        get() = when (kind) {
            EditDiffKind.ADDED -> "$label：新增 $after"
            EditDiffKind.CLEARED -> "$label：清除原值 $before"
            EditDiffKind.CHANGED -> "$label：$before → $after"
        }
}

/** 把折叠结果翻译成可展示的 diff 行。 */
object EditDiff {

    fun rows(source: MetadataSet, outcome: EditOutcome): List<EditDiffRow> =
        outcome.changedKeys
            .sortedWith(
                compareBy(
                    { orderOf(it) },
                    { it.namespace },
                    { it.name },
                ),
            )
            .map { key ->
                val spec = FieldCatalog.spec(key)
                val before = source[key]?.let { TagValueFormatter.format(it, spec) }
                val after = outcome.target[key]?.let { TagValueFormatter.format(it, spec) }
                val kind = when {
                    before == null -> EditDiffKind.ADDED
                    after == null -> EditDiffKind.CLEARED
                    else -> EditDiffKind.CHANGED
                }
                EditDiffRow(
                    key = key,
                    label = spec?.label ?: key.full,
                    before = before,
                    after = after,
                    kind = kind,
                )
            }
}

/**
 * 字段目录顺序（未登记键返回 [Int.MAX_VALUE]，排在最后）。
 * 目录是唯一事实源，UI 分组与排序都从它派生；这里不重复维护一张顺序表。
 */
internal val catalogOrder: Map<TagKey, Int> by lazy {
    FieldCatalog.all.withIndex().associate { (index, spec) -> spec.key to index }
}

internal fun orderOf(key: TagKey): Int = catalogOrder[key] ?: Int.MAX_VALUE
