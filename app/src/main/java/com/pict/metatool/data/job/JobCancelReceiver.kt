package com.pict.metatool.data.job

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通知栏「取消」按钮的接收者（docs/07 T5.3，FR-29）。
 *
 * 用户要停一个跑到一半的批量任务，最短的路径是**下拉通知栏点取消**——
 * 而不是划回应用、进批量页、再找那个按钮（那时界面可能早就重建过了）。
 *
 * 只做一件事：转给 [JobQueue]。取消本身是「请求」，正在写的那一项会把
 * 手上这个文件写完再认输（见 `JobRunner` / `JobItemStatus.canMoveTo`），
 * 所以这里不能、也不需要杀进程。
 */
class JobCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != JobQueue.CANCEL_ACTION) return
        val jobId = intent.getStringExtra(JobQueue.KEY_JOB_ID)
        // 留一行日志：这颗按钮有没有生效，只能从「任务真的停了」看出来，
        // 排查「点了没反应」时先看这行在不在（判断意图有没有送到），再看快照停在了哪一项。
        Log.i(TAG, "取消请求：jobId=${jobId ?: "(缺失 → 全停)"}")
        if (jobId.isNullOrEmpty()) {
            // 没带 id（比如通知被系统重建过）：宁可按「全停」，也不留一个停不下来的任务
            JobQueue.cancelAll(context)
            return
        }
        JobQueue.cancel(context, jobId)
    }

    companion object {
        private const val TAG = "JobCancelReceiver"
    }
}
