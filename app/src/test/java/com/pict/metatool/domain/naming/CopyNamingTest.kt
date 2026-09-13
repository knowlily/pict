package com.pict.metatool.domain.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批量另存时副本改名：撞名往后排「 (1)」「 (2)」。
 *
 * 编辑页的副本名交给 SAF 的「新建文档」对话框，Provider 自己会补编号、用户也看得见；
 * 批量的副本是程序建的（没有对话框、几百个一把梭），名字撞了要么覆盖别人、要么被 Provider
 * 悄悄改名而报告里还写着旧名——两种都很难解释。所以这一层得自己算清楚。
 */
class CopyNamingTest {

    @Test
    fun `没撞名就一个字都不改`() {
        val name = CopyNaming.unique("photo-edited.jpg", setOf("other.jpg", "photo.jpg"))

        assertEquals("photo-edited.jpg", name)
    }

    @Test
    fun `撞一次排一到扩展名之前`() {
        // 「名字 (1).jpg」不是「名字.jpg (1)」：后者扩展名不再是最后一段，很多图库认不出类型
        val name = CopyNaming.unique("photo-edited.jpg", setOf("photo-edited.jpg"))

        assertEquals("photo-edited (1).jpg", name)
    }

    @Test
    fun `号排到第一个空的为止，不重复用已经占掉的号`() {
        val taken = setOf("photo-edited.jpg", "photo-edited (1).jpg", "photo-edited (2).jpg")

        val name = CopyNaming.unique("photo-edited.jpg", taken)

        assertEquals("photo-edited (3).jpg", name)
    }

    @Test
    fun `大小写不敏感——SAF 背后可能是 exFAT，那里不区分大小写`() {
        // 按大小写敏感去比会算出「不撞」，然后建文件时才发现撞了
        val name = CopyNaming.unique("Photo-Edited.JPG", setOf("photo-edited.jpg"))

        assertEquals("Photo-Edited (1).JPG", name)
    }

    @Test
    fun `没有扩展名就往整名后面排号`() {
        val name = CopyNaming.unique("已编辑", setOf("已编辑"))

        assertEquals("已编辑 (1)", name)
    }

    @Test
    fun `前导点不算扩展名——不是真的后缀`() {
        val name = CopyNaming.unique(".hidden", setOf(".hidden"))

        assertEquals(".hidden (1)", name)
    }

    @Test
    fun `号排到 99 都没排开就交出去，不硬塞一个撞着的名字`() {
        // 硬塞撞着的名字 → Provider 悄悄改名 → 报告里写的名字与盘上的对不上，比报错难查
        val taken = HashSet<String>()
        taken += "photo.jpg"
        for (n in 1..CopyNaming.MAX_ATTEMPTS) taken += "photo ($n).jpg"

        val name = CopyNaming.unique("photo.jpg", taken)

        assertNull(name)
    }
}
