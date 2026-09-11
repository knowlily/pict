package com.pict.metatool.domain.job

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务报告（docs/07 T5.7、docs/01 FR-31）。
 *
 * 验收口径是**行数与任务数一致、能被 Excel / 文本工具打开**，所以这里重点钉三件事：
 * CSV 的行列结构、状态与错误码这类「对账字段」有没有落对列、以及转义。
 */
class JobReportTest {

    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")

    private val params = JobReportParams(
        mode = "PRESET",
        presetId = "iphone-15",
        overwriteExisting = true,
        seed = 20260101L,
        clearTargets = listOf("GPS"),
        dryRun = false,
        concurrency = 4,
        maxRetries = 2,
        itemCount = 3,
    )

    private fun item(
        id: String,
        name: String,
        status: JobItemStatus,
        startedAtMillis: Long? = null,
        finishedAtMillis: Long? = null,
        changed: Set<TagKey> = emptySet(),
        error: PictError? = null,
        note: String? = null,
        detail: String? = null,
    ) = JobItem(
        id = id,
        uri = "content://pict/$name",
        source = SourceInfo(name, "image/jpeg", 2_048L, ImageFormatHint.JPEG),
        status = status,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        changedKeys = changed,
        error = error,
        note = note,
        detail = detail,
    )

    private fun report(
        items: List<JobItem>,
        status: JobStatus = JobStatus.COMPLETED,
        dryRun: Boolean = false,
    ) = JobReport.of(
        Job(
            id = "job-1",
            label = "套用预设 · 3 张",
            createdAtMillis = 1_726_000_000_000L,
            items = items,
            options = JobOptions(dryRun = dryRun),
            status = status,
            startedAtMillis = 1_726_000_000_500L,
            finishedAtMillis = 1_726_000_009_000L,
        ),
        params,
    )

    private fun JobReport.csvLines(): List<String> =
        toCsv().removePrefix(JobReport.BOM).trimEnd('\n').lines()

    @Test
    fun `CSV 行数永远等于项数加表头`() {
        val report = report(
            listOf(
                item("i0", "a.jpg", JobItemStatus.SUCCESS),
                item("i1", "b.jpg", JobItemStatus.FAILED),
                item("i2", "c.jpg", JobItemStatus.SKIPPED),
            ),
        )
        val lines = report.csvLines()

        assertEquals(report.total + 1, lines.size)
        assertEquals(JobReport.CSV_HEADER, lines.first())
    }

    @Test
    fun `CSV 以 BOM 开头：中文 Windows 的 Excel 才不当成 GBK`() {
        val text = report(listOf(item("i0", "a.jpg", JobItemStatus.SUCCESS))).toCsv()
        assertTrue(text.startsWith(JobReport.BOM))
    }

    @Test
    fun `一行的六列各就各位：文件名、状态、耗时、错误码、变更字段数、备注`() {
        val report = report(
            listOf(
                item(
                    id = "i0",
                    name = "a.jpg",
                    status = JobItemStatus.FAILED,
                    startedAtMillis = 1_000L,
                    finishedAtMillis = 1_250L,
                    changed = setOf(make, model),
                    error = PictError.STORAGE_READONLY,
                    note = "只读，写不进去",
                ),
            ),
        )
        val columns = report.csvLines()[1].split(',')

        assertEquals("a.jpg", columns[0])
        assertEquals(JobItemStatus.FAILED.label, columns[1])
        assertEquals("250", columns[2])
        assertEquals(PictError.STORAGE_READONLY.code, columns[3])
        assertEquals("2", columns[4])
        // 中文备注里的全角逗号是内容、不是分隔符，所以这一格不该被引号包住
        assertEquals("只读，写不进去", columns[5])
    }

    @Test
    fun `备注里有半角逗号时整格加引号`() {
        val report = report(
            listOf(item("i0", "a.jpg", JobItemStatus.SUCCESS, note = "丢掉 Make, Model 两个字段")),
        )
        val line = report.csvLines()[1]

        assertTrue(line.endsWith("\"丢掉 Make, Model 两个字段\""))
        // 加了引号也仍然是一行：对账按行数走，不能因为一格里的逗号多出列来
        assertEquals(2, report.csvLines().size)
    }

    @Test
    fun `没跑完的项耗时留空，不写 0`() {
        val report = report(listOf(item("i0", "a.jpg", JobItemStatus.SKIPPED, note = "未开工")))
        val columns = report.csvLines()[1].split(',')

        assertEquals("", columns[2])
    }

    @Test
    fun `带逗号引号的文件名被引号包住、引号翻倍`() {
        val report = report(
            listOf(item("i0", "a,b\"c.jpg", JobItemStatus.SUCCESS, startedAtMillis = 0L, finishedAtMillis = 5L)),
        )
        val line = report.csvLines()[1]

        assertTrue(line.startsWith("\"a,b\"\"c.jpg\""))
    }

    @Test
    fun `文件名里的换行折成空格：一项就是一行，对账才对得上`() {
        val report = report(
            listOf(
                item("i0", "a\nb.jpg", JobItemStatus.SUCCESS),
                item("i1", "c.jpg", JobItemStatus.SUCCESS),
            ),
        )
        val lines = report.csvLines()

        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("a b.jpg"))
    }

    @Test
    fun `备注优先用 note，没有才退回 detail`() {
        val withNote = report(listOf(item("i0", "a.jpg", JobItemStatus.SUCCESS, note = "丢过 3 个字段", detail = "细节")))
        val withDetail = report(listOf(item("i1", "b.jpg", JobItemStatus.SUCCESS, detail = "细节")))

        assertEquals("丢过 3 个字段", withNote.items.first().note)
        assertEquals("细节", withDetail.items.first().note)
    }

    @Test
    fun `各类计数分档，不把校验未通过算进失败`() {
        val report = report(
            listOf(
                item("i0", "a.jpg", JobItemStatus.SUCCESS),
                item("i1", "b.jpg", JobItemStatus.SUCCESS),
                item("i2", "c.jpg", JobItemStatus.FAILED),
                item("i3", "d.jpg", JobItemStatus.VERIFY_FAILED),
                item("i4", "e.jpg", JobItemStatus.SKIPPED),
                item("i5", "f.jpg", JobItemStatus.UNSUPPORTED),
                item("i6", "g.jpg", JobItemStatus.PENDING),
            ),
        )

        assertEquals(7, report.total)
        assertEquals(2, report.succeeded)
        assertEquals(1, report.failed)
        assertEquals(1, report.verifyFailed)
        assertEquals(1, report.skipped)
        assertEquals(1, report.unsupported)
    }

    @Test
    fun `变更字段合计是每项加总`() {
        val report = report(
            listOf(
                item("i0", "a.jpg", JobItemStatus.SUCCESS, changed = setOf(make, model)),
                item("i1", "b.jpg", JobItemStatus.SUCCESS, changed = setOf(make)),
            ),
        )
        assertEquals(3, report.changedKeysTotal)
    }

    @Test
    fun `报告跟着任务的动作走：试运行、被取消都如实带上`() {
        val dryRunReport = report(listOf(item("i0", "a.jpg", JobItemStatus.SUCCESS)), dryRun = true)
        assertTrue(dryRunReport.dryRun)

        val canceled = report(listOf(item("i0", "a.jpg", JobItemStatus.SKIPPED)), status = JobStatus.CANCELED)
        assertEquals(JobStatus.CANCELED, canceled.status)
        assertFalse(canceled.params.dryRun)
        // 参数原样从定义带过来，报告不重算种子
        assertEquals(20260101L, canceled.params.seed)
    }
}
