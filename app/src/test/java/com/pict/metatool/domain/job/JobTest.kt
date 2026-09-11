package com.pict.metatool.domain.job

import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * docs/07 T5.1：任务与任务项的记账、状态机、进度。
 *
 * 时钟一律由测试传入（[Job] 的所有方法都要求 `nowMillis`），所以这里断言的是
 * 状态迁移与算式本身，不看真实时间——报告里的耗时/预估也不能靠「跑得快就绿」。
 */
class JobTest {

    private fun item(id: String) = JobItem(
        id = id,
        uri = "content://pict/$id",
        source = SourceInfo("$id.jpg", "image/jpeg", 2_048L, ImageFormatHint.JPEG),
    )

    private fun job(vararg ids: String, options: JobOptions = JobOptions()) = Job(
        id = "job-1",
        label = "测试任务",
        createdAtMillis = 1_000L,
        items = ids.map(::item),
        options = options,
    )

    /** 让某一项开工（PENDING → RUNNING）。状态机不许一步跨到终态，所以测试也分两步走。 */
    private fun Job.begin(itemId: String, at: Long = 2_000L): Job =
        update(
            item(itemId)!!.copy(status = JobItemStatus.RUNNING, attempts = 1, startedAtMillis = at),
            at,
        )

    private fun Job.finish(
        itemId: String,
        at: Long = 2_500L,
        status: JobItemStatus = JobItemStatus.SUCCESS,
        keys: Set<TagKey> = emptySet(),
        note: String? = null,
    ): Job = update(
        item(itemId)!!.copy(
            status = status,
            changedKeys = keys,
            note = note,
            finishedAtMillis = at,
        ),
        at,
    )

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 预期
        }
    }

    // ---------- 状态机 ----------

    @Test
    fun `迁移矩阵只放行该放的箭头`() {
        val allowed = mapOf(
            JobItemStatus.PENDING to setOf(
                JobItemStatus.RUNNING, JobItemStatus.SKIPPED, JobItemStatus.UNSUPPORTED,
            ),
            JobItemStatus.RUNNING to setOf(
                JobItemStatus.SUCCESS, JobItemStatus.RETRYING, JobItemStatus.FAILED,
                JobItemStatus.VERIFY_FAILED, JobItemStatus.SKIPPED, JobItemStatus.UNSUPPORTED,
            ),
            JobItemStatus.RETRYING to setOf(
                JobItemStatus.RUNNING, JobItemStatus.FAILED, JobItemStatus.SKIPPED,
            ),
            JobItemStatus.SUCCESS to emptySet(),
            JobItemStatus.FAILED to emptySet(),
            JobItemStatus.VERIFY_FAILED to emptySet(),
            JobItemStatus.SKIPPED to emptySet(),
            JobItemStatus.UNSUPPORTED to emptySet(),
        )

        JobItemStatus.entries.forEach { from ->
            JobItemStatus.entries.forEach { to ->
                assertEquals("$from → $to", to in allowed.getValue(from), from.canMoveTo(to))
            }
        }
    }

    @Test
    fun `终态恰好是成功失败校验失败跳过与不支持`() {
        val terminal = JobItemStatus.entries.filter { it.isTerminal }.toSet()

        assertEquals(
            setOf(
                JobItemStatus.SUCCESS, JobItemStatus.FAILED, JobItemStatus.VERIFY_FAILED,
                JobItemStatus.SKIPPED, JobItemStatus.UNSUPPORTED,
            ),
            terminal,
        )
        assertFalse("重试中的项还没完", JobItemStatus.RETRYING.isTerminal)
        assertTrue(JobStatus.CANCELED.isTerminal)
        assertFalse(JobStatus.RUNNING.isTerminal)
    }

    @Test
    fun `运行中回报不支持也能收尾`() {
        // docs/01 §5 只画了 PENDING → UNSUPPORTED，但「支不支持」得打开文件才知道，
        // 等 Worker 回报时项已经在跑了；不放行这条就会卡在 RUNNING 永远不完成。
        val after = job("a")
            .start(2_000L)
            .begin("a", at = 2_100L)
            .finish("a", at = 2_200L, status = JobItemStatus.UNSUPPORTED, note = "HEIF 只读")

        assertEquals(JobItemStatus.UNSUPPORTED, after.item("a")!!.status)
        assertEquals(JobStatus.COMPLETED, after.status)
    }

    @Test
    fun `运行中确定性失败可以当场记失败`() {
        // 不可重试的失败绕道 RETRYING 会让界面显示一个永远不会发生的「重试中」
        val after = job("a")
            .start(2_000L)
            .begin("a", at = 2_100L)
            .finish("a", at = 2_150L, status = JobItemStatus.FAILED)

        assertEquals(JobItemStatus.FAILED, after.item("a")!!.status)
    }

    // ---------- 取消 ----------

    @Test
    fun `取消把未开工的项记成跳过并带上原因`() {
        val canceled = job("a", "b", "c").start(2_000L).cancel(9_000L)

        assertEquals(JobStatus.CANCELED, canceled.status)
        assertEquals(9_000L, canceled.finishedAtMillis)
        assertEquals(3, canceled.count(JobItemStatus.SKIPPED))
        assertTrue(canceled.items.all { it.note == Job.CANCELED_NOTE })
    }

    @Test
    fun `取消不碰正在处理的那一项`() {
        // 正在写的文件不能凭空变成「已跳过」：让执行侧在安全点自己收尾，
        // 否则界面说跳过了、文件其实正被改。
        val after = job("a", "b").start(2_000L).begin("a", at = 2_100L).cancel(2_200L)

        assertEquals(JobItemStatus.RUNNING, after.item("a")!!.status)
        assertNull(after.item("a")!!.note)
        assertEquals(JobItemStatus.SKIPPED, after.item("b")!!.status)
    }

    @Test
    fun `取消之后即使全部收尾也不改回已完成`() {
        val after = job("a", "b")
            .start(2_000L)
            .begin("a", at = 2_100L)
            .cancel(2_200L)
            .finish("a", at = 2_300L)

        assertEquals(JobStatus.CANCELED, after.status)
        assertEquals(1, after.count(JobItemStatus.SUCCESS))
        assertEquals(1, after.count(JobItemStatus.SKIPPED))
    }

    @Test
    fun `取消已下达时收尾的那一下不会记成已完成`() {
        // 执行侧在取消后仍要落账最后一项，这时必须显式告知「取消已下达」，
        // 否则「跑完的 + 跳过的」恰好全到终态 → 报告写已完成，而用户按的是取消。
        val before = job("a", "b").start(2_000L).begin("a", at = 2_100L)
        val after = before.update(
            before.item("a")!!.copy(status = JobItemStatus.SUCCESS, finishedAtMillis = 2_200L),
            2_200L,
            cancelRequested = true,
        )

        assertEquals(JobStatus.CANCELED, after.status)
        assertEquals(1, after.count(JobItemStatus.SUCCESS))
        assertEquals(1, after.count(JobItemStatus.SKIPPED))
    }

    // ---------- 任务级状态 ----------

    @Test
    fun `全部到终态才完成任务`() {
        val half = job("a", "b").start(2_000L).begin("a", at = 2_100L).finish("a", at = 2_500L)

        assertEquals("还有项在排队，任务不能算完", JobStatus.RUNNING, half.status)

        val all = half.begin("b", at = 2_550L).finish("b", at = 2_600L)

        assertEquals(JobStatus.COMPLETED, all.status)
        assertEquals(2_600L, all.finishedAtMillis)
    }

    @Test
    fun `重复 id 与未知 id 都不放过`() {
        assertIllegalArgument { Job("job-1", "测试任务", 1_000L, listOf(item("a"), item("a"))) }

        val before = job("a").start(2_000L)
        val stranger = JobItem("b", "content://pict/b", before.item("a")!!.source, status = JobItemStatus.RUNNING)
        assertIllegalArgument { before.update(stranger, 2_100L) }
    }

    @Test
    fun `非法迁移当场抛异常`() {
        val done = job("a").start(2_000L).begin("a", at = 2_100L).finish("a", at = 2_100L)

        // 终态再改一次 = 执行侧写错，静默吞掉会留下自相矛盾的报告
        assertIllegalArgument {
            done.update(done.item("a")!!.copy(status = JobItemStatus.RUNNING), 2_200L)
        }
    }

    // ---------- 进度与记账 ----------

    @Test
    fun `进度按项记账并给出预估剩余`() {
        val second = job("a", "b", "c")
            .start(1_000L)
            .begin("a", at = 1_100L)
            .finish("a", at = 2_100L)
            .begin("b", at = 2_200L)
        val progress = second.progress(3_200L)

        assertEquals(3, progress.total)
        assertEquals(1, progress.finished)
        assertEquals(1, progress.succeeded)
        assertEquals(1, progress.running)
        assertEquals("当前项要报文件名（FR-29）", "b.jpg", progress.currentName)
        assertEquals(1f / 3f, progress.fraction, 0.0001f)
        assertEquals("已完成项平均 1000 ms，还剩 2 项", 2_000L, progress.remainingMillis)
        assertEquals(2_200L, progress.elapsedMillis)
        assertFalse(progress.isDone)
    }

    @Test
    fun `一项都没完成时不给假的剩余时间`() {
        assertNull(job("a", "b").start(1_000L).progress(1_500L).remainingMillis)
    }

    @Test
    fun `耗时只在有始有终时给出`() {
        assertNull("还没开工就没有耗时", item("a").durationMillis)
        assertEquals(
            400L,
            item("a").copy(
                status = JobItemStatus.RUNNING,
                startedAtMillis = 100L,
                finishedAtMillis = 500L,
            ).durationMillis,
        )
    }

    @Test
    fun `变更字段数按报告口径记账`() {
        val keys = setOf(TagKey.of("EXIF:Make"), TagKey.of("EXIF:Model"))
        val done = job("a").start(2_000L).begin("a", at = 2_100L).finish("a", at = 2_200L, keys = keys)

        assertEquals(2, done.item("a")!!.changedCount)
    }

    @Test
    fun `选项越界一律夹回`() {
        assertEquals(8, JobOptions(concurrency = 99).normalized().concurrency)
        assertEquals(1, JobOptions(concurrency = 0).normalized().concurrency)
        assertEquals(2, JobOptions(maxRetries = 9).normalized().maxRetries)
        assertEquals(0, JobOptions(maxRetries = -3).normalized().maxRetries)
        assertTrue(
            "默认并发要落在 1–8（FR-35）",
            JobOptions.defaultConcurrency() in JobOptions.MIN_CONCURRENCY..JobOptions.MAX_CONCURRENCY,
        )
    }

    @Test
    fun `任务的选项在装配时就已经规范化`() {
        val job = job("a").withOptions(JobOptions(concurrency = 42))

        assertEquals(8, job.options.concurrency)
    }
}
