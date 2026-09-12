package com.pict.metatool.ui.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/** 拖到多远算数：窄栏守住这个下限，宽栏再按格子放大（[navSwipeCommitDistancePx]）。 */
private val NavSwipeMinCommitDistance = 48.dp

/** 没拖够距离时，松手那一瞬甩多快也算数（dp/s）。 */
private val NavSwipeFlickVelocity = 800.dp

/** 够宽的栏上，拖过这么多格就算数。 */
private const val NavSwipeCommitSlotRatio = 0.35f

/**
 * 这一把该走几格（右为正，0 = 不动）。
 *
 * 位移够阈值就按位移换算：**拖过半格进一格**（1.4 格算 1 格、1.6 格算 2 格），
 * 够阈值至少给一格——不这么兜底的话，「刚过阈值但半格不到」的拖动会算成 0 格，人会以为失灵。
 * 位移不够再看甩：甩够快给一格（接住「短促轻扫」，那一下位移本来就短）。
 */
fun navSwipeSteps(
    dragXPx: Float,
    velocityXPxPerSec: Float,
    slotPx: Float,
    commitDistancePx: Float,
    flickVelocityPxPerSec: Float,
): Int {
    if (slotPx <= 0f) return 0
    if (abs(dragXPx) >= commitDistancePx) {
        val slots = max(1, (abs(dragXPx) / slotPx + 0.5f).toInt())
        return if (dragXPx > 0f) slots else -slots
    }
    if (abs(velocityXPxPerSec) >= flickVelocityPxPerSec) {
        return if (velocityXPxPerSec > 0f) 1 else -1
    }
    return 0
}

/**
 * 在底栏上左右滑动切页时要切到哪一项（docs/06 §2）。
 *
 * 读法是**直接操纵**：手指往右拖，选中胶囊跟着往右走，松手就落到右边那一项。
 * 反过来那种「左滑 = 下一页」的翻页读法在这儿会自相矛盾——胶囊在拖动过程中是跟着手指走的，
 * 判定方向一翻，手指往右、胶囊往左，看着就像坏了。真要翻成翻页读法，把两个入参取负即可，
 * 但**必须同时把胶囊的跟随方向一起翻**，不然两半对着走。
 *
 * **一把拖多远就走几格**（[navSwipeSteps]）：位移换算成格数，再夹在当前栏内。
 * 「从最左边那格一把拖到最右边那格」＝ 两格距离，直接落到最后一项；拖过头就停在最后一格，
 * 不会翻出去。第一版一次只走一格，从最左拖到最右得松手两回、手感像「拖不过去」——那是坏体验，
 * 2026-09-13 修掉。
 *
 * @param currentIndex 当前选中项下标
 * @param itemCount 底栏上实际有几项（设置里关掉的入口不在其中）
 * @param dragXPx 这一把的水平位移，右为正
 * @param velocityXPxPerSec 松手瞬间的水平速度，右为正
 * @param slotPx 一格有多宽（格子宽 + 间距），位移按它换算成格数
 * @param commitDistancePx 拖到多远算数（[navSwipeCommitDistancePx]）
 * @param flickVelocityPxPerSec 甩多快算数
 * @return 该切到的下标；没拖够也没甩够、或者贴着边往外的方向，都返回 null（退回原位，什么都不做）
 */
fun navSwipeTarget(
    currentIndex: Int,
    itemCount: Int,
    dragXPx: Float,
    velocityXPxPerSec: Float,
    slotPx: Float,
    commitDistancePx: Float,
    flickVelocityPxPerSec: Float,
): Int? {
    if (itemCount <= 1) return null
    if (currentIndex !in 0 until itemCount) return null
    val steps = navSwipeSteps(dragXPx, velocityXPxPerSec, slotPx, commitDistancePx, flickVelocityPxPerSec)
    if (steps == 0) return null
    val target = (currentIndex + steps).coerceIn(0, itemCount - 1)
    // 夹完回到原地 = 贴着边往外的方向，什么都不做（不是「往上顶一格」）
    return target.takeIf { it != currentIndex }
}

/**
 * 拖动过程中胶囊该偏移多少：跟着手指走，但不许滑出底栏——到边就停住。
 *
 * 到边不是「拖不动」，是「没地方去了」：先夹住胶囊，松手时 [navSwipeTarget] 同样会判成不动，
 * 两边一致，所以不会出现「胶囊被拖到栏外、松手又弹回来」这种白晃一下。
 *
 * @param slotPx 一格有多宽（格子宽 + 间距）
 */
fun navSwipeFollowOffsetPx(currentIndex: Int, itemCount: Int, dragXPx: Float, slotPx: Float): Float {
    if (itemCount <= 1 || slotPx <= 0f) return 0f
    val nearest = currentIndex.coerceIn(0, itemCount - 1)
    val leftmost = -nearest * slotPx
    val rightmost = (itemCount - 1 - nearest) * slotPx
    return dragXPx.coerceIn(leftmost, rightmost)
}

/**
 * 这一把拖多远算数：取「下限」和「0.35 个格子」里大的那个。
 *
 * 三格的底栏在平板上很宽，一格就有 300 dp，按格子算要拖半屏才动，太累；
 * 反过来在窄屏（竖屏手机）一格只有 ~110 dp，光看下限又太容易误触——两个取大值正好两边都照顾到。
 */
fun navSwipeCommitDistancePx(slotPx: Float, minPx: Float): Float =
    max(minPx, slotPx * NavSwipeCommitSlotRatio)

/**
 * 在底栏上左右滑动切页（FR-35 续，见 docs/06 §2）。
 *
 * 挂在整条底栏上，**格子自己不动**：拖动期间只有选中胶囊跟着手指走，松手才真的切页
 * （切页会换页面，「跟手」跟到一半就把页面换掉会闪）。
 *
 * 两个必须这么写的点：
 * 1. 按下**不消费**——不然格子上的点按（`selectable`）直接失效，滑动做出来了、点击没了。
 * 2. 够了 `touchSlop` 之后在 [PointerEventPass.Initial] 上吃掉位移——这一遍是「先外后内」，
 *    底栏先看见、先吃，格子的点按会因此被取消；不这么做的话，一次滑动结束时手指恰好停在
 *    某一格上，那次点按会跟着触发，滑一下顺带把页面又点走。
 *
 * 拖动期间折射那种「按下去才化开」的效果自然退场：点按被取消，格子的按压态跟着松掉。
 *
 * @param itemCount 底栏上实际有几项，≤ 1 时整段手势都不挂
 * @param currentIndex 当前选中项下标
 * @param onSwipeTo 切到这一项
 * @param onDragOffsetChange 拖动中的水平偏移（像素，右为正），松手/取消时一定会收到一次 0
 */
@Composable
fun Modifier.navBarSwipe(
    itemCount: Int,
    currentIndex: Int,
    onSwipeTo: (Int) -> Unit,
    onDragOffsetChange: (Float) -> Unit = {},
): Modifier {
    // 手势块只在 itemCount 变化时重建，里面的值单独接最新的：否则每次重组都重启手势，
    // 拖到一半被打断（列表滚动、主题切换之类的重组都会碰上）
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestOnSwipeTo by rememberUpdatedState(onSwipeTo)
    val latestOnDrag by rememberUpdatedState(onDragOffsetChange)

    if (itemCount <= 1) return this
    return this.pointerInput(itemCount) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            val slotPx = size.width.toFloat() / itemCount
            val commitPx = navSwipeCommitDistancePx(slotPx, NavSwipeMinCommitDistance.toPx())
            val flickPx = NavSwipeFlickVelocity.toPx()
            var total = 0f
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // 多指不当作滑动：那是缩放/双指操作，跟着动反而会误切页
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) break
                total += change.position.x - change.previousPosition.x
                if (!dragging && abs(total) > viewConfiguration.touchSlop) dragging = true
                if (dragging) {
                    change.consume()
                    latestOnDrag(total)
                }
            }
            val velocityX = tracker.calculateVelocity().x
            // 先归位再切页：切页会让手势块以后重建，别把偏移留在那儿（胶囊会歪着停住）
            latestOnDrag(0f)
            if (dragging) {
                navSwipeTarget(latestIndex, itemCount, total, velocityX, slotPx, commitPx, flickPx)
                    ?.let { latestOnSwipeTo(it) }
            }
        }
    }
}
