package com.pict.metatool.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.job.JobCancelReceiver
import com.pict.metatool.data.job.JobIds
import com.pict.metatool.data.job.JobQueue
import com.pict.metatool.data.job.JobSnapshotStore
import com.pict.metatool.data.job.JobSpecStore
import kotlin.concurrent.thread

/**
 * 只在 debug 包里存在的验收探针，**不随正式包发布**（T5.3 / FR-29）。
 *
 * 为什么要有它：通知栏那颗「取消」按钮要生效，得走
 * `PendingIntent → [JobCancelReceiver] → [JobQueue.cancel] → WorkManager` 一串。
 * 而 receiver 是 `exported="false"`（只有自家通知能触发它，这是对的），
 * 再加上模拟器（MuMu）会把从 adb 发来、目标是本包名的广播直接拦掉——
 * 于是**从 adb 那头没有一条路能走到 receiver**，这条链路长期停在「代码看着对、谁也没验过」。
 *
 * 探针把「点了通知按钮之后会发生的事」原样搬进应用进程：
 * 用同一个 [JobQueue.cancelIntent] 自己投一次（**同进程投递不受导出位限制**）；
 * 更省事的一种是 `rerun-cancel`：拿最近一份任务定义重跑一遍，中途再取消，
 * 然后自己把快照读出来打进日志。这样一次 `am start` 就能验完「取消 → 任务停在半路」，
 * 完全不用依赖 UI 脚本的坐标。
 *
 * 用法（`<pkg>` = `com.pict.metatool`，`<probe>` = `com.pict.metatool.debug.CancelProbeActivity`）：
 * ```
 * adb shell am start -n <pkg>/<probe> --es mode cancel --es jobId <jobId>   # 只投一次取消
 * adb shell am start -n <pkg>/<probe> --es mode rerun                       # 重跑最近一份定义
 * adb shell am start -n <pkg>/<probe> --es mode rerun-cancel --el delayMs 4000
 * ```
 * 结果看 logcat：tag `CancelProbe` 的 `PROBE_RESULT`（status / finished / skipped）、
 * `PROBE_RERUN`（新任务 id）；receiver 有没有收到看 tag `JobCancelReceiver`。
 */
class CancelProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CANCEL
        val store = JobSpecStore(this)
        // 没显式给 jobId 就取盘上最近一份定义（时间倒序），这样脚本不用先去 ls 一遍
        val sourceId = intent.getStringExtra(JobQueue.KEY_JOB_ID) ?: store.ids().firstOrNull()

        when (mode) {
            MODE_RERUN, MODE_RERUN_CANCEL -> {
                if (sourceId == null) {
                    Log.i(TAG, "PROBE_ABORT 盘上一份任务定义都没有：先让界面跑一次批量再回来")
                } else {
                    rerun(store, sourceId, if (mode == MODE_RERUN_CANCEL) delayMillis() else NO_CANCEL)
                }
            }

            else -> {
                if (sourceId == null) {
                    Log.i(TAG, "PROBE_ABORT 没有可取消的 jobId")
                } else {
                    Log.i(TAG, "PROBE_CANCEL jobId=$sourceId")
                    sendBroadcast(JobQueue.cancelIntent(this, sourceId))
                }
            }
        }
        finish()
    }

    /**
     * 重跑一份已有定义（换成新 id，定义照常先落盘再入队——顺序跟界面那条路一样），
     * 需要的话过一会儿把取消意图投出去，然后把快照读回来打日志。
     */
    private fun rerun(store: JobSpecStore, sourceId: String, cancelAfterMillis: Long) {
        val spec = store.load(sourceId)
        if (spec == null) {
            Log.i(TAG, "PROBE_ABORT 读不到定义：$sourceId")
            return
        }
        val jobId = JobIds.newId(System.currentTimeMillis())
        val copy = spec.copy(jobId = jobId)
        val saved = store.save(copy)
        if (saved is PictResult.Failure) {
            Log.i(TAG, "PROBE_ABORT 定义存不下来：${saved.failure.detail}")
            return
        }

        JobQueue.enqueue(this, jobId)
        Log.i(TAG, "PROBE_RERUN jobId=$jobId items=${copy.items.size} 取消延迟=${cancelAfterMillis}ms")
        if (cancelAfterMillis < 0) return

        // 另起线程：Activity 立刻 finish，进程由 worker 的前台服务撑着，睡一觉再投取消
        thread(name = "cancel-probe") {
            Thread.sleep(cancelAfterMillis)
            Log.i(TAG, "PROBE_CANCEL jobId=$jobId（投给 ${JobCancelReceiver::class.java.simpleName}）")
            sendBroadcast(JobQueue.cancelIntent(this@CancelProbeActivity, jobId))

            // 收尾是异步的：正在写的那一项要把手上这个文件写完才认输，等一会儿再读快照
            Thread.sleep(RESULT_DELAY_MILLIS)
            val snapshot = JobSnapshotStore(this@CancelProbeActivity).load(jobId)
            Log.i(
                TAG,
                "PROBE_RESULT jobId=$jobId status=${snapshot?.status} " +
                    "finished=${snapshot?.finished}/${snapshot?.total} skipped=${snapshot?.skipped} " +
                    "succeeded=${snapshot?.succeeded} 停在哪一项=${snapshot?.currentName}",
            )
        }
    }

    private fun delayMillis(): Long =
        intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS).coerceAtLeast(0L)

    companion object {

        private const val TAG = "CancelProbe"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_DELAY_MS = "delayMs"

        private const val MODE_CANCEL = "cancel"
        private const val MODE_RERUN = "rerun"
        private const val MODE_RERUN_CANCEL = "rerun-cancel"

        /** 不取消（只重跑）时的哨兵值。 */
        private const val NO_CANCEL = -1L

        /** 默认在任务开跑 4 秒后取消——够它写掉前几项、又远没跑完。 */
        private const val DEFAULT_DELAY_MS = 4_000L

        /** 等收尾写完再读快照。 */
        private const val RESULT_DELAY_MILLIS = 6_000L
    }
}
