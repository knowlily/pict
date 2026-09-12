package com.pict.metatool.data.job

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import com.pict.metatool.domain.job.JobStatus
import kotlinx.coroutines.flow.first

/**
 * 队列那头对一次任务的说法（把 [WorkInfo] 归纳成判断要用的几档）。
 *
 * 前四档里只有前三档是「还活着」：排队中、跑着、被依赖挡着——页面把它们当「进行中」是对的。
 */
enum class QueueState {
    /** 排着队，还没轮到。 */
    QUEUED,

    /** 正在跑。 */
    RUNNING,

    /** 被前置条件挡着（本项目目前没用，留着免得以后加了被判成「死了」）。 */
    BLOCKED,

    /** 跑完了。 */
    DONE,

    /** 被人叫停（通知栏的取消、`JobQueue.cancel`）。 */
    CANCELED,

    /** 跑了但没成：worker 返回 `Result.failure`（被系统掐断那条路）。 */
    FAILED,

    /** 队列里连这条记录都没有了。 */
    GONE,
    ;

    val isAlive: Boolean get() = this == QUEUED || this == RUNNING || this == BLOCKED
}

/**
 * 对账的规矩：盘上那份快照该怎么改。纯函数，单测直接喂。
 */
object JobReconcileRules {

    /**
     * 这条快照现在该是哪个状态；`null` = 别动它。
     *
     * 「还是终态」与「队列那头说它还活着」都不动——用户点开任务页时正在跑的任务本来就该
     * 显示「进行中」，那是真的。
     */
    fun statusFor(current: JobStatus, queue: QueueState): JobStatus? = when {
        current.isTerminal -> null
        queue.isAlive -> null
        queue == QueueState.DONE -> JobStatus.COMPLETED
        queue == QueueState.CANCELED -> JobStatus.CANCELED
        else -> JobStatus.INTERRUPTED
    }

    /**
     * 收尾：抹掉「还在跑」的痕迹，并留一句为什么。
     *
     * 计数不编：已经落地的那些是真实观测，照原样留着；只把 `running` 归零（现在确实没有
     * 任何一项在跑）与 `currentName` 清空（那张的结局没人知道）。剩下的项数按
     * `total - finished` 算出来写进失败原因里——它不是「跳过」，是「没跑成」。
     */
    fun ended(snapshot: JobSnapshot, status: JobStatus, nowMillis: Long): JobSnapshot {
        val left = (snapshot.total - snapshot.finished).coerceAtLeast(0)
        val note = when (status) {
            JobStatus.INTERRUPTED -> "应用在跑这次任务时被中断，还剩 $left 项没有结果"
            JobStatus.CANCELED -> "任务被叫停，收尾没来得及记账，还剩 $left 项没有结果"
            JobStatus.COMPLETED -> if (left == 0) null else "任务结束了，但还剩 $left 项没有结果"
            else -> null
        }
        return snapshot.copy(
            status = status,
            running = 0,
            currentName = null,
            finishedAtMillis = snapshot.finishedAtMillis ?: nowMillis,
            updatedAtMillis = nowMillis,
            failures = if (note == null) {
                snapshot.failures
            } else {
                (listOf(note) + snapshot.failures).take(JobSnapshot.MAX_FAILURES)
            },
        )
    }
}

/**
 * 「盘上还写着在跑、其实早就没了」的对账（docs/08 §3「处理中杀进程 → 重启」）。
 *
 * 为什么需要它：worker 的收尾（`finally`）确实会写终态，但那是**协程还能跑**的时候。
 * 进程被硬杀（系统回收 / 强杀 / 崩溃）时那段根本不执行，盘上最后一版快照还写着 RUNNING，
 * 任务页于是永远把它摆在「进行中」——真机上那两条昨天被杀掉的任务就这么挂了一天。
 * 队列那头的说法才是准的：WorkManager 自己的记录知道这次工作是排着队、跑着、跑完了、
 * 被叫停了，还是根本没这回事。
 *
 * 只在任务页打开时对一遍账（用户点上来的那一刻才需要准确的表述），不做后台巡检：
 * 对账本身要读队列、要改文件，没必要在没人看的时候干。
 */
class JobSnapshotReconciler(
    private val snapshots: JobSnapshotStore,
    private val observe: suspend (String) -> QueueState,
) {

    constructor(context: Context) : this(
        snapshots = JobSnapshotStore(context),
        observe = { jobId -> queueStateOf(JobQueue.observe(context, jobId).first()) },
    )

    /**
     * 对一遍账，返回**改过的**那几条（没改就是空）。
     *
     * 只有「盘上不是终态」+「队列那头说它已经不在队列里」才动——两条同时成立时，盘上那份
     * 快照就是过期的。
     */
    suspend fun reconcile(nowMillis: Long = System.currentTimeMillis()): List<JobSnapshot> {
        val fixed = mutableListOf<JobSnapshot>()
        snapshots.all().forEach { snapshot ->
            if (snapshot.status.isTerminal) return@forEach
            val queue = observe(snapshot.jobId)
            val verdict = JobReconcileRules.statusFor(snapshot.status, queue) ?: return@forEach
            Log.i(TAG, "对账：${snapshot.jobId} 盘上写 ${snapshot.status.name}，队列说 $queue → ${verdict.name}")
            val ended = JobReconcileRules.ended(snapshot, verdict, nowMillis)
            snapshots.save(ended)
            fixed += ended
        }
        return fixed
    }

    private companion object {

        const val TAG = "JobReconcile"

        /** WorkManager 的四种终态说法，映射到我们判断要用的档位。 */
        fun queueStateOf(info: WorkInfo?): QueueState {
            if (info == null) return QueueState.GONE
            return when (info.state) {
                WorkInfo.State.ENQUEUED -> QueueState.QUEUED
                WorkInfo.State.RUNNING -> QueueState.RUNNING
                WorkInfo.State.BLOCKED -> QueueState.BLOCKED
                WorkInfo.State.SUCCEEDED -> {
                    // worker 返回成功说明它跑到了收尾：取消那条路也是 Result.success
                    val outcome = info.outputData.getString(JobQueue.KEY_OUTCOME)
                    if (outcome == JobQueue.OUTCOME_CANCELED) QueueState.CANCELED else QueueState.DONE
                }
                WorkInfo.State.CANCELLED -> QueueState.CANCELED
                WorkInfo.State.FAILED -> QueueState.FAILED
            }
        }
    }
}
