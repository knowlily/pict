package com.pict.metatool.data.job

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        if (jobId.isNullOrEmpty()) {
            // 没带 id（比如通知被系统重建过）：宁可按「全停」，也不留一个停不下来的任务
            JobQueue.cancelAll(context)
            return
        }
        JobQueue.cancel(context, jobId)
    }
}
