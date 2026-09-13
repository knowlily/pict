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
     *
     * [writesInPlace] 决定「授权」这一关要不要看：另存模式（false）只往源文件的旁边
     * 写一个新文件，源文件全程不开写通道 —— 只读授权照样能另存，于是这一关不拦。
     * 「格式有没有写通道」两档都要看：副本也存不下写不进去的键。
     *
     * 默认给 `true`（＝按老口径拦）是**保守方向**：调用方忘了传的时候宁可多拦几项，
     * 也不要拿着「以为能写」的判断去动用户的文件。应用里的唯一调用点显式传实际模式。
     */
    fun blockBeforeRead(
        writable: Boolean,
        format: ImageFormatHint,
        hasWriter: Boolean,
        writesInPlace: Boolean = true,
    ): ItemResult.Unsupported? = when {
        writesInPlace && !writable -> ItemResult.Unsupported("来源是只读授权，写不了：${format.label}")
        !hasWriter -> ItemResult.Unsupported(
            // 「原地」两个字只在真会动源文件的时候才说：另存模式下提「原地」，
            // 用户会以为源文件被弄坏了——其实压根没碰它
            if (writesInPlace) {
                "${format.label} 不支持原地写元数据"
            } else {
                "${format.label} 不支持写元数据（另存副本也改不了，得重编码）"
            },
        )
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

    /**
     * 另存模式的收尾：判定与 [resultOf] **完全一致**（同一份 FR-33 口径，不做第二套），
     * 只是把「落到哪个新文件」一并带上——报告要能告诉用户副本在哪儿，
     * 否则「改好了但不知道存哪去了」比失败还让人着急。
     *
     * 校验未通过时也把副本名字写进 detail：文件已经建出来了，用户得知道去哪儿看（或删），
     * 别让一个可能是半成品的新文件无声无息躺在相册里。
     */
    fun resultOfCopy(
        changedKeys: Set<TagKey>,
        report: MetadataVerifyReport?,
        outputUri: String,
        copyName: String,
    ): ItemResult = when (val base = resultOf(changedKeys, report)) {
        is ItemResult.Done -> base.copy(
            outputUri = outputUri,
            note = noteOf("另存成 $copyName", base.note),
        )

        is ItemResult.VerifyFailed -> base.copy(detail = noteOf(base.detail, "副本已留在 $copyName"))
        else -> base
    }

    /** 把几句话连成一句（丢掉空的、去重后按顺序连）；一句都没有就是 null。 */
    private fun noteOf(vararg parts: String?): String? =
        parts.filterNotNull().filter { it.isNotBlank() }.distinct().joinToString("；").ifBlank { null }
}
