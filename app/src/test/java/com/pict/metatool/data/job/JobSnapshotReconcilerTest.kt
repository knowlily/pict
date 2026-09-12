package com.pict.metatool.data.job

import com.pict.metatool.domain.job.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/08 §3「处理中杀进程 → 重启」：盘上还写着「进行中」、队列那头早没这回事时的对账。
 *
 * 这里只测判断本身（纯函数）——真的去问 WorkManager、真的写盘那两下靠真机走查。
 */
class JobSnapshotReconcilerTest {

    private fun snapshot(
        status: JobStatus = JobStatus.RUNNING,
        total: Int = 10,
        finished: Int = 4,
        running: Int = 1,
        currentName: String? = "a.jpg",
        finishedAtMillis: Long? = null,
        failures: List<String> = emptyList(),
    ) = JobSnapshot(
        jobId = "job-1",
        label = "任务",
        status = status,
        total = total,
        finished = finished,
        running = running,
        succeeded = finished,
        failed = 0,
        verifyFailed = 0,
        skipped = 0,
        unsupported = 0,
        changedKeys = 0,
        currentName = currentName,
        dryRun = false,
        startedAtMillis = 1_000L,
        finishedAtMillis = finishedAtMillis,
        updatedAtMillis = 2_000L,
        failures = failures,
        createdAtMillis = 900L,
    )

    @Test
    fun `队列里还排着、跑着、被挡着，都别动`() {
        val alive = listOf(QueueState.QUEUED, QueueState.RUNNING, QueueState.BLOCKED)

        assertTrue(alive.all { it.isAlive })
        alive.forEach { queue ->
            assertNull("队列说 $queue 时不该改快照", JobReconcileRules.statusFor(JobStatus.RUNNING, queue))
            assertNull(JobReconcileRules.statusFor(JobStatus.PENDING, queue))
        }
    }

    @Test
    fun `队列里没有记录了，剩下四档都是终态`() {
        val gone = listOf(QueueState.DONE, QueueState.CANCELED, QueueState.FAILED, QueueState.GONE)

        assertFalse(gone.any { it.isAlive })
    }

    @Test
    fun `队列说跑完了就是已完成，说被叫停就是已取消`() {
        assertEquals(JobStatus.COMPLETED, JobReconcileRules.statusFor(JobStatus.RUNNING, QueueState.DONE))
        assertEquals(JobStatus.CANCELED, JobReconcileRules.statusFor(JobStatus.RUNNING, QueueState.CANCELED))
    }

    @Test
    fun `跑了没成、或者连记录都没有，都算中断`() {
        assertEquals(JobStatus.INTERRUPTED, JobReconcileRules.statusFor(JobStatus.RUNNING, QueueState.FAILED))
        assertEquals(JobStatus.INTERRUPTED, JobReconcileRules.statusFor(JobStatus.RUNNING, QueueState.GONE))
        assertEquals(JobStatus.INTERRUPTED, JobReconcileRules.statusFor(JobStatus.PENDING, QueueState.GONE))
    }

    @Test
    fun `盘上已经是终态的快照，队列说什么都不动`() {
        val terminal = JobStatus.entries.filter { it.isTerminal }

        assertEquals(3, terminal.size)
        terminal.forEach { current ->
            QueueState.entries.forEach { queue ->
                assertNull(
                    "盘上是 $current、队列说 $queue 都不该改",
                    JobReconcileRules.statusFor(current, queue),
                )
            }
        }
    }

    @Test
    fun `中断收尾抹掉「还在跑」的痕迹，已落地的计数照原样留`() {
        val ended = JobReconcileRules.ended(snapshot(), JobStatus.INTERRUPTED, nowMillis = 5_000L)

        assertEquals(JobStatus.INTERRUPTED, ended.status)
        assertEquals(0, ended.running)
        assertNull(ended.currentName)
        assertEquals(5_000L, ended.finishedAtMillis)
        assertEquals(5_000L, ended.updatedAtMillis)
        // 已跑完的那些是真实观测，不编也不抹
        assertEquals(10, ended.total)
        assertEquals(4, ended.finished)
        assertTrue(ended.isTerminal)
    }

    @Test
    fun `还剩几项没跑写进失败原因的最前面`() {
        val ended = JobReconcileRules.ended(
            snapshot(failures = listOf("b.jpg：值不符")),
            JobStatus.INTERRUPTED,
            nowMillis = 5_000L,
        )

        assertEquals(2, ended.failures.size)
        assertTrue(ended.failures.first(), ended.failures.first().contains("6"))
        assertEquals("b.jpg：值不符", ended.failures.last())
    }

    @Test
    fun `失败原因只留前几条，中断那句不会被挤掉`() {
        val many = (1..8).map { "item-$it.jpg：失败" }

        val ended = JobReconcileRules.ended(snapshot(failures = many), JobStatus.INTERRUPTED, 5_000L)

        assertEquals(JobSnapshot.MAX_FAILURES, ended.failures.size)
        assertTrue(ended.failures.first().contains("中断"))
    }

    @Test
    fun `已经有结束时间的不改它`() {
        val ended = JobReconcileRules.ended(
            snapshot(finishedAtMillis = 3_000L),
            JobStatus.INTERRUPTED,
            5_000L,
        )

        assertEquals(3_000L, ended.finishedAtMillis)
    }

    @Test
    fun `跑完了只是没写终态的那种，不硬加一句没跑成的`() {
        val ended = JobReconcileRules.ended(
            snapshot(total = 10, finished = 10, running = 0),
            JobStatus.COMPLETED,
            5_000L,
        )

        assertEquals(JobStatus.COMPLETED, ended.status)
        assertTrue(ended.failures.isEmpty())
    }

    @Test
    fun `被叫停的收尾也说清楚还剩几项没记账`() {
        val ended = JobReconcileRules.ended(snapshot(), JobStatus.CANCELED, 5_000L)

        assertEquals(JobStatus.CANCELED, ended.status)
        assertTrue(ended.failures.first(), ended.failures.first().contains("6"))
    }

    @Test
    fun `中断是终态，不是还在跑`() {
        assertTrue(JobStatus.INTERRUPTED.isTerminal)
        assertFalse(JobStatus.RUNNING.isTerminal)
        assertEquals("已中断", JobStatus.INTERRUPTED.label)
    }
}
