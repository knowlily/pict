package com.pict.metatool.data.job

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.job.Job
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.job.JobReport
import com.pict.metatool.domain.job.JobReportParams
import com.pict.metatool.domain.job.JobStatus
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 报告落盘（docs/07 T5.7）。
 *
 * 这份文件要能在杀进程之后回答「这一轮到底做了什么」，所以重点钉两件事：
 * 盘上转一圈回来**一个字段都不少**（尤其参数与种子），以及坏文件只当「没有报告」。
 */
class JobReportStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = JobReportStore(File(folder.root, JobSpecStore.DIR))

    private fun report(
        jobId: String = "job-1-abcd",
        status: JobStatus = JobStatus.COMPLETED,
        itemStatus: JobItemStatus = JobItemStatus.SUCCESS,
    ) = JobReport.of(
        Job(
            id = jobId,
            label = "套用预设 · 1 张",
            createdAtMillis = 1_726_000_000_000L,
            items = listOf(
                JobItem(
                    id = "i0",
                    uri = "content://pict/a.jpg",
                    source = SourceInfo("a.jpg", "image/jpeg", 2_048L, ImageFormatHint.JPEG),
                    status = itemStatus,
                    startedAtMillis = 1_726_000_000_500L,
                    finishedAtMillis = 1_726_000_000_750L,
                    changedKeys = setOf(TagKey.of("EXIF:Make")),
                    error = if (itemStatus == JobItemStatus.FAILED) PictError.IO_WRITE else null,
                    note = "丢过 2 个字段",
                ),
            ),
            options = JobOptions(dryRun = true),
            status = status,
            startedAtMillis = 1_726_000_000_500L,
            finishedAtMillis = 1_726_000_009_000L,
        ),
        JobReportParams(
            mode = "PRESET",
            presetId = "iphone-15",
            overwriteExisting = true,
            seed = 20260101L,
            clearTargets = listOf("GPS", "ORIENTATION"),
            dryRun = true,
            concurrency = 4,
            maxRetries = 2,
            itemCount = 1,
        ),
    )

    @Test
    fun `存了再读，参数与种子一个不少`() {
        val store = store()
        val original = report()
        store.save(original)

        val loaded = store.load(original.jobId)

        assertEquals(original, loaded)
        assertEquals(20260101L, loaded?.params?.seed)
        assertEquals(listOf("GPS", "ORIENTATION"), loaded?.params?.clearTargets)
        assertEquals("iphone-15", loaded?.params?.presetId)
    }

    @Test
    fun `逐项的耗时、错误码、变更字段数、备注都能转一圈回来`() {
        val store = store()
        val original = report(itemStatus = JobItemStatus.FAILED)
        store.save(original)

        val item = store.load(original.jobId)?.items?.single()

        assertEquals(250L, item?.durationMillis)
        assertEquals(PictError.IO_WRITE.code, item?.errorCode)
        assertEquals(1, item?.changedKeys)
        assertEquals("丢过 2 个字段", item?.note)
    }

    @Test
    fun `没跑完的耗时读回来还是空`() {
        val store = store()
        val original = JobReport.of(
            Job(
                id = "job-1-incomplete",
                label = "被取消的任务",
                createdAtMillis = 1L,
                items = listOf(
                    JobItem(
                        id = "i0",
                        uri = "content://pict/a.jpg",
                        source = SourceInfo("a.jpg", "image/jpeg", 1L, ImageFormatHint.JPEG),
                        status = JobItemStatus.SKIPPED,
                        note = "未开工",
                    ),
                ),
                status = JobStatus.CANCELED,
            ),
            report().params,
        )
        store.save(original)

        val item = store.load(original.jobId)?.items?.single()
        assertNull(item?.durationMillis)
        assertEquals("未开工", item?.note)
    }

    @Test
    fun `存盘不把文件写成半截：写完没有 tmp 残留`() {
        val store = store()
        store.save(report())

        val leftover = File(folder.root, JobSpecStore.DIR).listFiles().orEmpty().map { it.name }
        assertTrue(leftover.none { it.endsWith(".tmp") })
        assertTrue(leftover.any { it.endsWith(JobReportStore.SUFFIX) })
    }

    @Test
    fun `半截 JSON 读成没有，不抛异常`() {
        val store = store()
        store.save(report())
        store.fileOf("job-1-abcd").writeText("{ 半截")

        assertNull(store.load("job-1-abcd"))
    }

    @Test
    fun `没存过的任务读成 null`() {
        assertNull(store().load("job-9-none"))
    }

    @Test
    fun `id 拼得成路径穿越的一律不认`() {
        val store = store()

        store.save(report(jobId = "../../etc/passwd"))

        assertNull(store.load("../../etc/passwd"))
        assertNull(store.load("../evil"))
    }
}
