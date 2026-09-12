package com.pict.metatool.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 底栏上左右滑动切页的判定（FR-35 续、docs/06 §2）。
 *
 * 这条判定错在哪都不好当场看出来：切早了像误触、切反了像失灵、贴着边还切就是切到不存在的一项、
 * 一把拖两格只走一格就是「拖不过去」。所以把「切不切、切哪边、走几格」摆成纯函数，
 * 真机手势那条路只剩「够不够远」的算术，边界在这儿钉死。
 * 真机拖动的读法是**往右拖 = 切右边那一项**（胶囊跟着手指走）。
 */
class NavBarSwipeTest {

    private val commit = 60f
    private val flick = 2000f

    /** 一格 300 px：够宽，能看出「走几格」的换算。 */
    private val slot = 300f

    @Test
    fun `往右拖够远就切右边那一项`() {
        assertEquals(1, navSwipeTarget(0, 3, dragXPx = 90f, velocityXPxPerSec = 0f, slot, commit, flick))
        assertEquals(2, navSwipeTarget(1, 3, dragXPx = 200f, velocityXPxPerSec = 0f, slot, commit, flick))
    }

    @Test
    fun `往左拖够远就切左边那一项`() {
        assertEquals(1, navSwipeTarget(2, 3, dragXPx = -90f, velocityXPxPerSec = 0f, slot, commit, flick))
        assertEquals(0, navSwipeTarget(1, 3, dragXPx = -200f, velocityXPxPerSec = 0f, slot, commit, flick))
    }

    @Test
    fun `一把从最左拖到最右就直接落到最后一项`() {
        // 两格距离（600 px）：第一版这儿只走一格，用户得松手两回，手感像「拖不过去」
        assertEquals(2, navSwipeTarget(0, 3, dragXPx = 620f, velocityXPxPerSec = 0f, slot, commit, flick))
        assertEquals(0, navSwipeTarget(2, 3, dragXPx = -620f, velocityXPxPerSec = 0f, slot, commit, flick))
    }

    @Test
    fun `拖过半格才进一格`() {
        assertEquals(2, navSwipeTarget(0, 3, dragXPx = 480f, velocityXPxPerSec = 0f, slot, commit, flick)) // 1.6 格 -> 2
        assertEquals(1, navSwipeTarget(0, 3, dragXPx = 420f, velocityXPxPerSec = 0f, slot, commit, flick)) // 1.4 格 -> 1
    }

    @Test
    fun `拖过头不会翻出去，最多停在最后一格`() {
        assertEquals(2, navSwipeTarget(1, 3, dragXPx = 900f, velocityXPxPerSec = 0f, slot, commit, flick)) // 3 格 -> 夹到 2
        assertEquals(0, navSwipeTarget(1, 3, dragXPx = -900f, velocityXPxPerSec = 0f, slot, commit, flick))
    }

    @Test
    fun `刚过阈值但半格不到也至少走一格`() {
        // 70 px > 阈值 60 px，但只 0.23 格：按格数换算会算成 0 格，人就以为「拖了没反应」
        assertEquals(1, navSwipeTarget(0, 3, dragXPx = 70f, velocityXPxPerSec = 0f, slot, commit, flick))
        assertEquals(1, navSwipeTarget(2, 3, dragXPx = -70f, velocityXPxPerSec = 0f, slot, commit, flick))
    }

    @Test
    fun `没拖够也没甩起来就退回原位`() {
        assertNull(navSwipeTarget(1, 3, dragXPx = 40f, velocityXPxPerSec = 300f, slot, commit, flick))
        assertNull(navSwipeTarget(1, 3, dragXPx = -40f, velocityXPxPerSec = -300f, slot, commit, flick))
        // 刚好等于阈值不算：边界只归一边，免得在阈值上抖出两种结果
        assertNull(navSwipeTarget(1, 3, dragXPx = 59.9f, velocityXPxPerSec = 1999f, slot, commit, flick))
    }

    @Test
    fun `只甩够快也切，但只给一格`() {
        assertEquals(1, navSwipeTarget(0, 3, dragXPx = 10f, velocityXPxPerSec = 3000f, slot, commit, flick))
        assertEquals(0, navSwipeTarget(1, 3, dragXPx = -10f, velocityXPxPerSec = -3000f, slot, commit, flick))
        // 甩那一下位移本来就短，不该按位移换算出多格
        assertEquals(1, navSwipeTarget(0, 5, dragXPx = 11f, velocityXPxPerSec = 9000f, slot, commit, flick))
    }

    @Test
    fun `位移和甩的方向打架时以位移为准`() {
        // 拖到右边又往回甩：手指停在右边那一格上，胶囊也在那儿，按位移切
        assertEquals(1, navSwipeTarget(0, 3, dragXPx = 80f, velocityXPxPerSec = -4000f, slot, commit, flick))
    }

    @Test
    fun `贴着边的方向不动`() {
        assertNull(navSwipeTarget(2, 3, dragXPx = 300f, velocityXPxPerSec = 5000f, slot, commit, flick)) // 最后一项再往右
        assertNull(navSwipeTarget(0, 3, dragXPx = -300f, velocityXPxPerSec = -5000f, slot, commit, flick)) // 第一项再往左
        // 一把拖三格，从最后一项往右：夹回原地，同样不动，不是「往上顶一格」
        assertNull(navSwipeTarget(2, 3, dragXPx = 900f, velocityXPxPerSec = 0f, slot, commit, flick))
    }

    @Test
    fun `只有一项时怎么拖都不切`() {
        assertNull(navSwipeTarget(0, 1, dragXPx = 500f, velocityXPxPerSec = 9000f, slot, commit, flick))
        assertNull(navSwipeTarget(0, 0, dragXPx = 500f, velocityXPxPerSec = 9000f, slot, commit, flick))
    }

    @Test
    fun `下标越界（手改 pref 留下的怪路由）不猜目标`() {
        assertNull(navSwipeTarget(-1, 3, dragXPx = 500f, velocityXPxPerSec = 9000f, slot, commit, flick))
        assertNull(navSwipeTarget(3, 3, dragXPx = 500f, velocityXPxPerSec = 9000f, slot, commit, flick))
    }

    @Test
    fun `还没量出格子宽度时不猜格数`() {
        // slotPx 为 0/负：宁可什么都不做，也别拿一个假的格宽去换算（会得出个离谱的跳格数）
        assertNull(navSwipeTarget(0, 3, dragXPx = 900f, velocityXPxPerSec = 0f, 0f, commit, flick))
        assertEquals(0, navSwipeSteps(900f, 0f, slotPx = 0f, commitDistancePx = commit, flickVelocityPxPerSec = flick))
        assertEquals(0, navSwipeSteps(0f, 9000f, slotPx = -1f, commitDistancePx = commit, flickVelocityPxPerSec = flick))
    }

    @Test
    fun `走几格：位移优先，位移不够才看甩`() {
        assertEquals(0, navSwipeSteps(30f, 500f, slot, commit, flick))
        assertEquals(2, navSwipeSteps(600f, 0f, slot, commit, flick))
        assertEquals(-2, navSwipeSteps(-600f, 0f, slot, commit, flick))
        assertEquals(1, navSwipeSteps(10f, 5000f, slot, commit, flick)) // 位移不够，甩补一格
    }

    @Test
    fun `算多远算数：窄栏守下限，宽栏按格子放大`() {
        val min = 48f
        assertEquals(min, navSwipeCommitDistancePx(slotPx = 100f, minPx = min), 0.01f) // 竖屏手机：一格 100 px 太短，按 48 走
        assertEquals(105f, navSwipeCommitDistancePx(slotPx = 300f, minPx = min), 0.01f) // 横屏平板：一格 300 px，35% 才够
    }

    @Test
    fun `拖动时胶囊跟手，但夹在底栏里`() {
        assertEquals(120f, navSwipeFollowOffsetPx(1, 3, dragXPx = 120f, slotPx = slot), 0.01f) // 中间那格两边都有地方
        assertEquals(0f, navSwipeFollowOffsetPx(0, 3, dragXPx = -80f, slotPx = slot), 0.01f) // 第一项不能往左走
        assertEquals(0f, navSwipeFollowOffsetPx(2, 3, dragXPx = 80f, slotPx = slot), 0.01f) // 最后一项不能往右走
        assertEquals(600f, navSwipeFollowOffsetPx(0, 3, dragXPx = 900f, slotPx = slot), 0.01f) // 拖到栏外也只走到底
    }

    @Test
    fun `只有一项、或者还没量出宽度时不偏移`() {
        assertEquals(0f, navSwipeFollowOffsetPx(0, 1, dragXPx = 200f, slotPx = 300f), 0.01f)
        assertEquals(0f, navSwipeFollowOffsetPx(0, 3, dragXPx = 200f, slotPx = 0f), 0.01f)
    }
}
