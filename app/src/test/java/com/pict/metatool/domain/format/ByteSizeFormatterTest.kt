package com.pict.metatool.domain.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 体积格式化（图库信息行、详情页「文件」Tab）。
 * 规则钉在这里：取不到显示占位符、1024 进制、保留 1 位小数并去掉 `.0`。
 */
class ByteSizeFormatterTest {

    @Test
    fun `取不到或负数显示占位符`() {
        assertEquals("—", ByteSizeFormatter.format(null))
        assertEquals("—", ByteSizeFormatter.format(-1L))
    }

    @Test
    fun `小于 1KB 显示整数字节`() {
        assertEquals("0 B", ByteSizeFormatter.format(0L))
        assertEquals("1 B", ByteSizeFormatter.format(1L))
        assertEquals("1023 B", ByteSizeFormatter.format(1023L))
    }

    @Test
    fun `1024 进制换档`() {
        assertEquals("1 KB", ByteSizeFormatter.format(1024L))
        assertEquals("1 MB", ByteSizeFormatter.format(1024L * 1024))
        assertEquals("1 GB", ByteSizeFormatter.format(1024L * 1024 * 1024))
        assertEquals("1 TB", ByteSizeFormatter.format(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `保留一位小数并去掉多余的 0`() {
        assertEquals("1.5 KB", ByteSizeFormatter.format(1536L))
        assertEquals("1.5 MB", ByteSizeFormatter.format(1024L * 1536))
        assertEquals("2 KB", ByteSizeFormatter.format(2048L))
    }

    @Test
    fun `差一点到 1MB 仍显示 1024 KB 而不是提前进位`() {
        // 1_048_575 / 1024 = 1023.999…，四舍五入后是 1024.0 —— 单位不跳档，避免「0.99 MB」式误导。
        assertEquals("1024 KB", ByteSizeFormatter.format(1_048_575L))
    }

    @Test
    fun `TB 档与大值不丢精度`() {
        assertEquals("1023 KB", ByteSizeFormatter.format(1023L * 1024))
        assertEquals("2 TB", ByteSizeFormatter.format(2L * 1024 * 1024 * 1024 * 1024))
        assertEquals("1.5 TB", ByteSizeFormatter.format(1024L * 1024 * 1024 * 1536))
    }

    @Test
    fun `未知占位符是破折号`() {
        assertEquals("—", ByteSizeFormatter.UNKNOWN)
    }
}
