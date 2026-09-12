package com.pict.metatool.data.job

import com.pict.metatool.domain.batch.BatchDraft
import com.pict.metatool.domain.batch.BatchMode
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.job.Job
import com.pict.metatool.domain.job.JobHistory
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.job.JobReport
import com.pict.metatool.domain.job.JobReportItem
import com.pict.metatool.domain.job.JobReportParams
import com.pict.metatool.domain.job.JobStatus
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * docs/07 T5.8：历史列表的装配。
 *
 * 用真的临时目录：这里要验的恰好是「三份东西（快照、报告、定义）怎么拼成一行」，
 * 装配错一处（比如把报告读成别人的、把时间戳丢了）只有真文件才看得出来。
 */
class JobHistoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun dir(root: File = folder.root) = File(root, JobSpecStore.DIR)

    private fun store(root: File = folder.root) = JobHistoryStore(
        snapshots = JobSnapshotStore(dir(root)),
        specs = JobSpecStore(dir(root)),
        reports = JobReportStore(dir(root)),
    )

    private fun item(
        name: String,
        status: JobItemStatus = JobItemStatus.SUCCESS,
        backupFolder: String? = null,
    ) = JobReportItem(
        name = name,
        status = status,
        durationMillis = 12L,
        errorCode = null,
        changedKeys = 1,
        note = null,
        uri = "content://pict/src/$name",
        backupUri = backupFolder?.let { "content://pict/backup/$it/$name" },
        backupFolder = backupFolder,
    )

    private fun report(
        jobId: String,
        items: List<JobReportItem>,
        undoneAtMillis: Long? = null,
    ) = JobReport(
        jobId = jobId,
        label = "任务 $jobId",
        status = JobStatus.COMPLETED,
        dryRun = false,
        startedAtMillis = 1_726_000_000_000L,
        finishedAtMillis = 1_726_000_009_000L,
        params = JobReportParams(
            mode = "PRESET",
            presetId = "device.iphone-16-pro",
            overwriteExisting = true,
            seed = 7L,
            clearTargets = emptyList(),
            dryRun = false,
            concurrency = 4,
            maxRetries = 2,
            itemCount = items.size,
        ),
        items = items,
        undoneAtMillis = undoneAtMillis,
    )

    private fun jobItem(jobId: String) = JobItem(
        id = "$jobId-0",
        uri = "content://pict/$jobId.jpg",
        source = SourceInfo("$jobId.jpg", "image/jpeg", 2_048L, ImageFormatHint.JPEG),
        status = JobItemStatus.SUCCESS,
    )

    private fun job(
        jobId: String,
        status: JobStatus,
        finishedAtMillis: Long? = null,
    ) = Job(
        id = jobId,
        label = "任务 $jobId",
        createdAtMillis = 1_726_000_000_000L,
        items = listOf(jobItem(jobId)),
        options = JobOptions(),
        status = status,
        startedAtMillis = 1_726_000_002_000L,
        finishedAtMillis = finishedAtMillis,
    )

    private fun runningSnapshot(jobId: String) =
        JobSnapshot.of(job(jobId, JobStatus.RUNNING), nowMillis = 1_726_000_003_000L)

    private fun finishedSnapshot(jobId: String) = JobSnapshot.of(
        job(jobId, JobStatus.COMPLETED, finishedAtMillis = 1_726_000_009_000L),
        nowMillis = 1_726_000_009_000L,
    )

    private fun spec(jobId: String) = BatchJobSpec.from(
        draft = BatchDraft(mode = BatchMode.RANDOM, presetId = "device.iphone-16-pro", seed = 3L),
        targets = listOf(BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.JPEG)),
        options = JobOptions(),
        jobId = jobId,
        nowMillis = 1_726_000_000_000L,
    )

    @Test
    fun `扫盘装出两组，历史行带上备份目录与份数`() {
        val snapshots = JobSnapshotStore(dir())
        snapshots.save(runningSnapshot("job-running"))
        snapshots.save(finishedSnapshot("job-done"))

        val reports = JobReportStore(dir())
        reports.save(
            report(
                jobId = "job-done",
                items = listOf(
                    item("a.jpg", backupFolder = "20260912_101530"),
                    item("b.jpg", backupFolder = "20260912_101530"),
                    item("c.jpg", status = JobItemStatus.FAILED, backupFolder = "20260912_114500"),
                ),
            ),
        )

        val loaded = store().load()
        val entries = JobHistory.from(loaded.entries)

        assertEquals(listOf("job-running"), entries.running.map { it.jobId })
        assertEquals(listOf("job-done"), entries.finished.map { it.jobId })
        // 最后一次备份的目录：按目录名里的时间戳挑，不看报告里的项序
        assertEquals("20260912_114500", entries.finished.single().backupFolder)
        assertEquals(1, entries.finished.single().backupFiles)
        assertNull(entries.running.single().backupFolder)
    }

    @Test
    fun `两次备份的时间戳都进时间戳表，撤销判定才有得挑`() {
        val snapshots = JobSnapshotStore(dir())
        snapshots.save(finishedSnapshot("job-done"))
        JobReportStore(dir()).save(
            report(
                jobId = "job-done",
                items = listOf(
                    item("a.jpg", backupFolder = "20260912_101530"),
                    item("b.jpg", backupFolder = "20260912_101530"),
                    item("c.jpg", backupFolder = "20260912_114500"),
                ),
            ),
        )

        val stamps = store().load().stamps

        assertEquals(2, stamps.size)
        assertEquals(listOf("20260912_101530", "20260912_114500"), stamps.map { it.folder }.sorted())
        assertEquals(2, stamps.single { it.folder == "20260912_101530" }.files)
        assertEquals("job-done", stamps.first().jobId)
        assertTrue(stamps.all { it.createdAt.year == 2026 })
    }

    @Test
    fun `报告里的撤销时刻带到条目上`() {
        val snapshots = JobSnapshotStore(dir())
        snapshots.save(finishedSnapshot("job-done"))
        JobReportStore(dir()).save(
            report(
                jobId = "job-done",
                items = listOf(item("a.jpg", backupFolder = "20260912_101530")),
                undoneAtMillis = 1_726_000_100_000L,
            ),
        )

        val entry = store().load().entries.single()

        assertEquals(1_726_000_100_000L, entry.undoneAtMillis)
    }

    @Test
    fun `没有报告的历史任务照样在列表里，只是没有备份可撤`() {
        // 老任务（备份还没接上时跑的）报告可能已经被清理，列表不能因此空着
        val snapshots = JobSnapshotStore(dir())
        snapshots.save(finishedSnapshot("job-old"))

        val loaded = store().load()

        assertEquals(listOf("job-old"), loaded.entries.map { it.jobId })
        assertNull(loaded.entries.single().backupFolder)
        assertEquals(0, loaded.entries.single().backupFiles)
        assertNull(loaded.entries.single().undoneAtMillis)
        assertTrue(loaded.stamps.isEmpty())
    }

    @Test
    fun `定义还在就能重跑，定义丢了就不给重跑`() {
        val snapshots = JobSnapshotStore(dir())
        snapshots.save(finishedSnapshot("job-here"))
        snapshots.save(finishedSnapshot("job-gone"))
        JobSpecStore(dir()).save(spec("job-here"))

        val entries = store().load().entries.associateBy { it.jobId }

        assertTrue(entries.getValue("job-here").hasSpec)
        assertFalse(entries.getValue("job-gone").hasSpec)
    }
}
