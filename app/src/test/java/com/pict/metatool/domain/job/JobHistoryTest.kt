package com.pict.metatool.domain.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.8、docs/06 §3.8：任务页两个分组怎么排。
 *
 * 排序是这里唯一的「判断」：进行中按排队时间、历史按结束时间。
 * 结束时间缺失的那些（被系统掐掉、没写终态）要用创建时间兜底——
 * 否则它们会全部沉到列表底下去，而它们恰恰是最需要被看见的。
 */
class JobHistoryTest {

    private fun entry(
        jobId: String,
        status: JobStatus = JobStatus.COMPLETED,
        createdAtMillis: Long = 1_000L,
        finishedAtMillis: Long? = null,
        hasSpec: Boolean = true,
        undoState: UndoState = UndoState.NO_BACKUP,
        failed: Int = 0,
        verifyFailed: Int = 0,
        skipped: Int = 0,
        unsupported: Int = 0,
    ) = JobHistoryEntry(
        jobId = jobId,
        label = "任务 $jobId",
        status = status,
        createdAtMillis = createdAtMillis,
        finishedAtMillis = finishedAtMillis,
        total = 4,
        succeeded = 4 - failed - verifyFailed - skipped - unsupported,
        failed = failed,
        verifyFailed = verifyFailed,
        skipped = skipped,
        unsupported = unsupported,
        hasSpec = hasSpec,
        undoState = undoState,
    )

    @Test
    fun `跑着的和跑完的分成两组`() {
        val history = JobHistory.from(
            listOf(
                entry("p", status = JobStatus.PENDING),
                entry("r", status = JobStatus.RUNNING),
                entry("c", status = JobStatus.COMPLETED),
                entry("x", status = JobStatus.CANCELED),
            ),
        )

        assertEquals(listOf("p", "r"), history.running.map { it.jobId }.sorted())
        assertEquals(listOf("c", "x"), history.finished.map { it.jobId }.sorted())
        assertEquals(4, history.size)
        assertFalse(history.isEmpty)
        assertTrue(JobHistory.from(emptyList()).isEmpty)
    }

    @Test
    fun `进行中按排队时间倒序：刚点下去的在最上面`() {
        val history = JobHistory.from(
            listOf(
                entry("old", status = JobStatus.RUNNING, createdAtMillis = 1_000L),
                entry("new", status = JobStatus.PENDING, createdAtMillis = 3_000L),
                entry("mid", status = JobStatus.RUNNING, createdAtMillis = 2_000L),
            ),
        )

        assertEquals(listOf("new", "mid", "old"), history.running.map { it.jobId })
    }

    @Test
    fun `历史按结束时间倒序`() {
        val history = JobHistory.from(
            listOf(
                entry("first", createdAtMillis = 1_000L, finishedAtMillis = 5_000L),
                entry("last", createdAtMillis = 2_000L, finishedAtMillis = 9_000L),
                entry("mid", createdAtMillis = 3_000L, finishedAtMillis = 7_000L),
            ),
        )

        assertEquals(listOf("last", "mid", "first"), history.finished.map { it.jobId })
    }

    @Test
    fun `没有结束时间的用创建时间兜底，不会一起沉底`() {
        val history = JobHistory.from(
            listOf(
                entry("normal", createdAtMillis = 1_000L, finishedAtMillis = 6_000L),
                // 被系统掐掉、只落了取消没写终态的那次：创建得最晚，就该排在历史最上面
                entry("killed", status = JobStatus.CANCELED, createdAtMillis = 9_000L),
            ),
        )

        assertTrue(history.running.isEmpty())
        assertEquals(listOf("killed", "normal"), history.finished.map { it.jobId })
        assertEquals(9_000L, history.finished.first().orderMillis)
    }

    @Test
    fun `历史只留最近 20 条，多的沉在盘上不显示`() {
        val entries = (1..25).map { index ->
            entry("job-$index", createdAtMillis = index * 1_000L, finishedAtMillis = index * 1_000L + 500L)
        }

        val history = JobHistory.from(entries)

        assertEquals(JobHistory.FINISHED_LIMIT, history.finished.size)
        assertEquals("job-25", history.finished.first().jobId)
        assertEquals("job-6", history.finished.last().jobId)
    }

    @Test
    fun `能不能重跑看定义在不在，能不能撤看撤销档位`() {
        val rerunnable = entry("with-spec", hasSpec = true)
        val specGone = entry("spec-gone", hasSpec = false, undoState = UndoState.AVAILABLE)

        assertTrue(rerunnable.canRerun)
        assertFalse(specGone.canRerun)
        // 两件事互不干扰：定义丢了照样能撤，撤销过期了照样能重跑
        assertTrue(specGone.canUndo)
        assertFalse(rerunnable.canUndo)
    }

    @Test
    fun `落定与要看一眼的项分开数`() {
        val entry = entry("job-1", failed = 1, verifyFailed = 2, skipped = 1)

        assertEquals(4, entry.settled)
        assertEquals(3, entry.troubled)
    }
}
