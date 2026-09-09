package com.pict.metatool.data.metadata

import android.content.ContentResolver
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.source.ImageSource
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey

/**
 * 元数据写入器契约（docs/07 T2.1、docs/02 §5）。
 *
 * 与只读的 [MetadataStore] 对称：一个 writer 只管一种落盘方式
 * （ExifInterface 重写块 / CommonsImaging 无损重写），由上层按格式路由，
 * writer 之间不互相调用。
 *
 * 约定：
 * - 写入失败返回 [PictResult.Failure]，**不抛异常**；
 * - [canWrite] 必须是纯判断（只看 [SourceInfo]，不读文件内容），保证路由可预测。
 *   刻意不叫 `supports`：读写能力并不重合（HEIF 能读不能原地写），同名会让
 *   同时实现 [MetadataStore] 与 [MetadataWriter] 的类没法表达两种答案；
 * - [write] 的入参是**目标全量元数据**（由 T2.2 折叠操作序列得到），不是增量补丁：
 *   与源文件的差异由实现方自行计算；
 * - 不支持的格式要提前暴露：HEIF 无法原地写（docs/02 §5），对应实现的
 *   [canWrite] 应返回 false，由上层提示「需重编码」，而不是写坏了再报错。
 */
interface MetadataWriter {

    /** 稳定标识，写入日志与报告（如 `exif`、`imaging`）。 */
    val id: String

    /** 该写入器是否能处理这个来源（按 [SourceInfo.format] 判断，纯数据、可单测）。 */
    fun canWrite(info: SourceInfo): Boolean

    suspend fun write(
        resolver: ContentResolver,
        source: ImageSource,
        target: MetadataSet,
    ): PictResult<WriteResult>
}

/**
 * 写入结果（docs/07 T2.1）。
 *
 * @param writtenKeys 真正落到文件里的键
 * @param droppedKeys 因格式限制或段大小上限被丢弃的键（丢弃原因与策略见 T2.5）；
 *   与 [writtenKeys] 不相交
 */
data class WriteResult(
    val writtenKeys: Set<TagKey>,
    val droppedKeys: Set<TagKey> = emptySet(),
) {

    val hasDropped: Boolean get() = droppedKeys.isNotEmpty()

    /** 日志/报告用的一行摘要。 */
    fun summary(): String =
        if (hasDropped) "写入 ${writtenKeys.size} 项，丢弃 ${droppedKeys.size} 项"
        else "写入 ${writtenKeys.size} 项"
}
