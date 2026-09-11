package com.pict.metatool.data.job

import com.pict.metatool.data.metadata.MetadataVerifyReport
import com.pict.metatool.domain.batch.seededFor
import com.pict.metatool.domain.job.ItemResult
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.plan.EditPlan

/**
 * 单项干活的**判断**部分（T5.3）：什么情况算不支持、写完读回来的结果算不算成功。
 *
 * 单独拎出来是因为这几条正是 FR-32 / FR-33 的落点，而它们在设备上很难复现
 * （「只读授权」「校验未通过」「读回失败」都要构造真文件才能撞上）。判断是纯的，
 * 于是可以在 JVM 上把每种结局都跑一遍；`BatchItemWorker` 只负责把 I/O 喂进来。
 */
object BatchItemRules {

    /**
     * 这一项的计划：按序号错开种子，与预览用的 `EditPlan.seededFor` 是同一个函数。
     * 于是「预览里第 3 张的拍摄时间」与「执行时第 3 张的拍摄时间」必然同值。
     */
    fun planFor(plan: EditPlan, index: Int): EditPlan = plan.seededFor(index)

    /**
     * 开工前的拦停。返回 null = 可以继续读文件。
     *
     * 判序与 `BatchPreviewer` 一致：**先看授权，再看格式**。只读却报「格式不支持」
     * 会把用户引到错的方向去（换格式没用，该重新授权）。
     */
    fun blockBeforeRead(writable: Boolean, format: ImageFormatHint, hasWriter: Boolean): ItemResult.Unsupported? = when {
        !writable -> ItemResult.Unsupported("来源是只读授权，写不了：${format.label}")
        !hasWriter -> ItemResult.Unsupported("${format.label} 不支持原地写元数据")
        else -> null
    }

    /**
     * 写完（或 dry-run 算完）之后怎么记账。
     *
     * FR-33：**读不回来 = 不算成功**。这里刻意把「读回失败」与「读回来了但对不上」
     * 都归 [ItemResult.VerifyFailed] 而不是 [ItemResult.Failed]——它不是「没写成」，
     * 是「写成了但证不了没问题」，重试也不会变好，别让重试白白再写一遍文件。
     */
    fun resultOf(changedKeys: Set<TagKey>, report: MetadataVerifyReport?): ItemResult = when {
        report == null -> ItemResult.VerifyFailed(detail = "写入后读回失败，无法校验", changedKeys = changedKeys)
        !report.isLossless -> ItemResult.VerifyFailed(detail = report.detail(), changedKeys = changedKeys)
        // 通过，但写入器事前声明丢过键：成功也要把这句话带上，别让「格式存不下」无声无息
        else -> ItemResult.Done(changedKeys = changedKeys, note = report.droppedNote)
    }

    /** dry-run 的收尾：只报「将会变哪些键」，一个字节都不落盘（FR-32）。 */
    fun dryRunResult(changedKeys: Set<TagKey>): ItemResult = ItemResult.Done(changedKeys = changedKeys)
}
