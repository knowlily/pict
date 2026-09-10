package com.pict.metatool.data.source

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.result.PictResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * docs/07 T2.11 / docs/08 §2：备份目录约定、保留期与撤销可用性的判定。
 *
 * 这里只测纯逻辑（[BackupManager] 的伴生对象）：SAF 目录读写要真机，见 docs/08 §3。
 */
class BackupManagerTest {

    private fun at(text: String): LocalDateTime = LocalDateTime.parse(text)

    private fun entry(folder: String, documentId: String = "primary:$folder"): BackupEntry =
        BackupEntry(
            folderName = folder,
            createdAt = at(BackupManager.parseFolderName(folder)?.toString() ?: "1970-01-01T00:00"),
            documentId = documentId,
            files = listOf(BackupFile(uri = "content://backup/$folder/IMG_1.jpg", name = "IMG_1.jpg", sizeBytes = 1024)),
        )

    private fun entryAt(text: String): BackupEntry = BackupEntry(
        folderName = BackupManager.folderName(at(text)),
        createdAt = at(text),
        documentId = "primary:${BackupManager.folderName(at(text))}",
    )

    // ---------------- 目录命名 ----------------

    @Test
    fun `备份目录名是十五位时间戳`() {
        val name = BackupManager.folderName(at("2026-09-10T11:35:00"))

        assertEquals("20260910_113500", name)
        assertEquals(15, name.length)
    }

    @Test
    fun `备份目录名与解析可往返`() {
        val times = listOf(
            "2026-09-10T11:35:00",
            "2026-01-01T00:00:00",
            "2026-12-31T23:59:59",
            "2024-02-29T12:00:00",
        )

        for (text in times) {
            val moment = at(text)
            assertEquals(moment, BackupManager.parseFolderName(BackupManager.folderName(moment)))
        }
    }

    @Test
    fun `解析拒绝长度不对的名字`() {
        assertNull(BackupManager.parseFolderName(""))
        assertNull(BackupManager.parseFolderName("20260910_11350"))
        assertNull(BackupManager.parseFolderName("20260910_1135007"))
        assertNull(BackupManager.parseFolderName("20260910_113500_1"))
    }

    @Test
    fun `解析拒绝分隔符不对的名字`() {
        assertNull(BackupManager.parseFolderName("20260910-113500"))
        assertNull(BackupManager.parseFolderName("202609101135000"))
    }

    @Test
    fun `解析拒绝不存在的日期与时刻`() {
        // SMART 解析会把 2 月 30 日顺延成 3 月 2 日，必须用 STRICT 拒掉
        assertNull(BackupManager.parseFolderName("20260230_000000"))
        assertNull(BackupManager.parseFolderName("20261332_000000"))
        assertNull(BackupManager.parseFolderName("20260910_240000"))
        assertNull(BackupManager.parseFolderName("20260910_113560"))
        assertNull(BackupManager.parseFolderName("20260910_096060"))
    }

    @Test
    fun `备份根目录约定是 Pict 下的 backup`() {
        assertEquals("Pict", BackupManager.ROOT_DIR)
        assertEquals("backup", BackupManager.BACKUP_DIR)
    }

    @Test
    fun `默认保留期是七天`() {
        assertEquals(7L, BackupManager.RETENTION_DAYS)
    }

    // ---------------- 保留期 ----------------

    @Test
    fun `保留期边界是满七天`() {
        val created = at("2026-09-01T10:00:00")

        assertFalse(BackupManager.isExpired(created, created.plus(7, ChronoUnit.DAYS).minusSeconds(1)))
        assertTrue(BackupManager.isExpired(created, created.plus(7, ChronoUnit.DAYS)))
        assertTrue(BackupManager.isExpired(created, created.plus(7, ChronoUnit.DAYS).plusSeconds(1)))
    }

    @Test
    fun `保留期内未过期`() {
        val created = at("2026-09-01T10:00:00")
        assertFalse(BackupManager.isExpired(created, created.plusDays(6).plusHours(23).plusMinutes(59)))
    }

    @Test
    fun `创建时刻本身不算过期`() {
        val created = at("2026-09-01T10:00:00")
        assertFalse(BackupManager.isExpired(created, created))
    }

    @Test
    fun `过期的备份按时间升序列出`() {
        val now = at("2026-09-20T00:00:00")
        val fresh = entryAt("2026-09-19T00:00:00")
        val older = entryAt("2026-09-01T00:00:00")
        val oldest = entryAt("2026-08-01T00:00:00")

        val expired = BackupManager.expiredEntries(listOf(fresh, older, oldest), now)

        assertEquals(listOf(oldest.folderName, older.folderName), expired.map { it.folderName })
    }

    @Test
    fun `刚过七天一分钟即过期`() {
        val now = at("2026-09-08T00:00:00")
        val created = entryAt("2026-09-01T00:00:00")

        assertTrue(BackupManager.isExpired(created.createdAt, now))
    }

    // ---------------- 最近一次 ----------------

    @Test
    fun `selectLatest 取最近一次`() {
        val older = entryAt("2026-09-01T00:00:00")
        val newer = entryAt("2026-09-05T00:00:00")

        assertEquals(newer.folderName, BackupManager.selectLatest(listOf(older, newer))?.folderName)
        assertEquals(newer.folderName, BackupManager.selectLatest(listOf(newer, older))?.folderName)
    }

    @Test
    fun `selectLatest 空列表返回 null`() {
        assertNull(BackupManager.selectLatest(emptyList()))
    }

    // ---------------- 撤销可用性（FR-34） ----------------

    @Test
    fun `没有备份时撤销报没有备份`() {
        val result = BackupManager.availability(emptyList(), at("2026-09-10T00:00:00"))

        assertEquals(PictError.BACKUP_MISSING, (result as PictResult.Failure).error)
    }

    @Test
    fun `只有过期备份时撤销报已过期`() {
        val now = at("2026-09-20T00:00:00")
        val result = BackupManager.availability(listOf(entryAt("2026-09-01T00:00:00")), now)

        assertEquals(PictError.BACKUP_EXPIRED, (result as PictResult.Failure).error)
    }

    @Test
    fun `早于保留期的旧备份不影响撤销最近一次`() {
        val now = at("2026-09-10T00:00:00")
        val stale = entryAt("2026-08-01T00:00:00")
        val fresh = entryAt("2026-09-09T00:00:00")

        val result = BackupManager.availability(listOf(stale, fresh), now)

        assertEquals(fresh.folderName, result.getOrNull()?.folderName)
    }

    @Test
    fun `最新一份正好满七天时报已过期`() {
        val created = at("2026-09-03T12:00:00")
        val result = BackupManager.availability(listOf(entryAt("2026-09-03T12:00:00")), created.plusDays(7))

        assertEquals(PictError.BACKUP_EXPIRED, (result as PictResult.Failure).error)
    }

    @Test
    fun `撤销取的是倒序列表里的第一份`() {
        val now = at("2026-09-10T00:00:00")
        val entries = listOf(
            entryAt("2026-09-09T00:00:00"),
            entryAt("2026-09-08T00:00:00"),
            entryAt("2026-09-07T00:00:00"),
        )

        assertEquals(entries.first().folderName, BackupManager.availability(entries, now).getOrNull()?.folderName)
    }

    // ---------------- 重名 ----------------

    @Test
    fun `重名时不冲突的名字原样返回`() {
        assertEquals("IMG_2.jpg", BackupManager.uniqueName("IMG_2.jpg", listOf("IMG_1.jpg")))
    }

    @Test
    fun `重名时加序号并避开已占用的序号`() {
        assertEquals("IMG_1 (2).jpg", BackupManager.uniqueName("IMG_1.jpg", listOf("IMG_1.jpg")))
        assertEquals(
            "IMG_1 (3).jpg",
            BackupManager.uniqueName("IMG_1.jpg", listOf("IMG_1.jpg", "IMG_1 (2).jpg")),
        )
    }

    @Test
    fun `没有扩展名时也在末尾加序号`() {
        assertEquals("IMG_1 (2)", BackupManager.uniqueName("IMG_1", listOf("IMG_1")))
    }

    @Test
    fun `多点文件名在最后一个点前加序号`() {
        assertEquals("a.b.c (2).jpg", BackupManager.uniqueName("a.b.c.jpg", listOf("a.b.c.jpg")))
    }

    @Test
    fun `只有扩展名的名字整体当基名`() {
        assertEquals(".jpg (2)", BackupManager.uniqueName(".jpg", listOf(".jpg")))
    }

    @Test
    fun `文件名两端的空白被去掉`() {
        assertEquals("IMG_1.jpg", BackupManager.uniqueName("  IMG_1.jpg  ", emptyList()))
    }

    @Test
    fun `空白名字用兜底名`() {
        assertEquals(BackupManager.DEFAULT_BACKUP_NAME, BackupManager.uniqueName("   ", emptyList()))
        assertEquals(
            "${BackupManager.DEFAULT_BACKUP_NAME} (2)",
            BackupManager.uniqueName("", listOf(BackupManager.DEFAULT_BACKUP_NAME)),
        )
    }

    @Test
    fun `重名判定区分大小写`() {
        assertEquals("img.jpg", BackupManager.uniqueName("img.jpg", listOf("IMG.JPG")))
    }

    // ---------------- 数据形状 ----------------

    @Test
    fun `副本字节数按未知记零`() {
        val entry = BackupEntry(
            folderName = "20260910_113500",
            createdAt = at("2026-09-10T11:35:00"),
            files = listOf(
                BackupFile(uri = "content://a", name = "a.jpg", sizeBytes = 1024),
                BackupFile(uri = "content://b", name = "b.jpg", sizeBytes = null),
            ),
        )

        assertEquals(1024L, entry.totalBytes)
        assertFalse(entry.isEmpty)
        assertTrue(BackupEntry("20260910_113500", at("2026-09-10T11:35:00")).isEmpty)
    }

    @Test
    fun `现在时刻取的是 UTC`() {
        val before = LocalDateTime.now(ZoneOffset.UTC)
        val actual = BackupManager.nowUtc()
        val after = LocalDateTime.now(ZoneOffset.UTC)

        assertFalse(actual.isBefore(before.minusSeconds(5)))
        assertFalse(actual.isAfter(after.plusSeconds(5)))
    }

    @Test
    fun `文件夹名解析后的时间戳与目录名一致`() {
        val entry = entry("20260910_113500")

        assertEquals("20260910_113500", BackupManager.folderName(entry.createdAt))
    }
}
