package com.pict.metatool.data.job

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 把任务交给 WorkManager 的那一层（docs/07 T5.3）。
 *
 * 为什么一定要 WorkManager 而不是一个自建线程：批量写元数据是「用户按了开始、然后去看别的」的活，
 * 进程会被系统在后台杀掉、机器会进低内存、用户会锁屏。WorkManager 在这些情况下会替我们把
 * worker 重新拉起来，而自建线程没了就是没了——那才是批量功能真正的「不可用」。
 *
 * 一个任务 = 一条**唯一命名**的 work（`pict.batch.<jobId>`）：
 * 同一个 jobId 重复入队不会跑两遍（[ExistingWorkPolicy.KEEP]），
 * 取消也能精确落到某一个任务上（FR-29），而不是「把全体的批量任务都掐了」。
 */
object JobQueue {

    const val TAG = "pict.batch"
    const val NAME_PREFIX = "pict.batch."
    const val KEY_JOB_ID = "jobId"
    const val KEY_OUTCOME = "outcome"

    /** worker 返回值里的几种收尾（跑完了 / 被取消 / 被系统掐断）。 */
    const val OUTCOME_COMPLETED = "completed"
    const val OUTCOME_CANCELED = "canceled"
    const val OUTCOME_INTERRUPTED = "interrupted"
    const val OUTCOME_UNSTARTED = "unstarted"

    /** 通知栏「取消」按钮的 action（[JobCancelReceiver] 收）。 */
    const val CANCEL_ACTION = "com.pict.metatool.action.CANCEL_JOB"

    fun workName(jobId: String): String = "$NAME_PREFIX$jobId"

    fun jobIdOf(uniqueWorkName: String): String? =
        uniqueWorkName.takeIf { it.startsWith(NAME_PREFIX) }?.removePrefix(NAME_PREFIX)?.takeIf { it.isNotEmpty() }

    /** 入参只带任务 id：定义本体在盘上，`Data` 有大小上限，塞不进几百个 URI。 */
    fun inputData(jobId: String): Map<String, String> = mapOf(KEY_JOB_ID to jobId)

    fun request(context: Context, jobId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<JobWorker>()
            .setInputData(
                Data.Builder()
                    .putAll(inputData(jobId).mapValues { it.value as Any })
                    .build(),
            )
            // 存储吃紧时先别写：批量写的是一堆文件，机器快满了再上只会写坏
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG)
            .build()

    /** 入队（同 jobId 重复入队是幂等的，[ExistingWorkPolicy.KEEP]）。返回任务 id 便于调用方记账。 */
    fun enqueue(context: Context, jobId: String): String {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(jobId),
            ExistingWorkPolicy.KEEP,
            request(context, jobId),
        )
        return jobId
    }

    /** 取消一个任务（FR-29）。未开工的项会被记成 SKIPPED，见 `JobRunner`。 */
    fun cancel(context: Context, jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
    }

    /** 兜底：把所有批量任务都停下（比如用户从设置里「停掉全部」）。 */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    /** 观察某条 work 的状态（T5.6 进度页读它）。 */
    fun observe(context: Context, jobId: String): Flow<WorkInfo?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workName(jobId))
            .map { infos -> infos.firstOrNull() }
}

/** 任务 id 生成。单独拎出来是为了让「id 必须能当文件名」这件事有一个可测的落点。 */
object JobIds {

    fun newId(nowMillis: Long, nonce: String): String {
        val safe = nonce.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "0" }
        return "job-$nowMillis-$safe"
    }

    fun newId(nowMillis: Long): String =
        newId(nowMillis, UUID.randomUUID().toString().replace("-", "").take(8))
}
