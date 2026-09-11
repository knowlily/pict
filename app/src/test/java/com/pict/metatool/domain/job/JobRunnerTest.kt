package com.pict.metatool.domain.job

import com.pict.metatool.core.error.PictError
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * docs/07 T5.2：并发、重试、取消、进度流。
 *
 * 这里用假的 [JobItemWorker] 把所有「怎么读文件、怎么写盘」都挡在外面，
 * 于是并发上限、重试次数、取消语义都能在 JVM 上跑满——设备与真文件留给 androidTest。
 * 退避与等待都可注入，测试里不会真等 500 ms / 2 s。
 */
class JobRunnerTest {

    private fun item(id: String) = JobItem(
        id = id,
        uri = "content://pict/$id",
        source = SourceInfo("$id.jpg", "image/jpeg", 1_024L, ImageFormatHint.JPEG),
    )

    private fun job(ids: List<String>, options: JobOptions = JobOptions(maxRetries = 2)) = Job(
        id = "job-1",
        label = "测试任务",
        createdAtMillis = 1_000L,
        items = ids.map(::item),
        options = options,
    )

    /** 按 id 排好的剧本工人：第 n 次调用返回第 n 条，用完了就一直复用最后一条。 */
    private class ScriptedWorker(
        private val script: Map<String, List<ItemResult>> = emptyMap(),
    ) : JobItemWorker {

        val calls = mutableListOf<String>()
        val attempts = mutableMapOf<String, Int>()
        val optionsSeen = mutableListOf<JobOptions>()

        override suspend fun run(item: JobItem, options: JobOptions): ItemResult {
            calls += item.id
            attempts[item.id] = (attempts[item.id] ?: 0) + 1
            optionsSeen += options
            val results = script[item.id] ?: listOf(ItemResult.Done())
            return results.getOrElse(attempts.getValue(item.id) - 1) { results.last() }
        }
    }

    /** 记录退避请求与真实等待，避免测试真的睡 2 秒。 */
    private class Backoff {
        val asked = mutableListOf<Int>()
        val slept = mutableListOf<Long>()

        fun policy(attempt: Int): Long {
            asked += attempt
            return 7L
        }
    }

    private fun runner(worker: JobItemWorker, backoff: Backoff = Backoff()) = JobRunner(
        worker = worker,
        dispatcher = Dispatchers.Default,
        backoff = backoff::policy,
        sleep = { backoff.slept += it },
    )

    // ---------- 顺路 ----------

    @Test
    fun `全部成功时逐项记账并收尾`() = runBlocking {
        val keys = setOf(TagKey.of("EXIF:Make"), TagKey.of("EXIF:Model"))
        val worker = ScriptedWorker(mapOf("a" to listOf(ItemResult.Done(keys, "out/a.jpg"))))

        val snapshots = runner(worker).run(job(listOf("a", "b"))).toList()
        val final = snapshots.last()

        assertEquals(JobStatus.COMPLETED, final.status)
        assertTrue(final.items.all { it.status == JobItemStatus.SUCCESS })
        assertEquals(2, final.items.sumOf { it.attempts })
        assertEquals(keys, final.item("a")!!.changedKeys)
        assertEquals("out/a.jpg", final.item("a")!!.outputUri)
        assertEquals(2, worker.calls.size)
    }

    @Test
    fun `空任务直接算完成`() = runBlocking {
        val final = runner(ScriptedWorker()).run(job(emptyList())).toList().last()

        assertEquals(JobStatus.COMPLETED, final.status)
        assertTrue(final.items.isEmpty())
    }

    @Test
    fun `dry-run 标志原样交给工人`() = runBlocking {
        // FR-32：不落盘是工人的责任，任务层只保证标志不被吃掉
        val worker = ScriptedWorker()

        runner(worker).run(job(listOf("a"), options = JobOptions(dryRun = true))).toList()

        assertTrue(worker.optionsSeen.single().dryRun)
    }

    @Test
    fun `进度快照不倒退且最后一版是终态`() = runBlocking {
        val final = runner(ScriptedWorker())
            .run(job(listOf("a", "b", "c"), options = JobOptions(concurrency = 1)))
            .toList()

        val finished = final.map { it.progress(9_000L).finished }

        assertEquals(finished.sorted(), finished)
        assertEquals(3, finished.last())
        assertTrue(final.last().isTerminal)
    }

    // ---------- 重试 ----------

    @Test
    fun `可重试失败会退避后重来并最终成功`() = runBlocking {
        val backoff = Backoff()
        val worker = ScriptedWorker(
            mapOf("a" to listOf(ItemResult.Failed(PictError.IO_WRITE, "磁盘忙"), ItemResult.Done())),
        )

        val snapshots = runner(worker, backoff).run(job(listOf("a"))).toList()
        val a = snapshots.last().item("a")!!

        assertEquals(JobItemStatus.SUCCESS, a.status)
        assertEquals(2, a.attempts)
        assertEquals(listOf(1), backoff.asked)
        assertEquals(listOf(7L), backoff.slept)
        assertTrue(
            "重试中的项要出现在快照里，UI 才知道它在等",
            snapshots.any { it.item("a")!!.status == JobItemStatus.RETRYING },
        )
    }

    @Test
    fun `重试耗尽后按最后一次的错误记失败`() = runBlocking {
        val backoff = Backoff()
        val worker = ScriptedWorker(
            mapOf(
                "a" to listOf(
                    ItemResult.Failed(PictError.IO_WRITE, "第一次"),
                    ItemResult.Failed(PictError.IO_WRITE, "第二次"),
                    ItemResult.Failed(PictError.IO_WRITE, "第三次"),
                ),
            ),
        )

        val final = runner(worker, backoff).run(job(listOf("a"))).toList().last()
        val a = final.item("a")!!

        assertEquals("1 次首跑 + 2 次重试（FR-30）", 3, worker.calls.size)
        assertEquals(listOf(1, 2), backoff.asked)
        assertEquals(JobItemStatus.FAILED, a.status)
        assertEquals(PictError.IO_WRITE, a.error)
        assertEquals("第三次", a.detail)
        assertEquals(3, a.attempts)
    }

    @Test
    fun `确定性失败不浪费重试`() = runBlocking {
        val backoff = Backoff()
        val worker = ScriptedWorker(mapOf("a" to listOf(ItemResult.Failed(PictError.STORAGE_READONLY))))

        val final = runner(worker, backoff).run(job(listOf("a"))).toList().last()

        assertEquals("不可写的源再试也没用", 1, worker.calls.size)
        assertTrue(backoff.asked.isEmpty())
        assertEquals(PictError.STORAGE_READONLY, final.item("a")!!.error)
    }

    @Test
    fun `退避口径是 500 毫秒与 2 秒`() {
        assertEquals(500L, JobRunner.backoffMillis(1))
        assertEquals(2_000L, JobRunner.backoffMillis(2))
    }

    @Test
    fun `工人抛异常算未分类失败而不是炸掉整个任务`() = runBlocking {
        val worker = JobItemWorker { _, _ -> throw IllegalStateException("boom") }

        val final = runner(worker).run(job(listOf("a"))).toList().last()
        val a = final.item("a")!!

        assertEquals(JobItemStatus.FAILED, a.status)
        assertEquals(PictError.UNKNOWN, a.error)
        assertEquals("未分类错误按瞬时处理，跑满 3 次", 3, a.attempts)
    }

    // ---------- 取消 ----------

    @Test
    fun `取消后未开工的项记跳过 正在跑的项自己收尾`() = runBlocking {
        val cancellation = JobCancellation()
        val worker = JobItemWorker { _, _ ->
            cancellation.cancel()
            ItemResult.Done()
        }

        val final = runner(worker)
            .run(job(listOf("a", "b", "c"), options = JobOptions(concurrency = 1)), cancellation)
            .toList()
            .last()

        assertEquals(JobStatus.CANCELED, final.status)
        assertEquals("在跑的那一项照常报结果", 1, final.count(JobItemStatus.SUCCESS))
        assertEquals("剩下的不再开工", 2, final.count(JobItemStatus.SKIPPED))
        assertTrue(
            final.items.filter { it.status == JobItemStatus.SKIPPED }
                .all { it.note == Job.CANCELED_NOTE },
        )
    }

    @Test
    fun `开工前就被取消则一项都不碰`() = runBlocking {
        val cancellation = JobCancellation().apply { cancel() }
        val worker = ScriptedWorker()

        val final = runner(worker).run(job(listOf("a", "b")), cancellation).toList().last()

        assertEquals(JobStatus.CANCELED, final.status)
        assertTrue("取消之后一个文件都不该动", worker.calls.isEmpty())
        assertEquals(2, final.count(JobItemStatus.SKIPPED))
    }

    // ---------- 并发 ----------

    @Test
    fun `并发上限被严格执行`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val worker = JobItemWorker { _, _ ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { left, right -> maxOf(left, right) }
            delay(20)
            inFlight.decrementAndGet()
            ItemResult.Done()
        }

        val final = runner(worker)
            .run(job(List(6) { "i$it" }, options = JobOptions(concurrency = 2)))
            .toList()
            .last()

        assertEquals(2, peak.get())
        assertEquals(6, final.items.size)
        assertTrue(final.items.all { it.status == JobItemStatus.SUCCESS })
    }
}
