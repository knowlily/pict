package com.pict.metatool.domain.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.8、docs/01 FR-34：撤销计划怎么算。
 *
 * 这一层是「拿报告算账」：留了备份的恢复回去、没有备份的导出副本删掉、
 * 缺地址的老报告跳过。真正动手的部分在 `JobUndoRunner`，这里只验算出来的计划。
 */
class JobUndoTest {

    private fun item(
        name: String,
        status: JobItemStatus = JobItemStatus.SUCCESS,
        uri: String? = "content://pict/src/$name",
        outputUri: String? = null,
        backupUri: String? = null,
        backupFolder: String? = null,
    ) = JobReportItem(
        name = name,
        status = status,
        durationMillis = 12L,
        errorCode = null,
        changedKeys = 1,
        note = null,
        uri = uri,
        outputUri = outputUri,
        backupUri = backupUri,
        backupFolder = backupFolder,
    )

    private fun report(
        items: List<JobReportItem>,
        dryRun: Boolean = false,
    ) = JobReport(
        jobId = "job-1-abcd",
        label = "套用预设 · ${items.size} 张",
        status = JobStatus.COMPLETED,
        dryRun = dryRun,
        startedAtMillis = 1_726_000_000_000L,
        finishedAtMillis = 1_726_000_009_000L,
        params = JobReportParams(
            mode = "PRESET",
            presetId = "device.iphone-16-pro",
            overwriteExisting = true,
            seed = 7L,
            clearTargets = emptyList(),
            dryRun = dryRun,
            concurrency = 4,
            maxRetries = 2,
            itemCount = items.size,
        ),
        items = items,
    )

    @Test
    fun `留了备份的一律恢复，写失败的那张也照样恢复`() {
        val plan = JobUndo.plan(
            report(
                listOf(
                    item("a.jpg", backupUri = "content://pict/b/20260912_101530/a.jpg", backupFolder = "20260912_101530"),
                    item(
                        "b.jpg",
                        status = JobItemStatus.FAILED,
                        backupUri = "content://pict/b/20260912_101530/b.jpg",
                        backupFolder = "20260912_101530",
                    ),
                ),
            ),
        )

        assertEquals(listOf("a.jpg", "b.jpg"), plan.restores.map { it.name })
        assertTrue(plan.deletes.isEmpty())
        assertEquals("content://pict/b/20260912_101530/a.jpg", plan.restores[0].backupUri)
        assertEquals("content://pict/src/a.jpg", plan.restores[0].targetUri)
    }

    @Test
    fun `没有备份的导出副本要删掉，源文件一根汗毛都不动`() {
        val plan = JobUndo.plan(
            report(
                listOf(
                    item(
                        "a.webp",
                        uri = "content://pict/src/a.jpg",
                        outputUri = "content://pict/out/a.webp",
                    ),
                ),
            ),
        )

        assertEquals(listOf("content://pict/out/a.webp"), plan.deletes.map { it.targetUri })
        assertTrue(plan.restores.isEmpty())
    }

    @Test
    fun `副本地址就是源文件时不删源文件`() {
        // 原位覆写的任务里 outputUri 就等于 uri：删了等于把用户的图删了
        val plan = JobUndo.plan(
            report(listOf(item("a.jpg", uri = "content://pict/src/a.jpg", outputUri = "content://pict/src/a.jpg"))),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `试运行没有可撤的，哪怕报告里带着备份地址`() {
        val plan = JobUndo.plan(
            report(
                items = listOf(item("a.jpg", backupUri = "content://pict/b/20260912_101530/a.jpg")),
                dryRun = true,
            ),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `缺地址的老报告项跳过：没地址动手就是瞎猜`() {
        val plan = JobUndo.plan(
            report(
                listOf(
                    item("a.jpg", uri = null, backupUri = "content://pict/b/20260912_101530/a.jpg"),
                    item("b.jpg", uri = ""),
                ),
            ),
        )

        assertEquals("job-1-abcd", plan.jobId)
        assertTrue(plan.isEmpty)
        assertFalse(plan.isEmpty && plan.steps.isNotEmpty())
    }

    @Test
    fun `计划顺序跟报告里的项一致`() {
        val plan = JobUndo.plan(
            report(
                listOf(
                    item("a.jpg", backupUri = "content://pict/b/20260912_101530/a.jpg"),
                    item("b.jpg", uri = "content://pict/src/b.jpg", outputUri = "content://pict/out/b.jpg"),
                    item("c.jpg", backupUri = "content://pict/b/20260912_101530/c.jpg"),
                ),
            ),
        )

        assertEquals(3, plan.size)
        assertEquals(listOf("a.jpg", "c.jpg"), plan.restores.map { it.name })
        assertEquals(listOf("b.jpg"), plan.deletes.map { it.name })
    }
}
