package com.pict.metatool.data.metadata

import android.content.ContentResolver
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue

/**
 * 元数据读取器契约（docs/07 T1.3、docs/02 §5）。
 *
 * 分工：一个 store 只管一类来源（ExifInterface / MetadataExtractor / CommonsImaging），
 * 由 [MetadataReader] 按格式路由并合并；store 之间不互相调用。
 *
 * 约定：
 * - 只读失败返回 [PictResult.Failure]，**不抛异常**；
 * - [supports] 必须是纯判断（不看文件内容，只看格式/扩展名），保证路由可预测；
 * - 读出的 [MetadataSet] 不做字段裁剪，缺什么由 UI 层按 FieldCatalog 决定展示。
 */
interface MetadataStore {

    /** 稳定标识，写入日志与报告来源标注（如 `exif`、`extractor`、`imaging`）。 */
    val id: String

    fun supports(source: ImageSource): Boolean

    suspend fun read(resolver: ContentResolver, source: ImageSource): PictResult<MetadataSet>
}

/**
 * 多来源合并（docs/07 T1.3）。
 *
 * 合并顺序即优先级：**后面的覆盖前面的**。调用方按「低优先 → 高优先」传入，
 * 例如 ExifInterface（权威 EXIF）放在 MetadataExtractor（补充 IPTC/ICC）之后。
 */
object MetadataMerger {

    fun merge(sources: List<MetadataSet>, base: MetadataSet? = null): MetadataSet {
        val head = base ?: sources.firstOrNull() ?: MetadataSet.empty()
        return sources.drop(if (base == null) 1 else 0).fold(head) { acc, next -> acc.merge(next) }
    }

    /**
     * 差异报告：返回「仅 A 有 / 仅 B 有 / 值不同」三类键。
     * 写入校验（T2.4）与「导入对比」功能共用。
     */
    fun diff(a: MetadataSet, b: MetadataSet): MetadataDiff {
        val onlyA = a.entries.keys - b.entries.keys
        val onlyB = b.entries.keys - a.entries.keys
        val changed = a.entries.keys.intersect(b.entries.keys)
            .filter { a.entries[it] != b.entries[it] }
            .toSet()
        return MetadataDiff(onlyA, onlyB, changed)
    }
}

data class MetadataDiff(
    val onlyInFirst: Set<TagKey>,
    val onlyInSecond: Set<TagKey>,
    val changed: Set<TagKey>,
) {
    val isEmpty: Boolean get() = onlyInFirst.isEmpty() && onlyInSecond.isEmpty() && changed.isEmpty()

    val total: Int get() = onlyInFirst.size + onlyInSecond.size + changed.size

    /** 人读摘要，用于日志与任务报告。 */
    fun summary(): String = when {
        isEmpty -> "无差异"
        else -> "差异 ${total} 项（仅 A ${onlyInFirst.size} / 仅 B ${onlyInSecond.size} / 变更 ${changed.size}）"
    }
}

/** 便捷取值：按规范名取，取不到返回 null（不抛）。 */
fun MetadataSet.valueOrNull(fullKey: String): TagValue? =
    runCatching { entries[TagKey.of(fullKey)] }.getOrNull()
