package com.pict.metatool.data.job

import com.pict.metatool.core.result.PictResult
import com.pict.metatool.domain.batch.BatchDraft
import com.pict.metatool.domain.batch.BatchMode
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.job.JobOptions
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.plan.ClearTarget
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.preset.PresetTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.3：任务定义的落盘与还原。
 *
 * 这一层的意义全在「worker 复活后还能知道要跑什么」，所以测试的重点不是字段搬运，
 * 而是**来回转一圈之后还是同一份东西**——少一个字段，用户排的队就跑成了另一个任务。
 */
class BatchJobSpecTest {

    private val device = PresetTestSupport.preset("device.iphone-16-pro")
    private val catalog = PresetTestSupport.catalog(device)

    private val readable = BatchTarget.of("content://pict/a.jpg", "a.jpg", ImageFormatHint.JPEG)
    private val readonly = BatchTarget.of("content://pict/b.heic", "b.heic", ImageFormatHint.HEIF, writable = false)

    private fun spec(
        draft: BatchDraft = BatchDraft(mode = BatchMode.PRESET, presetId = device.id, seed = 7L),
        options: JobOptions = JobOptions(),
    ) = BatchJobSpec.from(
        draft = draft,
        targets = listOf(readable, readonly),
        options = options,
        jobId = "job-1726000000000-abcd1234",
        nowMillis = 1_726_000_000_000L,
        presetName = device.name,
    )

    // ---------- 攒定义 ----------

    @Test
    fun `项按序号编号，授权位跟着项快照走`() {
        val built = spec()

        assertEquals(listOf("item-0", "item-1"), built.items.map { it.id })
        assertEquals(mapOf("item-0" to 0, "item-1" to 1), built.indices)
        assertEquals(mapOf("item-0" to true, "item-1" to false), built.writableByIds)
        assertEquals("a.jpg", built.items[0].displayName)
        assertEquals(ImageFormatHint.HEIF, built.items[1].format)
        assertEquals("job-1726000000000-abcd1234", built.jobId)
    }

    @Test
    fun `dry-run 定义照样能攒——禁的是碰图片，不是记账`() {
        val built = spec(options = JobOptions(dryRun = true))

        assertTrue(built.dryRun)
        assertTrue(built.options.dryRun)
        assertEquals(2, built.items.size)
    }

    // ---------- 来回转 ----------

    @Test
    fun `定义转一圈 JSON 回来还是同一份`() {
        val original = spec(
            draft = BatchDraft(
                mode = BatchMode.CLEAR,
                clearTargets = setOf(ClearTarget.THUMBNAIL, ClearTarget.GPS),
                overwriteExisting = true,
                seed = 11L,
            ),
            options = JobOptions(concurrency = 3, maxRetries = 1, dryRun = true),
        )

        val restored = BatchJobSpec.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    @Test
    fun `清除目标按枚举顺序落盘，读回来顺序稳定`() {
        val built = spec(
            draft = BatchDraft(
                mode = BatchMode.CLEAR,
                clearTargets = setOf(ClearTarget.ICC, ClearTarget.GPS, ClearTarget.DEVICE),
            ),
        )

        assertEquals(listOf(ClearTarget.GPS, ClearTarget.DEVICE, ClearTarget.ICC), built.clearTargets)
        assertEquals(built.clearTargets, BatchJobSpec.fromJson(built.toJson())?.clearTargets)
    }

    @Test
    fun `烂 JSON、未知模式、缺 id 都当没有`() {
        assertNull(BatchJobSpec.fromJson("不是 JSON"))
        assertNull(BatchJobSpec.fromJson("{}"))
        assertNull(BatchJobSpec.fromJson("""{"jobId":"j","mode":"SPRAY_AND_PRAY"}"""))
        assertNull(BatchJobSpec.fromJson("""{"mode":"CLEAR"}"""))
    }

    @Test
    fun `读回来时坏项被丢掉，好项留着`() {
        val json = spec().toJson().replace(""""id":"item-1",""", """"id":null,""")

        val restored = BatchJobSpec.fromJson(json)

        assertNotNull(restored)
        assertEquals(listOf("item-0"), restored!!.items.map { it.id })
    }

    // ---------- 拼计划 / 拼任务 ----------

    @Test
    fun `拼出来的计划与预览同源且带上 dry-run 与备份标志`() {
        val built = spec(options = JobOptions(dryRun = true))

        val plan = (built.toPlan(catalog) as PictResult.Success).value

        assertTrue(plan.dryRun)
        assertTrue(plan.backupBeforeOverwrite)
        assertEquals(1, plan.operations.size)
        val op = plan.operations.single() as EditOperation.ApplyPreset
        assertEquals(device.id, op.presetId)
        assertEquals(7L, op.seed)
    }

    @Test
    fun `预设没了就拼不出计划`() {
        val built = spec(draft = BatchDraft(mode = BatchMode.PRESET, presetId = "device.不存在"))

        val result = built.toPlan(catalog)

        assertTrue(result is PictResult.Failure)
    }

    @Test
    fun `拼出来的任务项与定义一一对应`() {
        val built = spec()
        val job = built.toJob()

        assertEquals(built.jobId, job.id)
        assertEquals(built.label, job.label)
        assertEquals(built.createdAtMillis, job.createdAtMillis)
        assertEquals(built.items.map { it.id }, job.items.map { it.id })
        assertEquals(built.items.map { it.uri }, job.items.map { it.uri })
        assertEquals("a.jpg", job.items[0].source.displayName)
        assertEquals(built.options, job.options)
    }

    // ---------- 标签 ----------

    @Test
    fun `标签说清做什么与多少张`() {
        assertEquals("套用预设「${device.name}」 · 2 张", spec().label)

        val random = spec(draft = BatchDraft(mode = BatchMode.RANDOM, presetId = device.id, seed = 1L))
        assertTrue(random.label.startsWith("按「"))
        assertTrue(random.label.endsWith(" · 2 张"))

        val cleared = spec(
            draft = BatchDraft(
                mode = BatchMode.CLEAR,
                clearTargets = setOf(ClearTarget.GPS, ClearTarget.DEVICE, ClearTarget.TIME),
            ),
        )
        assertTrue(cleared.label.startsWith("清除"))
        assertTrue(cleared.label.contains("等 3 组"))

        val unnamed = BatchJobSpec.labelOf(BatchDraft(mode = BatchMode.RANDOM, presetId = "x"), 5)
        assertEquals("随机填充 · 5 张", unnamed)
    }
}
