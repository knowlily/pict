package com.pict.metatool.ui.job

import androidx.work.WorkInfo
import com.pict.metatool.data.job.JobLiveState
import com.pict.metatool.data.job.JobSnapshot
import com.pict.metatool.domain.job.Job
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobReport
import com.pict.metatool.domain.job.JobReportParams
import com.pict.metatool.domain.job.JobStatus
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度页状态的合成（docs/07 T5.6）。
 *
 * 三个来源（进程内广播 / 盘上快照与报告 / WorkManager 队列状态）谁说了算，
 * 是这一页最容易写错的地方——所以逐个钉住。全部是纯函数，不需要 Robolectric。
 */
class JobProgressUiStateTest {

    private val params = JobReportParams(
        mode = "PRESET",
        presetIds = emptyList(),
        overwriteExisting = false,
        seed = 1L,
        clearTargets = emptyList(),
        dryRun = false,
        concurrency = 2,
        maxRetries = 2,
        itemCount = 4,
    )

    private fun item(id: String, name: String, status: JobItemStatus) = JobItem(
        id = id,
        uri = "content://pict/$name",
        source = SourceInfo(name, "image/jpeg", 1_024L, ImageFormatHint.JPEG),
        status = status,
        startedAtMillis = 1_000L,
        finishedAtMillis = 1_500L,
    )

    private fun job(status: JobStatus, items: List<JobItem>) = Job(
        id = "job-1",
        label = "套用预设 · 4 张",
        createdAtMillis = 1L,
        items = items,
        status = status,
        startedAtMillis = 2L,
        finishedAtMillis = 3L,
    )

    private fun report(status: JobStatus, itemStatuses: List<JobItemStatus>): JobReport =
        JobReport.of(
            job(status, itemStatuses.mapIndexed { index, s -> item("i$index", "f$index.jpg", s) }),
            params,
        )

    private fun snapshot(status: JobStatus, itemStatuses: List<JobItemStatus>): JobSnapshot =
        JobSnapshot.of(job(status, itemStatuses.mapIndexed { index, s -> item("i$index", "f$index.jpg", s) }), 9L)

    @Test
    fun `没有数据时是空的，不崩`() {
        val state = JobProgressUiState(jobId = "job-1")

        assertEquals(0, state.total)
        assertEquals(0, state.finished)
        assertEquals(0, state.percent)
        assertNull(state.label.ifEmpty { null })
        assertFalse(state.hasReport)
        assertFalse(state.canCancel)
    }

    @Test
    fun `盘上的快照与报告能填进来`() {
        val state = JobProgressUiState(jobId = "job-1").withStored(
            snapshot = snapshot(JobStatus.COMPLETED, listOf(JobItemStatus.SUCCESS, JobItemStatus.FAILED)),
            report = report(JobStatus.COMPLETED, listOf(JobItemStatus.SUCCESS, JobItemStatus.FAILED)),
        )

        assertEquals("套用预设 · 4 张", state.label)
        assertEquals(100, state.percent)
        assertTrue(state.hasReport)
        assertTrue(state.isTerminal)
    }

    @Test
    fun `只读得到报告时，计数从逐项状态里数出来`() {
        val state = JobProgressUiState(jobId = "job-1").withStored(
            snapshot = null,
            report = report(
                JobStatus.RUNNING,
                listOf(JobItemStatus.SUCCESS, JobItemStatus.RUNNING, JobItemStatus.PENDING, JobItemStatus.PENDING),
            ),
        )

        assertEquals(4, state.total)
        assertEquals(1, state.finished)
        assertEquals(25, state.percent)
        assertEquals("f1.jpg", state.currentName)
        assertFalse(state.isTerminal)
    }

    @Test
    fun `广播到了之后，盘上那份不许把新值盖回去`() {
        val live = JobProgressUiState(jobId = "job-1").withLive(
            JobLiveState(
                snapshot = snapshot(JobStatus.RUNNING, listOf(JobItemStatus.SUCCESS, JobItemStatus.RUNNING)),
                report = report(JobStatus.RUNNING, listOf(JobItemStatus.SUCCESS, JobItemStatus.RUNNING)),
                remainingMillis = 4_000L,
                isLive = true,
            ),
        )

        val afterDisk = live.withStored(
            snapshot = snapshot(JobStatus.RUNNING, listOf(JobItemStatus.PENDING, JobItemStatus.PENDING)),
            report = null,
        )

        // 磁盘快照是节流写入的、天然更旧：谁后到用谁会让进度条往回跳
        assertEquals(50, afterDisk.percent)
        assertEquals(4_000L, afterDisk.remainingMillis)
        assertTrue(afterDisk.hasReport)
    }

    @Test
    fun `队列状态能回答还在不在跑：排队中给取消按钮，已结束不给`() {
        val queued = JobProgressUiState(jobId = "job-1").withWorkState(WorkInfo.State.ENQUEUED)
        assertTrue(queued.isWaiting)
        assertTrue(queued.canCancel)

        val done = JobProgressUiState(jobId = "job-1").withWorkState(WorkInfo.State.SUCCEEDED)
        assertTrue(done.isTerminal)
        assertFalse(done.canCancel)
    }

    @Test
    fun `已经收到广播就不再显示排队中`() {
        val state = JobProgressUiState(jobId = "job-1")
            .withWorkState(WorkInfo.State.ENQUEUED)
            .withLive(
                JobLiveState(
                    snapshot = snapshot(JobStatus.RUNNING, listOf(JobItemStatus.RUNNING, JobItemStatus.PENDING)),
                    report = report(JobStatus.RUNNING, listOf(JobItemStatus.RUNNING, JobItemStatus.PENDING)),
                    remainingMillis = null,
                    isLive = true,
                ),
            )

        assertFalse(state.isWaiting)
        assertTrue(state.canCancel)
    }

    @Test
    fun `收尾那一次广播也要认：任务结束了就不该再给取消`() {
        val state = JobProgressUiState(jobId = "job-1").withLive(
            JobLiveState(
                snapshot = snapshot(JobStatus.CANCELED, listOf(JobItemStatus.SKIPPED, JobItemStatus.SKIPPED)),
                report = report(JobStatus.CANCELED, listOf(JobItemStatus.SKIPPED, JobItemStatus.SKIPPED)),
                remainingMillis = null,
                isLive = false,
            ),
        )

        assertTrue(state.isTerminal)
        assertFalse(state.canCancel)
        assertFalse(state.live)
        assertNull(state.remainingMillis)
    }
}
