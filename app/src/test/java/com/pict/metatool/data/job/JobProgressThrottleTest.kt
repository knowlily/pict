package com.pict.metatool.data.job

import com.pict.metatool.domain.job.JobStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.3：进度通知的节流。
 *
 * 规则必须同时满足两件相反的事——**别把系统限频撞爆**（500 张图会发上千次更新），
 * 又**不能吞掉重要时刻**（用户按了取消、任务跑完了都必须在通知里看得见）。
 */
class JobProgressThrottleTest {

    private val first = snapshot(finished = 0, status = JobStatus.RUNNING, currentName = "a.jpg")

    private fun snapshot(
        finished: Int,
        status: JobStatus = JobStatus.RUNNING,
        currentName: String? = "a.jpg",
    ) = JobSnapshot(
        jobId = "job-1",
        label = "标签",
        status = status,
        total = 10,
        finished = finished,
        running = 1,
        succeeded = finished,
        failed = 0,
        verifyFailed = 0,
        skipped = 0,
        unsupported = 0,
        changedKeys = 0,
        currentName = currentName,
        dryRun = false,
        startedAtMillis = 0L,
        finishedAtMillis = null,
        updatedAtMillis = 0L,
    )

    @Test
    fun `第一条一定发——否则任务开跑时通知栏什么都没有`() {
        assertTrue(JobProgressThrottle.shouldPost(previous = null, next = first, lastPostedAtMillis = 0L, nowMillis = 0L))
    }

    @Test
    fun `什么都没变就别发`() {
        assertFalse(
            JobProgressThrottle.shouldPost(
                previous = first,
                next = snapshot(finished = 0),
                lastPostedAtMillis = 0L,
                nowMillis = 5_000L,
            ),
        )
    }

    @Test
    fun `进度变了但没到间隔，先憋着`() {
        assertFalse(
            JobProgressThrottle.shouldPost(
                previous = first,
                next = snapshot(finished = 3),
                lastPostedAtMillis = 1_000L,
                nowMillis = 1_400L,
            ),
        )
    }

    @Test
    fun `过了间隔就发`() {
        assertTrue(
            JobProgressThrottle.shouldPost(
                previous = first,
                next = snapshot(finished = 3),
                lastPostedAtMillis = 1_000L,
                nowMillis = 2_100L,
            ),
        )
    }

    @Test
    fun `刚换文件、还没到间隔就先不刷`() {
        assertFalse(
            JobProgressThrottle.shouldPost(
                previous = first,
                next = snapshot(finished = 0, currentName = "b.jpg"),
                lastPostedAtMillis = 1_000L,
                nowMillis = 1_100L,
            ),
        )
    }

    @Test
    fun `换了文件又过了间隔就刷一版`() {
        assertTrue(
            JobProgressThrottle.shouldPost(
                previous = first,
                next = snapshot(finished = 0, currentName = "b.jpg"),
                lastPostedAtMillis = 1_000L,
                nowMillis = 2_100L,
            ),
        )
    }

    @Test
    fun `终态必发，不管离上一条多近`() {
        assertTrue(
            JobProgressThrottle.shouldPost(
                previous = first,
                next = snapshot(finished = 10, status = JobStatus.COMPLETED, currentName = null),
                lastPostedAtMillis = 1_000L,
                nowMillis = 1_001L,
            ),
        )
    }

    @Test
    fun `已经终态了就不再刷`() {
        val done = snapshot(finished = 10, status = JobStatus.COMPLETED, currentName = null)
        assertTrue(JobProgressThrottle.shouldPost(previous = done, next = done, lastPostedAtMillis = 0L, nowMillis = 9_000L))
    }
}
