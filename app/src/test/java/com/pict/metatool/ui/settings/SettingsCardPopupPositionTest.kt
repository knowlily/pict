package com.pict.metatool.ui.settings

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置项弹层的落点（docs/06 §3.7）：点开哪一张卡里的项，弹出的编辑层就盖住那张卡。
 *
 * 全是整数几何、不碰 Android，所以边界能在 JVM 上钉死——「有没有盖住」靠肉眼看截图太容易
 * 滑过去（上一版就是这样漏掉了「对话框落在屏幕正中」这件事）。
 */
class SettingsCardPopupPositionTest {

    private val window = IntSize(width = 1920, height = 1080)

    /** 真机量到的一张设置卡（`x28–1892`）：弹层左上角跟它对齐。 */
    @Test
    fun `弹层左上角对齐设置卡`() {
        val card = IntRect(left = 28, top = 256, right = 1892, bottom = 620)

        val offset = anchoredPopupOffset(card, window, IntSize(width = 1864, height = 480))

        assertEquals(IntOffset(28, 256), offset)
    }

    /** 卡贴近底边时把弹层往上顶：宁可盖住上面几行，也别让确定按钮掉出屏幕。 */
    @Test
    fun `弹层比卡下方的空间高就往上顶`() {
        val card = IntRect(left = 28, top = 900, right = 1892, bottom = 1050)

        val offset = anchoredPopupOffset(card, window, IntSize(width = 1864, height = 430))

        assertEquals(650, offset.y) // 1080 - 430
        assertEquals(28, offset.x)
    }

    /** 卡贴着右边（窄屏）：x 同样不许溢出。 */
    @Test
    fun `弹层不会从右边溢出`() {
        val card = IntRect(left = 1700, top = 100, right = 1910, bottom = 300)

        val offset = anchoredPopupOffset(card, window, IntSize(width = 300, height = 200))

        assertEquals(1620, offset.x) // 1920 - 300
    }

    /** 卡片还没量到（第一次组合时是零矩形）：退回屏幕正中，跟改动前的对话框同一个落点，不闪。 */
    @Test
    fun `还没量到卡片时退回屏幕正中`() {
        val offset = anchoredPopupOffset(IntRect.Zero, window, IntSize(width = 400, height = 300))

        assertEquals(IntOffset(760, 390), offset) // (1920-400)/2, (1080-300)/2
    }

    /** 弹层比窗口还大：夹到 0，至少左上角还在屏内。 */
    @Test
    fun `弹层比窗口还大就贴左上角`() {
        val card = IntRect(left = 100, top = 100, right = 200, bottom = 300)

        val offset = anchoredPopupOffset(card, window, IntSize(width = 2000, height = 1200))

        assertEquals(IntOffset(0, 0), offset)
    }

    /** 窗口尺寸还没就绪（0×0）：不猜，交给调用方兜底。 */
    @Test
    fun `窗口尺寸为零时不动`() {
        val card = IntRect(left = 28, top = 256, right = 1892, bottom = 620)

        val offset = anchoredPopupOffset(card, IntSize.Zero, IntSize(width = 100, height = 100))

        assertEquals(IntOffset.Zero, offset)
    }
}
