package com.pict.metatool.data.metadata

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pict.metatool.data.metadata.exif.ExifMetadataStore
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.MetadataSet
import com.pict.metatool.domain.model.SourceInfo
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 无损写入的端到端校验（docs/07 T2.4）。
 *
 * 需要真实解码器和 [android.content.ContentResolver]，所以放在设备上跑；JVM 侧只覆盖比对逻辑。
 */
@RunWith(AndroidJUnit4::class)
class MetadataVerifierInstrumentedTest {

    private val store = ExifMetadataStore()
    private val hasher = BitmapPixelHasher()

    @Test
    fun 改元数据后像素指纹不变且校验通过() = runBlocking {
        val file = sampleFile()
        val uri = Uri.fromFile(file)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val info = infoOf(file)

        val before = store.readFrom(ExifInterface(file), info, file.readBytes())
        val fingerprintBefore = hasher.hashOf(resolver, uri).getOrNull()
        assertNotNull("样本应能算出像素指纹", fingerprintBefore)

        val target = MetadataSet(info, before.entries + (MAKE to TagValue.Text("PictTool")))
        store.writeTo(ExifInterface(file), target)

        val after = store.readFrom(ExifInterface(file), info, file.readBytes())
        val fingerprintAfter = hasher.hashOf(resolver, uri).getOrNull()

        val report = MetadataVerifier.compare(before, target, after, fingerprintBefore, fingerprintAfter)

        assertEquals(TagValue.Text("PictTool"), after.entries[MAKE])
        assertEquals("像素指纹应保持一致", fingerprintBefore, fingerprintAfter)
        assertTrue(report.summary(), report.isLossless)
    }

    @Test
    fun 像素指纹能区分不同图像() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val red = solidImage(dir, "solid-red.jpg", Color.RED)
        val blue = solidImage(dir, "solid-blue.jpg", Color.BLUE)

        // 同一份像素 → 同一个指纹
        val first = hasher.hashOf(resolver, Uri.fromFile(red)).getOrNull()
        val second = hasher.hashOf(resolver, Uri.fromFile(red)).getOrNull()
        assertEquals(first, second)

        // 不同像素 → 不同指纹
        val other = hasher.hashOf(resolver, Uri.fromFile(blue)).getOrNull()
        assertNotNull(other)
        assertTrue("不同图像不该给出相同指纹", first != other)
    }

    private fun sampleFile(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = instrumentation.targetContext.cacheDir
        dir.mkdirs()
        val file = File(dir, "verifier-${System.nanoTime()}.jpg")
        instrumentation.context.assets.open("canon-40d.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun solidImage(dir: File, name: String, color: Int): File {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun infoOf(file: File) =
        SourceInfo(file.name, "image/jpeg", file.length(), ImageFormatHint.JPEG)

    private companion object {
        val MAKE = TagKey.of("EXIF:Make")
    }
}
