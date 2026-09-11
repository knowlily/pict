package com.pict.metatool.data.job

import com.pict.metatool.domain.job.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.3：通知栏措辞。
 *
 * 通知是用户唯一能看到的进度窗口（批量任务跑在后台），所以「说了什么」是功能的一部分：
 * 「成功 3」和「已处理 3/10」是两件事，前者会被读成「跑完了」。这里逐条钉住。
 */
class JobNotificationContentTest {

    private fun snapshot(
        status: JobStatus = JobStatus.RUNNING,
        total: Int = 10,
        finished: Int = 3,
        running: Int = 1,
        succeeded: Int = 2,
        failed: Int = 1,
        verifyFailed: Int = 0,
        skipped: Int = 0,
        unsupported: Int = 0,
        currentName: String? = "a.jpg",
        dryRun: Boolean = false,
    ): JobSnapshot = JobSnapshot(
        jobId = "job-1",
        label = "套用预设「iPhone 16 Pro」· 10 张",
        status = status,
        total = total,
        finished = finished,
        running = running,
        succeeded = succeeded,
        failed = failed,
        verifyFailed = verifyFailed,
        skipped = skipped,
        unsupported = unsupported,
        changedKeys = 12,
        currentName = currentName,
        dryRun = dryRun,
        startedAtMillis = 1_726_000_000_000L,
        finishedAtMillis = null,
        updatedAtMillis = 1_726_000_001_000L,
    )

    @Test
    fun `跑着的时候说清进度与成败与正在处理谁`() {
        val content = JobNotificationContent.of("标签", snapshot())

        assertEquals("标签", content.title)
        assertTrue(content.text.contains("已处理 3 / 10"))
        assertTrue(content.text.contains("成功 2"))
        assertTrue(content.text.contains("失败 1"))
        assertTrue(content.text.contains("正在处理 a.jpg"))
        assertEquals(30, content.percent)
        assertFalse(content.indeterminate)
        assertTrue(content.ongoing)
    }

    @Test
    fun `没失败的项就不提失败，别把 0 挂在通知上`() {
        val content = JobNotificationContent.of("标签", snapshot(failed = 0, verifyFailed = 0))

        assertFalse(content.text.contains("失败"))
        assertFalse(content.text.contains("校验未通过"))
    }

    @Test
    fun `校验未通过单独报——它既不是失败也不是成功`() {
        val content = JobNotificationContent.of("标签", snapshot(succeeded = 1, failed = 0, verifyFailed = 2))

        assertTrue(content.text.contains("校验未通过 2"))
    }

    @Test
    fun `跑完的通知不再常驻`() {
        val content = JobNotificationContent.of(
            "标签",
            snapshot(status = JobStatus.COMPLETED, finished = 10, running = 0, succeeded = 9, failed = 1, currentName = null),
        )

        assertTrue(content.text.contains("已完成"))
        assertTrue(content.text.contains("成功 9"))
        assertEquals(100, content.percent)
        assertFalse(content.ongoing)
    }

    @Test
    fun `取消的通知把跳过的项也交代清楚`() {
        val content = JobNotificationContent.of(
            "标签",
            snapshot(status = JobStatus.CANCELED, finished = 5, running = 0, succeeded = 1, skipped = 4, currentName = null),
        )

        assertTrue(content.text.contains("已取消"))
        assertTrue(content.text.contains("已跳过 4"))
        assertFalse(content.ongoing)
    }

    @Test
    fun `dry-run 的通知先说清它不动文件`() {
        val content = JobNotificationContent.of("标签", snapshot(dryRun = true))

        assertTrue(content.text.startsWith("只算不写"))
    }

    @Test
    fun `空任务不显示成卡在 0%`() {
        val queued = JobNotificationContent.of("标签", snapshot(status = JobStatus.PENDING, total = 0, finished = 0, currentName = null))
        assertTrue(queued.indeterminate)

        val done = JobNotificationContent.of(
            "标签",
            snapshot(status = JobStatus.COMPLETED, total = 0, finished = 0, running = 0, currentName = null),
        )
        assertEquals(100, done.percent)
        assertFalse(done.indeterminate)
    }

    @Test
    fun `文案模板可替换，界面文案仍归资源文件`() {
        val texts = JobNotificationTexts(running = "%1\$d of %2\$d", succeeded = "%1\$d ok")

        val content = JobNotificationContent.of("L", snapshot(succeeded = 2, failed = 0), texts)

        assertTrue(content.text.contains("3 of 10"))
        assertTrue(content.text.contains("2 ok"))
    }
}
