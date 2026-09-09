package com.pict.metatool.data.metadata

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.core.result.failureOf
import com.pict.metatool.core.result.successOf
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.7 路由与合并（docs/07 T1.7）。
 *
 * 这里只测纯逻辑：[MetadataReader.storesFor] 的选路、[MetadataReader.combine] 的合并与来源标注、
 * [MetadataReader.resolve] 的成败判定。真实 IO 由各 store 自己的测试覆盖。
 */
class MetadataReaderTest {

    private val jpeg = SourceInfo("a.jpg", "image/jpeg", 1024L, ImageFormatHint.JPEG)
    private val tiff = SourceInfo("a.tif", "image/tiff", 1024L, ImageFormatHint.TIFF)
    private val raw = SourceInfo("a.nef", null, 1024L, ImageFormatHint.RAW)
    private val bmp = SourceInfo("a.bmp", "image/bmp", 1024L, ImageFormatHint.BMP)
    private val unknown = SourceInfo("noext", null, null, ImageFormatHint.UNKNOWN)

    private val reader = MetadataReader()

    private fun ids(info: SourceInfo): List<String> = reader.storesFor(info).map { it.id }

    private fun out(id: String, vararg entries: Pair<String, TagValue>): StoreOutput = StoreOutput(
        id = id,
        result = successOf(
            MetadataSet(
                source = jpeg,
                entries = entries.associate { TagKey.of(it.first) to it.second },
            ),
        ),
    )

    private fun failed(id: String, detail: String = "读不出来"): StoreOutput =
        StoreOutput(id, failureOf(PictError.META_PARSE, detail))

    // ---------- 路由 ----------

    @Test
    fun `JPEG 只走 extractor 与 exif——imaging 不参与`() {
        assertEquals(listOf("extractor", "exif"), ids(jpeg))
    }

    @Test
    fun `TIFF 把 imaging 排在最后——IFD 直读优先于 ExifInterface`() {
        assertEquals(listOf("extractor", "exif", "imaging"), ids(tiff))
    }

    @Test
    fun `RAW 目前是 extractor 与 imaging——exif store 尚未支持 RAW`() {
        // docs/02 要求 RAW 以 ExifInterface 为准，但 T1.4 的 ExifMetadataStore 把 RAW 排除了
        // （见 ExifMetadataStoreTest），所以此刻只有这两个读取器参与。
        // ORDER 里给 RAW 留了 exif 末位，等它补上 RAW 支持就自动按 docs/02 生效。
        assertEquals(listOf("extractor", "imaging"), ids(raw))
    }

    @Test
    fun `BMP 只有 extractor 能读`() {
        assertEquals(listOf("extractor"), ids(bmp))
    }

    @Test
    fun `格式识别不出来时全部读取器尽力一试`() {
        // supports 只做纯格式判断，识别失败不代表文件读不了 → 退化到全量
        assertEquals(listOf("extractor", "exif", "imaging"), ids(unknown))
    }

    @Test
    fun `没有注册读取器时选路为空`() {
        assertTrue(MetadataReader(emptyList()).storesFor(jpeg).isEmpty())
    }

    // ---------- 合并与来源标注 ----------

    @Test
    fun `高优先级覆盖低优先级，且记录全部来源`() {
        val low = out(
            "extractor",
            "EXIF:Make" to TagValue.Text("来自 extractor"),
            "IPTC:City" to TagValue.Text("苏州"),
        )
        val high = out("exif", "EXIF:Make" to TagValue.Text("来自 exif"))

        val result = MetadataReader.combine(listOf(low, high), jpeg)

        assertEquals(TagValue.Text("来自 exif"), result.set["EXIF:Make"])
        assertEquals(listOf("extractor", "exif"), result.originOf("EXIF:Make"))
        assertEquals("exif", result.effectiveOrigin("EXIF:Make"))
        // 只有一家提供的字段，来源就一个
        assertEquals(listOf("extractor"), result.originOf("IPTC:City"))
        assertEquals("extractor", result.effectiveOrigin("IPTC:City"))
        assertEquals(2, result.set.size)
    }

    @Test
    fun `失败的读取器不影响成功字段，只记进 failures`() {
        val ok = out("extractor", "EXIF:Make" to TagValue.Text("PictTest"))
        val bad = failed("exif", "URI 授权已失效")

        val result = MetadataReader.combine(listOf(ok, bad), jpeg)

        assertEquals(TagValue.Text("PictTest"), result.set["EXIF:Make"])
        assertEquals(mapOf("exif" to "URI 授权已失效"), result.failures)
        assertEquals(listOf("extractor"), result.contributors)
        assertEquals(listOf("extractor", "exif"), result.attempted)
    }

    @Test
    fun `contributors 只算真正产出字段的读取器`() {
        val a = out("extractor", "EXIF:Make" to TagValue.Text("A"))
        // 成功但一个字段都没读到（例如 BMP 只有文件属性）
        val b = StoreOutput("exif", successOf(MetadataSet.empty(ImageFormatHint.JPEG)))
        val c = failed("imaging")

        val result = MetadataReader.combine(listOf(a, b, c), jpeg)

        assertEquals(listOf("extractor"), result.contributors)
        assertEquals(listOf("extractor", "exif", "imaging"), result.attempted)
        assertTrue(result.failures.containsKey("imaging"))
    }

    @Test
    fun `合并结果带上传入的 SourceInfo`() {
        val result = MetadataReader.combine(listOf(out("exif", "EXIF:Make" to TagValue.Text("A"))), tiff)
        assertEquals(tiff, result.set.source)
    }

    // ---------- 成败判定 ----------

    @Test
    fun `只要有一个读取器成功，整次读取就算成功`() {
        val result = reader.resolve(listOf(out("exif", "EXIF:Make" to TagValue.Text("A")), failed("extractor")), jpeg)
        assertTrue(result is PictResult.Success)
        assertEquals(TagValue.Text("A"), result.getOrNull()?.set?.get("EXIF:Make"))
    }

    @Test
    fun `全部读取器失败才算失败`() {
        val result = reader.resolve(listOf(failed("exif", "坏了"), failed("extractor", "也坏了")), jpeg)
        assertTrue("应为 Failure，实际 $result", result is PictResult.Failure)
        val detail = (result as PictResult.Failure).failure.detail.orEmpty()
        assertTrue("失败摘要应带上各读取器原因，实际：$detail", detail.contains("exif=坏了") && detail.contains("extractor=也坏了"))
    }

    @Test
    fun `读取器列表为空直接失败，不静默返回空结果`() {
        val result = reader.resolve(emptyList(), jpeg)
        assertTrue(result is PictResult.Failure)
        assertEquals(PictError.META_PARSE, (result as PictResult.Failure).error)
    }
}
