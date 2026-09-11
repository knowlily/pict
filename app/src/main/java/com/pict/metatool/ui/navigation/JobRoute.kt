package com.pict.metatool.ui.navigation

import android.net.Uri

/**
 * 任务进度页路由（二级页面：进页面时收起底部导航，与详情页/编辑页/批量页同规格）。
 *
 * 只带一个任务 id。id 由 `JobIds.newId` 生成（字母数字加连字符），本身就安全；
 * 仍然编码一次，是为了「将来换了生成规则也不必回来改路由」——路由不该假设 id 长什么样。
 */
object JobRoute {

    const val ARG_JOB_ID: String = "jobId"

    /** NavHost 里注册用的模式串。 */
    const val PATTERN: String = "job/{$ARG_JOB_ID}"

    fun build(jobId: String): String = "job/" + Uri.encode(jobId)

    fun parse(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
}

/**
 * 任务报告页路由（二级页面）。
 *
 * 与进度页分成两条路由而不是一个页面里的两个 Tab：报告是「跑完之后看/导出」的东西，
 * 它能独立地出现在历史列表（T5.8）里，也能被分享出去——那是个独立的落脚点。
 */
object ReportRoute {

    const val ARG_JOB_ID: String = "jobId"

    const val PATTERN: String = "report/{$ARG_JOB_ID}"

    fun build(jobId: String): String = "report/" + Uri.encode(jobId)

    fun parse(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
}
