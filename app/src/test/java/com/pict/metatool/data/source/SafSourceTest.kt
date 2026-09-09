package com.pict.metatool.data.source

import android.provider.DocumentsContract
import com.pict.metatool.domain.model.ImageFormatHint
import com.pict.metatool.domain.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.8 纯逻辑测试：目录跳过、图片判定、列→[ImageItem] 映射、排序。
 *
 * 遍历本身（`DocumentsContract` + `ContentResolver` 查询）依赖 Provider，
 * 单测环境没有 Robolectric，按 docs/08 放到真机/MuMu 验证，这里只钉住可纯函数化的规则。
 */
class SafSourceTest {

    private fun item(
        name: String,
        size: Long? = null,
        modified: Long? = null,
        flags: Int = 0,
        uri: String = "content://test/document/$name",
        mime: String? = null,
    ): ImageItem = SafSource.buildItem(
        uri = uri,
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        lastModified = modified,
        flags = flags,
        origin = ImageItem.Origin.FOLDER_SCAN,
    )

    // ---------------- 目录跳过 ----------------

    @Test
    fun `隐藏目录与系统目录跳过`() {
        listOf(".thumbnails", ".nomedia", ".hidden", "Android", "android", "LOST.DIR", "lost.dir")
            .forEach { assertTrue("应跳过：$it", SafSource.shouldSkipDirectory(it)) }
    }

    @Test
    fun `普通目录不跳过`() {
        listOf("DCIM", "Pictures", "Camera", "我的相册", "2026-09").forEach {
            assertFalse("不应跳过：$it", SafSource.shouldSkipDirectory(it))
        }
    }

    @Test
    fun `空名目录跳过——查询拿不到名称时不当成可遍历目录`() {
        assertTrue(SafSource.shouldSkipDirectory(""))
        assertTrue(SafSource.shouldSkipDirectory("   "))
    }

    // ---------------- 图片判定 ----------------

    @Test
    fun `扩展名已知即收——含 docs05 里的 RAW 扩展名`() {
        listOf("a.JPG", "a.jpeg", "a.png", "a.webp", "a.heic", "a.avif", "a.bmp", "a.tif", "a.dng",
            "a.cr2", "a.nef", "a.arw", "a.raf", "a.orf", "a.rw2", "a.pef", "a.srw", "a.nrw")
            .forEach { assertTrue("应收：$it", SafSource.isImageCandidate(it, null)) }
    }

    @Test
    fun `MIME 是 image 开头就收——哪怕没有扩展名`() {
        assertTrue(SafSource.isImageCandidate(null, "image/jpeg"))
        assertTrue(SafSource.isImageCandidate("无扩展名", "image/heic"))
    }

    @Test
    fun `非图片不收`() {
        assertFalse(SafSource.isImageCandidate("notes.txt", "text/plain"))
        assertFalse(SafSource.isImageCandidate("video.mp4", "video/mp4"))
        assertFalse(SafSource.isImageCandidate(null, null))
        assertFalse(SafSource.isImageCandidate("", null))
    }

    @Test
    fun `目录 MIME 不收——即使名字带图片扩展名`() {
        val dirMime = DocumentsContract.Document.MIME_TYPE_DIR
        assertFalse(SafSource.isImageCandidate("fake.jpg", dirMime))
        assertFalse(SafSource.isImageCandidate("DCIM", dirMime))
    }

    // ---------------- 列 → ImageItem ----------------

    @Test
    fun `构造 ImageItem——名称 MIME 大小 修改时间 可写位全部落位`() {
        val flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        val it = SafSource.buildItem(
            uri = "content://test/document/photo.heic",
            displayName = "photo.HEIC",
            mimeType = "image/heic",
            sizeBytes = 4096,
            lastModified = 1_700_000_000_000,
            flags = flags,
            origin = ImageItem.Origin.FILE_PICKER,
            documentId = "12",
        )
        assertEquals("content://test/document/photo.heic", it.uri)
        assertEquals("photo.HEIC", it.displayName)
        assertEquals(ImageFormatHint.HEIF, it.format)
        assertEquals(4096L, it.sizeBytes)
        assertEquals(1_700_000_000_000, it.lastModified)
        assertTrue(it.writable)
        assertEquals(ImageItem.Origin.FILE_PICKER, it.origin)
        assertEquals("12", it.documentId)
        assertEquals("heic", it.extension)
        assertTrue(it.isImage)
    }

    @Test
    fun `没有写权限标志时可写为 false`() {
        assertFalse(item("a.jpg", flags = 0).writable)
    }

    @Test
    fun `只支持删除不算可写`() {
        val flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        assertFalse(item("a.jpg", flags = flags).writable)
    }

    @Test
    fun `名称为空时用 URI 末段兜底`() {
        val it = SafSource.buildItem(
            uri = "content://test/document/DCIM/IMG_0001.jpg",
            displayName = null,
            mimeType = null,
            sizeBytes = null,
            lastModified = null,
            flags = 0,
            origin = ImageItem.Origin.FOLDER_SCAN,
        )
        assertEquals("IMG_0001.jpg", it.displayName)
        assertEquals(ImageFormatHint.JPEG, it.format)
    }

    @Test
    fun `URI 也没有名字时给占位名——不返回 null`() {
        val it = SafSource.buildItem(
            uri = "content://test/document/",
            displayName = "   ",
            mimeType = null,
            sizeBytes = null,
            lastModified = null,
            flags = 0,
            origin = ImageItem.Origin.FILE_PICKER,
        )
        assertEquals("(未命名)", it.displayName)
        assertEquals(ImageFormatHint.UNKNOWN, it.format)
    }

    @Test
    fun `MIME 优先于扩展名——云端改名文件也按 MIME 路由`() {
        val it = item("photo.png", mime = "image/jpeg")
        assertEquals(ImageFormatHint.JPEG, it.format)
    }

    @Test
    fun `MIME 认不出时回退扩展名`() {
        val it = item("scan.tiff", mime = "application/octet-stream")
        assertEquals(ImageFormatHint.TIFF, it.format)
    }

    @Test
    fun `toSourceInfo 只带读取链需要的四项`() {
        val info = item("a.webp", size = 10, modified = 99, mime = "image/webp").toSourceInfo()
        assertEquals("a.webp", info.displayName)
        assertEquals("image/webp", info.mimeType)
        assertEquals(10L, info.sizeBytes)
        assertEquals(ImageFormatHint.WEBP, info.format)
    }

    // ---------------- 排序 ----------------

    @Test
    fun `默认按修改时间倒序——时间缺失的排最后`() {
        val a = item("a.jpg", modified = 100)
        val b = item("b.jpg", modified = 300)
        val c = item("c.jpg", modified = 200)
        val none = item("none.jpg", modified = null)
        assertEquals(
            listOf("b.jpg", "c.jpg", "a.jpg", "none.jpg"),
            SafSource.sortItems(listOf(a, b, c, none), SortOrder.MODIFIED_DESC).map { it.displayName },
        )
    }

    @Test
    fun `按名称升序——忽略大小写`() {
        val list = listOf(item("b.jpg"), item("A.jpg"), item("c.jpg"))
        assertEquals(
            listOf("A.jpg", "b.jpg", "c.jpg"),
            SafSource.sortItems(list, SortOrder.NAME_ASC).map { it.displayName },
        )
    }

    @Test
    fun `按大小倒序——大小未知的排最后`() {
        val list = listOf(item("small.jpg", size = 10), item("big.jpg", size = 999), item("unknown.jpg"))
        assertEquals(
            listOf("big.jpg", "small.jpg", "unknown.jpg"),
            SafSource.sortItems(list, SortOrder.SIZE_DESC).map { it.displayName },
        )
    }

    // ---------------- 常量与位判断 ----------------

    @Test
    fun `上限与深度常量按 docs05 固定`() {
        assertEquals(5, SafSource.MAX_DEPTH)
        assertEquals(500, SafSource.MAX_ITEMS)
    }

    @Test
    fun `UriAccess 写位判断`() {
        assertTrue(UriAccess.canWrite(DocumentsContract.Document.FLAG_SUPPORTS_WRITE))
        assertTrue(
            UriAccess.canWrite(
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            )
        )
        assertFalse(UriAccess.canWrite(0))
        assertFalse(UriAccess.canWrite(DocumentsContract.Document.FLAG_SUPPORTS_DELETE))
    }

    @Test
    fun `ScanResult 默认形态`() {
        val r = ScanResult(emptyList(), truncated = false, visitedDirectories = 0)
        assertTrue(r.items.isEmpty())
        assertFalse(r.truncated)
    }
}
