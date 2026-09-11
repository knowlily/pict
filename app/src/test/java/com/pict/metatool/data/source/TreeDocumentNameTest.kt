package com.pict.metatool.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 树 URI → 目录名（FR-03 的兜底名字，Provider 问不到时才用）。
 *
 * 样本按真机上见过的三种形状钉：内部存储的树、下载 Provider 的树、带 query 的树。
 * 纯字符串处理，不需要 Robolectric。
 */
class TreeDocumentNameTest {

    @Test
    fun `内部存储的目录取最后一段`() {
        assertEquals(
            "Camera",
            TreeDocumentName.fromUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
            ),
        )
    }

    @Test
    fun `下载 Provider 的树没有卷标也能取名字`() {
        assertEquals(
            "Download",
            TreeDocumentName.fromUri(
                "content://com.android.providers.downloads.documents/tree/downloads%3ADownload",
            ),
        )
    }

    @Test
    fun `中文目录名解码回来`() {
        assertEquals(
            "风景照",
            TreeDocumentName.fromUri(
                "content://com.android.externalstorage.documents/tree/primary%3APictures%2F%E9%A3%8E%E6%99%AF%E7%85%A7",
            ),
        )
    }

    @Test
    fun `URI 后面跟了 document 段或 query 也不吃错`() {
        assertEquals(
            "Camera",
            TreeDocumentName.fromUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera" +
                    "/document/primary%3ADCIM%2FCamera?x=1",
            ),
        )
    }

    @Test
    fun `卷标根目录没有名字：返回 null 让界面决定叫它什么`() {
        assertNull(TreeDocumentName.fromUri("content://com.android.externalstorage.documents/tree/primary%3A"))
    }

    @Test
    fun `不是树 URI 就返回 null`() {
        assertNull(TreeDocumentName.fromUri("content://media/external/images/media/1234"))
        assertNull(TreeDocumentName.fromUri(""))
    }

    @Test
    fun `加号不按表单规则变成空格`() {
        assertEquals(
            "a+b",
            TreeDocumentName.fromUri("content://com.android.externalstorage.documents/tree/primary%3Aa+b"),
        )
    }

    @Test
    fun `百分号后面不是十六进制时原样保留，不吞字符`() {
        assertEquals(
            "100%",
            TreeDocumentName.fromUri("content://com.android.externalstorage.documents/tree/primary%3A100%"),
        )
    }
}
