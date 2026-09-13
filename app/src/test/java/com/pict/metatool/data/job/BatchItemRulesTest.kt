package com.pict.metatool.data.job

import com.pict.metatool.data.metadata.MetadataVerifier
import com.pict.metatool.domain.job.ItemResult
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import com.pict.metatool.domain.plan.EditOperation
import com.pict.metatool.domain.plan.EditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 T5.3、docs/01 FR-32 / FR-33：单项执行的**判断**部分。
 *
 * 这些分支在设备上很难撞上（只读授权、校验未通过、读回失败都要真文件真权限），
 * 而它们恰好是「批量会不会悄悄写坏图」的分界线，所以在这里逐条钉住。
 */
class BatchItemRulesTest {

    private val make = TagKey.of("EXIF:Make")
    private val model = TagKey.of("EXIF:Model")

    private fun set(vararg entries: Pair<TagKey, TagValue>): MetadataSet =
        MetadataSet(
            source = SourceInfo("a.jpg", "image/jpeg", 2_048L, ImageFormatHint.JPEG),
            entries = entries.toMap(),
        )

    // ---------- 开工前拦停 ----------

    @Test
    fun `只读授权报只读，不冒充格式不支持`() {
        val blocked = BatchItemRules.blockBeforeRead(writable = false, format = ImageFormatHint.HEIF, hasWriter = true)

        assertNotNull(blocked)
        assertTrue(blocked!!.note.contains("只读"))
    }

    @Test
    fun `格式没人会写才算不支持`() {
        val blocked = BatchItemRules.blockBeforeRead(writable = true, format = ImageFormatHint.HEIF, hasWriter = false)

        assertNotNull(blocked)
        assertTrue(blocked!!.note.contains("不支持原地写"))
    }

    @Test
    fun `又只读又没写通道时先报只读——换格式没用，该去重新授权`() {
        val blocked = BatchItemRules.blockBeforeRead(writable = false, format = ImageFormatHint.HEIF, hasWriter = false)

        assertTrue(blocked!!.note.contains("只读"))
    }

    @Test
    fun `另存模式下只读来源不拦——改的是旁边的副本，原文件不开写通道`() {
        val blocked = BatchItemRules.blockBeforeRead(
            writable = false,
            format = ImageFormatHint.JPEG,
            hasWriter = true,
            writesInPlace = false,
        )

        assertNull(blocked)
    }

    @Test
    fun `另存模式下格式写不了照样得拦——副本也是这个格式`() {
        val blocked = BatchItemRules.blockBeforeRead(
            writable = true,
            format = ImageFormatHint.HEIF,
            hasWriter = false,
            writesInPlace = false,
        )

        assertNotNull(blocked)
        assertTrue(blocked!!.note.contains("不支持写元数据"))
        // 另存模式别提「原地」：这时根本没打算碰源文件
        assertFalse(blocked.note.contains("原地"))
    }

    @Test
    fun `能写就放行`() {
        assertNull(BatchItemRules.blockBeforeRead(writable = true, format = ImageFormatHint.JPEG, hasWriter = true))
    }

    // ---------- 写后记账 ----------

    @Test
    fun `读回一致才算成功`() {
        val before = set(make to TagValue.Text("Canon"))
        val target = before.with(model, TagValue.Text("EOS R5"))
        val after = set(make to TagValue.Text("Canon"), model to TagValue.Text("EOS R5"))

        val report = MetadataVerifier.compare(before, target, after)
        val result = BatchItemRules.resultOf(setOf(model), report)

        assertTrue(report.isLossless)
        assertEquals(ItemResult.Done(changedKeys = setOf(model)), result)
    }

    @Test
    fun `读回来了但对不上算校验未通过`() {
        val before = set(make to TagValue.Text("Canon"))
        val target = before.with(model, TagValue.Text("EOS R5"))
        val after = set(make to TagValue.Text("Canon"), model to TagValue.Text("别的机型"))

        val result = BatchItemRules.resultOf(setOf(model), MetadataVerifier.compare(before, target, after))

        assertTrue(result is ItemResult.VerifyFailed)
    }

    @Test
    fun `读不回来也算校验未通过——证不了就不算成功`() {
        val result = BatchItemRules.resultOf(setOf(model), report = null)

        assertTrue(result is ItemResult.VerifyFailed)
        assertTrue((result as ItemResult.VerifyFailed).detail!!.contains("读回失败"))
    }

    @Test
    fun `dry-run 的收尾只报键，不落盘`() {
        val result = BatchItemRules.dryRunResult(setOf(make, model))

        assertEquals(ItemResult.Done(changedKeys = setOf(make, model)), result)
    }

    @Test
    fun `声明装不下的键不算校验未通过，但成功里要留一句`() {
        val xmpModel = TagKey.of("XMP:tiff:Model")
        val before = set(make to TagValue.Text("Canon"))
        val target = before
            .with(model, TagValue.Text("EOS R5"))
            .with(xmpModel, TagValue.Text("EOS R5"))
        val after = set(make to TagValue.Text("Canon"), model to TagValue.Text("EOS R5"))

        val result = BatchItemRules.resultOf(
            setOf(model),
            MetadataVerifier.compare(before, target, after, dropped = setOf(xmpModel)),
        )

        assertEquals(ItemResult.Done(changedKeys = setOf(model), note = "1 项格式存不下"), result)
    }

    @Test
    fun `校验未通过也带上改过的键——计数不该显示成 0`() {
        val before = set(make to TagValue.Text("Canon"))
        val target = before.with(model, TagValue.Text("EOS R5"))
        val after = set(make to TagValue.Text("Canon"), model to TagValue.Text("别的机型"))

        val result = BatchItemRules.resultOf(setOf(model), MetadataVerifier.compare(before, target, after))

        assertEquals(setOf(model), (result as ItemResult.VerifyFailed).changedKeys)
    }

    @Test
    fun `另存收尾跟原地同一套判定，另外把副本地址与名字带上`() {
        val before = set(make to TagValue.Text("Canon"))
        val target = before.with(model, TagValue.Text("EOS R5"))
        val after = set(make to TagValue.Text("Canon"), model to TagValue.Text("EOS R5"))

        val result = BatchItemRules.resultOfCopy(
            changedKeys = setOf(model),
            report = MetadataVerifier.compare(before, target, after),
            outputUri = "content://tree/copy/1",
            copyName = "a-edited.jpg",
        )

        val done = result as ItemResult.Done
        assertEquals("content://tree/copy/1", done.outputUri)
        assertTrue(done.note!!.contains("另存成 a-edited.jpg"))
    }

    @Test
    fun `另存收尾不换判定：读不回来一样算校验未通过`() {
        val result = BatchItemRules.resultOfCopy(
            changedKeys = setOf(model),
            report = null,
            outputUri = "content://tree/copy/1",
            copyName = "a-edited.jpg",
        )

        assertTrue(result is ItemResult.VerifyFailed)
    }

    @Test
    fun `另存时校验没过也要说清副本在哪儿——别留个半成品无声无息在相册里`() {
        val before = set(make to TagValue.Text("Canon"))
        val target = before.with(model, TagValue.Text("EOS R5"))
        val after = set(make to TagValue.Text("Canon"), model to TagValue.Text("别的机型"))

        val result = BatchItemRules.resultOfCopy(
            changedKeys = setOf(model),
            report = MetadataVerifier.compare(before, target, after),
            outputUri = "content://tree/copy/1",
            copyName = "a-edited.jpg",
        ) as ItemResult.VerifyFailed

        assertTrue(result.detail!!.contains("a-edited.jpg"))
    }

    // ---------- 种子错开 ----------

    @Test
    fun `按序号错开种子，且第 0 项与单张编辑一致`() {
        val preset = EditPlan(listOf(EditOperation.ApplyPreset("device.iphone-16-pro", overwriteExisting = false, seed = 5L)))

        assertEquals(preset, BatchItemRules.planFor(preset, 0))
        val second = BatchItemRules.planFor(preset, 2).operations.single() as EditOperation.ApplyPreset
        assertEquals(7L, second.seed)
    }
}
