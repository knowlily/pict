package com.pict.metatool.data.job

import com.pict.metatool.domain.job.JobStatus

/**
 * 通知栏要说的话（T5.3）。纯数据 + 纯函数：措辞属于可测逻辑，
 * 不该埋在 `NotificationCompat.Builder` 的调用链里——那样只能靠真机肉眼验。
 *
 * 文案走 [JobNotificationTexts] 传入而不是写死，是为了两件事都成立：
 * 界面文案仍归 `strings.xml`（[fromResources]），单测不必依赖 Android 资源。
 */
data class JobNotificationContent(
    val title: String,
    val text: String,
    val percent: Int,
    val indeterminate: Boolean,
    val ongoing: Boolean,
) {

    companion object {

        fun of(
            label: String,
            snapshot: JobSnapshot,
            texts: JobNotificationTexts = JobNotificationTexts(),
        ): JobNotificationContent {
            val head = when (snapshot.status) {
                JobStatus.CANCELED -> texts.canceled
                JobStatus.COMPLETED -> texts.done
                JobStatus.PENDING -> texts.queued
                JobStatus.RUNNING -> texts.running.format(snapshot.finished, snapshot.total)
            }
            val parts = buildList {
                add(texts.succeeded.format(snapshot.succeeded))
                if (snapshot.failed > 0) add(texts.failed.format(snapshot.failed))
                if (snapshot.verifyFailed > 0) add(texts.verifyFailed.format(snapshot.verifyFailed))
                if (snapshot.unsupported > 0) add(texts.unsupported.format(snapshot.unsupported))
                if (snapshot.skipped > 0) add(texts.skipped.format(snapshot.skipped))
                if (snapshot.status == JobStatus.RUNNING && snapshot.currentName != null) {
                    add(texts.current.format(snapshot.currentName))
                }
            }
            val prefix = if (snapshot.dryRun) "${texts.dryRun} · " else ""
            return JobNotificationContent(
                title = label,
                text = prefix + head + "：" + parts.joinToString(" · "),
                percent = snapshot.percent,
                indeterminate = snapshot.total <= 0 && !snapshot.isTerminal,
                // 跑着的时候不能划掉：任务还在动，通知是它唯一可见的痕迹
                ongoing = !snapshot.isTerminal,
            )
        }
    }
}

/** 通知文案模板（`%1$d` 这类占位符与 `strings.xml` 里的一致）。 */
data class JobNotificationTexts(
    // 注意 `\$`：Kotlin 会把 `$d` 当成模板变量，这里要的是给 String.format 用的字面量
    val running: String = "已处理 %1\$d / %2\$d",
    val done: String = "已完成",
    val canceled: String = "已取消",
    val queued: String = "排队中",
    val succeeded: String = "成功 %1\$d",
    val failed: String = "失败 %1\$d",
    val verifyFailed: String = "校验未通过 %1\$d",
    val unsupported: String = "不支持 %1\$d",
    val skipped: String = "已跳过 %1\$d",
    val current: String = "正在处理 %1\$s",
    val dryRun: String = "只算不写",
)

/**
 * 进度通知的节流（T5.3）。
 *
 * `JobRunner` 每有一项状态变化就发一次快照，500 张图 = 上千次通知更新；
 * 系统对通知更新有频率限制，更新太密还会把前台服务本身拖慢。所以定一条规则：
 * 首帧必发、状态变化必发、终态必发、其余每 [MIN_INTERVAL_MILLIS] 最多一条。
 */
object JobProgressThrottle {

    const val MIN_INTERVAL_MILLIS = 1_000L

    fun shouldPost(
        previous: JobSnapshot?,
        next: JobSnapshot,
        lastPostedAtMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (previous == null) return true
        if (next.isTerminal || previous.isTerminal) return true
        if (next.status != previous.status) return true
        if (next.finished == previous.finished && next.currentName == previous.currentName) return false
        return nowMillis - lastPostedAtMillis >= MIN_INTERVAL_MILLIS
    }
}
