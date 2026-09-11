package com.pict.metatool.data.settings

import com.pict.metatool.domain.settings.RecentFolder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「最近目录」的存盘编码（FR-03）。
 *
 * 盘上这串字是要跨版本活很久的：这里锁住「存进去什么、读出来就是什么」，
 * 以及**坏行只丢自己**——pref 被手改过不该让图库页打不开。
 */
class RecentFolderCodecTest {

    private val camera = RecentFolder(
        uri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
        name = "Camera",
        usedAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun `存了再读是同一份`() {
        assertEquals(listOf(camera), RecentFolderCodec.decode(RecentFolderCodec.encode(listOf(camera))))
    }

    @Test
    fun `没存过是空列表而不是报错`() {
        assertEquals(emptyList<RecentFolder>(), RecentFolderCodec.decode(null))
        assertEquals(emptyList<RecentFolder>(), RecentFolderCodec.decode(""))
    }

    @Test
    fun `多条的先后顺序原样保留`() {
        val download = camera.copy(
            uri = "content://com.android.providers.downloads.documents/tree/downloads%3ADownload",
            name = "Download",
            usedAtMillis = 1L,
        )
        val roundTrip = RecentFolderCodec.decode(RecentFolderCodec.encode(listOf(camera, download)))
        assertEquals(listOf("Camera", "Download"), roundTrip.map { it.name })
    }

    @Test
    fun `名字里的换行与分隔符被换成空格：串不到下一条去`() {
        val messy = camera.copy(name = "Cam\nmera\u001F2")
        val encoded = RecentFolderCodec.encode(listOf(messy, camera.copy(name = "Download")))
        val decoded = RecentFolderCodec.decode(encoded)
        assertEquals(2, decoded.size)
        assertEquals("Cam mera 2", decoded.first().name)
    }

    @Test
    fun `坏行只丢自己，好行照读`() {
        val good = RecentFolderCodec.encode(listOf(camera))
        val decoded = RecentFolderCodec.decode(
            "少了一段\u001F这不是三条\n$good\n时间不是数字\u001Fcontent://tree/x\u001Fabc",
        )
        assertEquals(listOf("Camera"), decoded.map { it.name })
    }
}
