package com.pict.metatool.data.metadata.exif

import androidx.exifinterface.media.ExifInterface
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.Rational
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真机样本回归：**应用自己写过的 JPEG，读回时曝光字段还在不在**（T5.3）。
 *
 * 背景：批量执行在真机上跑完，每条都是 `VERIFY_FAILED`，原因是
 * 「缺失 5 项[EXIF:ExposureTime、EXIF:FNumber、XMP:tiff:Make、XMP:tiff:Model …]」
 * —— 而 exiftool 在同一个文件里明明看得到 `ExifIFD:ExposureTime = 1/250`、`FNumber = 7.3`。
 *
 * 于是把真机写出的样张（`device-written.jpg`）收进测试资源：问题不在写、在读。
 * `ExifInterface` 对这两个标签返回十进制（`0.004`、`7.3`），早先的解析只认真分数，
 * 两个键就被静默丢掉了。文件没换、断言照着文件里的值写，所以这条用例红了就是读路径又退化了。
 */
class WrittenFileReadBackTest {

    private val store = ExifMetadataStore()

    private fun load(name: String): Map<TagKey, TagValue> {
        val file = File("src/test/resources/samples/$name")
        assertTrue("样本缺失：${file.absolutePath}", file.exists())
        val info = SourceInfo(name, "image/jpeg", file.length(), ImageFormatHint.JPEG)
        return store.readFrom(ExifInterface(file), info, file.readBytes()).entries
    }

    @Test
    fun `应用写过的文件里曝光时间与光圈读得回来`() {
        val written = load("device-written.jpg")

        // 预设改过的两个值（exiftool 复核过：1/250、7.3）
        assertEquals(
            TagValue.RationalValue(Rational(1, 250)),
            written[TagKey.of("EXIF:ExposureTime")],
        )
        assertEquals(
            TagValue.RationalValue(Rational(73, 10)),
            written[TagKey.of("EXIF:FNumber")],
        )
        // 写路径确实改到了 IFD0
        assertEquals(TagValue.Text("Canon EOS R6m2"), written[TagKey.of("EXIF:Model")])
    }

    @Test
    fun `原样样张的曝光时间与光圈也一直读得到`() {
        // 文件里本来就有（exiftool：1/160、7.1），早先读不出来才有人断言「该样本没有 FNumber」
        val pristine = load("canon-40d.jpg")

        assertEquals(
            TagValue.RationalValue(Rational(1, 160)),
            pristine[TagKey.of("EXIF:ExposureTime")],
        )
        assertEquals(
            TagValue.RationalValue(Rational(71, 10)),
            pristine[TagKey.of("EXIF:FNumber")],
        )
    }
}
