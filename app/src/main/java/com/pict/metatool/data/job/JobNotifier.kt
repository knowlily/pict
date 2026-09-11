package com.pict.metatool.data.job

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.pict.metatool.R
import com.pict.metatool.app.MainActivity

/**
 * 任务通知与前台服务声明（docs/07 T5.3）。
 *
 * 批量任务要能「退到后台继续跑」，就得是前台服务：只有挂出一条常驻通知，
 * 系统才不会把进程当成可随时回收的后台任务。通知同时也是用户**唯一**能看到进度的窗口，
 * 所以它得说人话（[JobNotificationContent] 负责措辞，这里只管拼通知）。
 *
 * 文案统一从 `strings.xml` 取（[texts]），与其它界面文案一样能翻译、能改，
 * 而不是把中文写死在 `data/` 里。
 */
class JobNotifier(private val context: Context) {

    private val manager: NotificationManagerCompat = NotificationManagerCompat.from(context)

    /** 建一次就够了；每次开跑都调也无害（同 id 会覆盖）。 */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.job_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.job_channel_description)
            setShowBadge(false)
        }
        // createNotificationChannel 在 NotificationManager（系统服务）上，Compat 没有这个 API
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun foreground(label: String, snapshot: JobSnapshot): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            notification(label, snapshot),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

    fun notification(label: String, snapshot: JobSnapshot): Notification {
        val content = JobNotificationContent.of(label, snapshot, texts())
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_job_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setProgress(100, content.percent, content.indeterminate)
            .setOngoing(content.ongoing)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.job_action_cancel), cancelJob(snapshot.jobId))
            .build()
    }

    /** 任务收尾后把常驻通知撤掉（跑完那一刻 WorkManager 也会撤，这里管用户已经划掉之后的重画）。 */
    fun dismiss() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, pendingFlags())
    }

    private fun cancelJob(jobId: String): PendingIntent {
        // 意图的 action/extra 由 JobQueue 定义，别在这里再拼一遍（见 JobQueue.cancelIntent）
        val intent = JobQueue.cancelIntent(context, jobId)
        return PendingIntent.getBroadcast(context, REQUEST_CANCEL, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    /** 从资源取通知文案模板（默认值与 [JobNotificationTexts] 相同，测试用默认值即可）。 */
    private fun texts(): JobNotificationTexts {
        val defaults = JobNotificationTexts()
        fun get(resId: Int, fallback: String): String =
            runCatching { context.getString(resId) }.getOrDefault(fallback)
        return JobNotificationTexts(
            running = get(R.string.job_notify_running, defaults.running),
            done = get(R.string.job_notify_done, defaults.done),
            canceled = get(R.string.job_notify_canceled, defaults.canceled),
            queued = get(R.string.job_notify_queued, defaults.queued),
            succeeded = get(R.string.job_notify_succeeded, defaults.succeeded),
            failed = get(R.string.job_notify_failed, defaults.failed),
            verifyFailed = get(R.string.job_notify_verify_failed, defaults.verifyFailed),
            unsupported = get(R.string.job_notify_unsupported, defaults.unsupported),
            skipped = get(R.string.job_notify_skipped, defaults.skipped),
            current = get(R.string.job_notify_current, defaults.current),
            dryRun = get(R.string.job_notify_dry_run, defaults.dryRun),
        )
    }

    companion object {

        const val CHANNEL_ID = "pict.jobs"
        const val NOTIFICATION_ID = 8101

        private const val REQUEST_OPEN = 8101
        private const val REQUEST_CANCEL = 8102
    }
}
