package com.pict.metatool.data.job

import com.pict.metatool.core.result.PictResult
import com.pict.metatool.domain.batch.BatchDraft
import com.pict.metatool.domain.batch.BatchMode
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.model.ImageFormatHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * docs/07 T5.3：定义的落盘。
 *
 * 用真的临时目录而不是假文件系统：这里要验的恰好是「写坏了怎么办」
 * （半截 JSON、残留 .tmp、路径穿越），假实现验不出来。
 */
class JobSpecStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(root: File = folder.root) = JobSpecStore(File(root, JobSpecStore.DIR))

    private fun spec(jobId: String = "job-1-abcd") = BatchJobSpec.from(
        draft = BatchDraft(mode = BatchMode.RANDOM, presetId = "device.iphone-16-pro", seed = 3L),
        targets = listOf(BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.JPEG)),
        options = JobOptions(),
        jobId = jobId,
        nowMillis = 1_726_000_000_000L,
    )

    @Test
    fun `存下来能原样读回`() {
        val store = store()
        val original = spec()

        val saved = store.save(original)

        assertTrue(saved is PictResult.Success)
        assertEquals(original, store.load(original.jobId))
    }

    @Test
    fun `没存过的任务读回 null，不是空定义`() {
        assertNull(store().load("job-不存在"))
    }

    @Test
    fun `删掉之后就真没了，且不留下半截文件`() {
        val store = store()
        val original = spec()
        store.save(original)

        store.delete(original.jobId)

        assertNull(store.load(original.jobId))
        assertEquals(emptyList<String>(), store.ids())
        val leftover = File(folder.root, JobSpecStore.DIR).listFiles().orEmpty().map { it.name }
        assertFalse(leftover.any { it.endsWith(".tmp") })
    }

    @Test
    fun `坏文件只影响它自己，不影响别的任务`() {
        val store = store()
        val good = spec("job-1-good")
        val broken = spec("job-2-broken")
        store.save(good)
        store.save(broken)
        store.fileOf(broken.jobId).writeText("{ 半截")

        assertEquals(good, store.load(good.jobId))
        assertNull(store.load(broken.jobId))
        assertEquals(setOf("job-2-broken", "job-1-good"), store.ids().toSet())
    }

    @Test
    fun `快照文件不算一个任务 id`() {
        val store = store()
        val original = spec()
        store.save(original)
        // 同一个目录里还躺着执行态快照（JobSnapshotStore）：它同样以 .json 结尾，
        // 早先会被 ids() 当成任务报出去（job-1-abcd.snapshot），历史列表就会多出幽灵条目
        File(store.fileOf(original.jobId).parentFile, "${original.jobId}${JobSnapshotStore.SUFFIX}")
            .writeText("{}")

        assertEquals(listOf(original.jobId), store.ids())
    }

    @Test
    fun `定义文件里的 id 拼不成路径穿越`() {
        val store = store()

        val rejected = store.save(spec("../../etc/passwd"))

        assertTrue(rejected is PictResult.Failure)
        assertNull(store.load("../../etc/passwd"))
        assertFalse(File(folder.root.parentFile, "etc/passwd").exists())
    }

    @Test
    fun `生成的 id 一定能当文件名`() {
        val id = JobIds.newId(1_726_000_000_000L, "a-b!c/../d")

        assertTrue(JobSpecStore.isSafeId(id))
        assertEquals("job-1726000000000-abcd", id)
    }
}
