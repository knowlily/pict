package com.pict.metatool.ui.settings

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置项弹层的落点（docs/06 §3.7）：点哪一行，弹出的编辑层就盖在那一行上。
 *
 * 全是整数几何、不碰 Android，所以边界能在 JVM 上钉死——「有没有盖住」靠肉眼看截图
 * 太容易滑过去（改动前那版对话框落在屏幕正中，就是没量数字才留了这么久）。
 */
class SettingsRowPopupPositionTest {

    private val window = IntSize(width = 1920, height = 1080)

    /** 导出后缀那一行（真机量到 x56–1822 / y287–361）：弹层左上角跟它左上角对齐。 */
    @Test
    fun `弹层左上角对齐被点的那一行`() {
        val row = IntRect(left = 56, top = 287, right = 1822, bottom = 361)

        val offset = anchoredPopupOffset(row, window, IntSize(width = 1766, height = 430))

        assertEquals(IntOffset(56, 287), offset)
    }

    /** 行贴近底边时把弹层往上顶：宁可盖住上面几行，也别让「确定」掉出屏幕。 */
    @Test
    fun `弹层比行下方的空间高就往上顶`() {
        val row = IntRect(left = 56, top = 900, right = 1822, bottom = 974)

        val offset = anchoredPopupOffset(row, window, IntSize(width = 1766, height = 430))

        assertEquals(56, offset.x)
        assertEquals(650, offset.y) // 1080 - 430
    }

    /** 行贴着右边：x 同样不许溢出（弹层最右正好落在窗口右沿）。 */
    @Test
    fun `弹层不会从右边溢出`() {
        val row = IntRect(left = 1800, top = 100, right = 1910, bottom = 174)

        val offset = anchoredPopupOffset(row, window, IntSize(width = 300, height = 200))

        assertEquals(1620, offset.x) // 1920 - 300
    }

    /** 锚点还没量到（首帧是零矩形）：退回屏幕正中，跟原来对话框的落点一致，不闪。 */
    @Test
    fun `还没量到锚点时退回屏幕正中`() {
        val offset = anchoredPopupOffset(IntRect.Zero, window, IntSize(width = 400, height = 300))

        assertEquals(IntOffset(760, 390), offset) // (1920-400)/2, (1080-300)/2
    }

    /** 弹层比窗口还大：夹到 0，至少左上角还留在屏内。 */
    @Test
    fun `弹层比窗口还大就贴左上角`() {
        val row = IntRect(left = 100, top = 100, right = 200, bottom = 174)

        val offset = anchoredPopupOffset(row, window, IntSize(width = 2000, height = 1200))

        assertEquals(IntOffset(0, 0), offset)
    }

    /** 窗口尺寸还没就绪（0×0）：不猜，交回 (0,0) 让系统自己决定。 */
    @Test
    fun `窗口尺寸为零时不动`() {
        val row = IntRect(left = 56, top = 287, right = 1822, bottom = 361)

        val offset = anchoredPopupOffset(row, IntSize.Zero, IntSize(width = 100, height = 100))

        assertEquals(IntOffset.Zero, offset)
    }
}
