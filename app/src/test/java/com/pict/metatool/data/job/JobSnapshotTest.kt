package com.pict.metatool.data.job

import com.pict.metatool.domain.job.Job
import com.pict.metatool.domain.job.JobItem
import com.pict.metatool.domain.job.JobItemStatus
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.job.JobStatus
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.3：执行态快照。
 *
 * 快照是「杀进程之后还知道跑到哪」的唯一凭据，所以它必须**只由任务本身决定**
 * （不依赖界面在不在），且能在盘上转一圈回来。
 */
class JobSnapshotTest {

    private val make = TagKey.of("EXIF:Make")

    private fun item(
        id: String,
        name: String,
        status: JobItemStatus,
        changed: Set<TagKey> = emptySet(),
        note: String? = null,
        detail: String? = null,
    ) = JobItem(
        id = id,
        uri = "content://pict/$name",
        source = SourceInfo(name, "image/jpeg", 2_048L, ImageFormatHint.JPEG),
        status = status,
        changedKeys = changed,
        note = note,
        detail = detail,
    )

    private fun job(
        status: JobStatus = JobStatus.RUNNING,
        dryRun: Boolean = false,
        items: List<JobItem>,
    ) = Job(
        id = "job-1",
        label = "套用预设 · 4 张",
        createdAtMillis = 1_726_000_000_000L,
        items = items,
        options = JobOptions(dryRun = dryRun),
        status = status,
        startedAtMillis = 1_726_000_000_500L,
    )

    @Test
    fun `计数按项状态各归各档，不把校验未通过算成失败`() {
        val snapshot = JobSnapshot.of(
            job(
                items = listOf(
                    item("item-0", "a.jpg", JobItemStatus.SUCCESS, setOf(make)),
                    item("item-1", "b.jpg", JobItemStatus.FAILED),
                    item("item-2", "c.jpg", JobItemStatus.VERIFY_FAILED),
                    item("item-3", "d.jpg", JobItemStatus.SKIPPED),
                    item("item-4", "e.jpg", JobItemStatus.UNSUPPORTED),
                    item("item-5", "f.jpg", JobItemStatus.RUNNING),
                    item("item-6", "g.jpg", JobItemStatus.RETRYING),
                    item("item-7", "h.jpg", JobItemStatus.PENDING),
                ),
            ),
            nowMillis = 1_726_000_002_000L,
        )

        assertEquals(8, snapshot.total)
        assertEquals(5, snapshot.finished)
        assertEquals(2, snapshot.running)
        assertEquals(1, snapshot.succeeded)
        assertEquals(1, snapshot.failed)
        assertEquals(1, snapshot.verifyFailed)
        assertEquals(1, snapshot.skipped)
        assertEquals(1, snapshot.unsupported)
        assertEquals(1, snapshot.changedKeys)
        assertEquals("f.jpg", snapshot.currentName)
        // 失败/校验未通过的项要留下原因，计数之外还得看得见「为什么」
        assertEquals(2, snapshot.failures.size)
        assertTrue(snapshot.failures.first().startsWith("b.jpg："))
        assertEquals(62, snapshot.percent)
        assertFalse(snapshot.isTerminal)
    }

    @Test
    fun `失败项没写 note 时用 detail 顶上，实在没有才退回状态名`() {
        val snapshot = JobSnapshot.of(
            job(
                items = listOf(
                    item("item-0", "a.jpg", JobItemStatus.VERIFY_FAILED, detail = "匹配 45 项，多出 2 项[EXIF:Software、XMP:Rating]"),
                    item("item-1", "b.jpg", JobItemStatus.FAILED, note = "第 1 次失败，E-IO 后重试"),
                    item("item-2", "c.jpg", JobItemStatus.VERIFY_FAILED),
                ),
            ),
            nowMillis = 1_726_000_002_000L,
        )

        assertEquals(snapshot.failures.toString(), 3, snapshot.failures.size)
        // 校验未通过的原因在 detail 上：快照要把它带出来，而不是只留个 VERIFY_FAILED
        assertTrue(snapshot.failures[0], snapshot.failures[0].contains("多出 2 项"))
        assertTrue(snapshot.failures[0], snapshot.failures[0].contains("EXIF:Software"))
        // note 优先于 detail（Runner 写的短句更贴近「发生了什么」）
        assertEquals("b.jpg：第 1 次失败，E-IO 后重试", snapshot.failures[1])
        // 两样都没有时不编造，如实退回状态名
        assertEquals("c.jpg：VERIFY_FAILED", snapshot.failures[2])
    }

    @Test
    fun `跑完的检查点由任务状态说了算`() {
        val done = JobSnapshot.of(
            job(
                status = JobStatus.COMPLETED,
                items = listOf(item("item-0", "a.jpg", JobItemStatus.SUCCESS, setOf(make))),
            ),
            nowMillis = 1L,
        )

        assertEquals(100, done.percent)
        assertTrue(done.isTerminal)
        assertNull(done.currentName)
    }

    @Test
    fun `空任务算完成而不是卡在 0%`() {
        val empty = JobSnapshot.of(job(items = emptyList()), nowMillis = 1L)

        assertEquals(0, empty.total)
        assertEquals(0, empty.percent)
        assertFalse(empty.isTerminal)

        val emptyDone = JobSnapshot.of(job(status = JobStatus.COMPLETED, items = emptyList()), nowMillis = 1L)
        assertEquals(100, emptyDone.percent)
    }

    @Test
    fun `dry-run 标记跟着任务走`() {
        val snapshot = JobSnapshot.of(job(dryRun = true, items = emptyList()), nowMillis = 1L)

        assertTrue(snapshot.dryRun)
    }

    @Test
    fun `快照转一圈 JSON 回来还是同一份`() {
        val original = JobSnapshot.of(
            job(
                items = listOf(
                    item("item-0", "a.jpg", JobItemStatus.SUCCESS, setOf(make)),
                    item("item-1", "带\"引号\\和换行的.jpg", JobItemStatus.RUNNING),
                ),
            ),
            nowMillis = 1_726_000_003_000L,
        )

        assertEquals(original, JobSnapshot.fromJson(original.toJson()))
    }

    @Test
    fun `烂快照当没有，不让一个坏文件拖垮任务列表`() {
        assertNull(JobSnapshot.fromJson("半截 json"))
        assertNull(JobSnapshot.fromJson("""{"jobId":"j","status":"TELEPORTING"}"""))
    }

    @Test
    fun `被叫停还没到终态时，收尾按取消记账`() {
        // 真机实测的形态：已经落地几项、有项正在写、其余还没开工，此时 WorkManager 把
        // worker 的协程取消了 —— 域层那张 CANCELED 发不出来，收尾必须自己补上
        val ended = JobSnapshot.endOf(
            job(
                items = listOf(
                    item("item-0", "a.jpg", JobItemStatus.SUCCESS, setOf(make)),
                    item("item-1", "b.jpg", JobItemStatus.SUCCESS),
                    item("item-2", "c.jpg", JobItemStatus.RUNNING),
                    item("item-3", "d.jpg", JobItemStatus.RUNNING),
                    item("item-4", "e.jpg", JobItemStatus.PENDING),
                    item("item-5", "f.jpg", JobItemStatus.PENDING),
                ),
            ),
            stopped = true,
            nowMillis = 1_726_000_009_000L,
        )

        assertEquals(JobStatus.CANCELED, ended.status)
        assertTrue(ended.isTerminal)
        assertEquals(1_726_000_009_000L, ended.finishedAtMillis)
        // 未开工的两项记 SKIPPED（与 Job.cancel 同一套口径），正在写的两项原样留着
        assertEquals(2, ended.skipped)
        assertEquals(2, ended.running)
        assertEquals(4, ended.finished)
    }

    @Test
    fun `没被叫停就别把跑完的任务改成取消`() {
        val done = job(status = JobStatus.COMPLETED, items = listOf(item("item-0", "a.jpg", JobItemStatus.SUCCESS)))

        val ended = JobSnapshot.endOf(done, stopped = false, nowMillis = 1_726_000_009_000L)

        assertEquals(JobStatus.COMPLETED, ended.status)
        assertEquals(0, ended.skipped)
    }
}
