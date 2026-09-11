package com.pict.metatool.data.metadata.exif

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [ExifTagNumbers] 是**机器导出**的（库没公开「标签名 → tag 号」这层映射），
 * 所以必须有一条守门用例：拿库自己的表对账，androidx 升级换号/换名时当场失败，
 * 而不是让读侧悄悄开始错杀真标签。
 *
 * 库的表在 `ExifInterface.sExifTagMapsForReading`（private static），只能用反射读；
 * 万一哪天这个字段改名了，本用例会以 NoSuchFieldException 失败——那就按当时的库重新导出一份表。
 */
class ExifTagNumbersTest {

    /** 库认得、我们的表里故意不收的名字（[ExifMetadataStore.TAGS] 里也没有，只是记个总账）。 */
    private val knownGaps = setOf("LensSerialNumber")

    @Suppress("UNCHECKED_CAST")
    private fun libraryTable(): Map<String, Set<Int>> {
        val field = ExifInterface::class.java.getDeclaredField("sExifTagMapsForReading")
        field.isAccessible = true
        val maps = field.get(null) as Array<Map<Int, Any>>
        val table = HashMap<String, MutableSet<Int>>()
        maps.forEach { map ->
            map.forEach { (number, tag) ->
                // ExifTag 是包私有类，字段本身 public 也要先放开可访问性（跨包反射）
                val name = tag.javaClass.getField("name").also { it.isAccessible = true }.get(tag) as String
                table.getOrPut(name) { HashSet() }.add(number)
            }
        }
        return table
    }

    @Test
    fun `表里的 tag 号跟库自己的映射一致`() {
        val library = libraryTable()
        ExifTagNumbers.BY_NAME.forEach { (name, ids) ->
            val fromLibrary = library[name]
            assertNotNull("库的表里没有「$name」——这张表是从库里导出的，名字对不上说明库变了", fromLibrary)
            assertEquals(
                "「$name」的 tag 号跟库不一致（库换号了？重新导出这张表）",
                fromLibrary!!.sorted(),
                ids.sorted(),
            )
        }
    }

    @Test
    fun `TAGS 里库认得的标签 表里都得有 不认得的只允许是已知缺口`() {
        val library = libraryTable()
        val missed = ExifMetadataStore.TAGS
            .filter { library.containsKey(it) && ExifTagNumbers.idsOf(it) == null }
            .toSet()
        assertEquals("这些标签库认得、我们的表却漏了：$missed", emptySet<String>(), missed)

        val unknown = ExifMetadataStore.TAGS.filterNot { library.containsKey(it) }.toSet()
        assertEquals("库表里没有的名字应该只剩已知缺口（新名字请补进 knownGaps 并说明为什么不判定）", knownGaps, unknown)
    }
}
