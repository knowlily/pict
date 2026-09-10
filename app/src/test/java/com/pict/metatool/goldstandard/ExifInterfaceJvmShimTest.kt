package com.pict.metatool.goldstandard

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * 守住 JVM 跑通 ExifInterface 写路径的那块垫脚石。
 *
 * AGP 的 mockable `android.jar` 里 `android.util.Pair` 的构造器是空壳，
 * 而 androidx ExifInterface 的 `setAttribute` 拿它传值——于是任何 `setAttribute`
 * 都会在 `pair.first` 上 NPE。测试源集里那个真的 `android.util.Pair`
 * （src/test/java/android/util/Pair.java）就是为它准备的。
 *
 * 这两条断言看着多余，但一旦哪天依赖顺序变了、桩又抢回优先权，金标准的
 * [ExiftoolGoldStandardTest] 会以「生产通道写不进去」的样子集体变红；这里先把它钉住，
 * 让人一眼看出是垫脚石松了，而不是写通道坏了。
 */
class ExifInterfaceJvmShimTest {

    private val outDir: File = File("build/goldstandard/out").apply { mkdirs() }

    @Test
    fun `android util Pair 的替身在生效而不是空壳桩`() {
        val pair = android.util.Pair("first", "second")

        assertEquals("替身没生效：Pair.first 拿到了 null，说明又是那个空壳桩", "first", pair.first)
        assertEquals("second", pair.second)
    }

    @Test
    fun `JVM 单测里 ExifInterface 能把属性写回文件`() {
        val file = File(outDir, "shim-write-probe.jpg")
        javaClass.getResourceAsStream("/samples/canon-40d.jpg")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val before = ExifInterface(file).getAttribute(ExifInterface.TAG_MODEL)

        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MODEL, "PROBE MODEL")
            saveAttributes()
        }

        assertEquals("PROBE MODEL", ExifInterface(file).getAttribute(ExifInterface.TAG_MODEL))
        assertNotEquals("写前写后一个样，说明根本没落盘", "PROBE MODEL", before)
    }
}
