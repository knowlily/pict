package com.pict.metatool.ui.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量页的状态契约：「排队了」和「该跳去看进度」是两件事。
 *
 * 钉住的是用户报过的那条——「批量处理之后，在套用预设界面返回时无法返回」
 * （进度页顶栏标题取的就是任务标签，套用预设的批量跑起来后写的正是「套用预设」）。
 * 当时这两件事挤在 `queuedJobId` 一个字段上，界面拿它当 `LaunchedEffect` 的 key：
 * 从进度页返回批量页时它仍然非 null，那一跳又重放了一遍，返回键按下去就被弹回来。
 */
class BatchUiStateTest {

    @Test
    fun `排上队：任务 id 与张数记下来，同时挂上跳进度页的一次性信号`() {
        val state = BatchUiState().queued(jobId = "job-1", count = 3, text = "已经交给后台了")

        assertEquals("job-1", state.queuedJobId)
        assertEquals("job-1", state.openJobId)
        assertEquals(3, state.queuedCount)
        assertEquals("已经交给后台了", state.message)
        assertTrue(state.isQueued)
    }

    @Test
    fun `跳过进度页之后：信号收回去，排队状态一个不动`() {
        val queued = BatchUiState().queued(jobId = "job-1", count = 3, text = null)
        val seen = queued.consumeOpenJob()

        assertNull("信号必须清掉——不清的话，从进度页返回时那一跳会重放", seen.openJobId)
        assertEquals(queued.copy(openJobId = null), seen)
        assertEquals("排队 id 要留着：按钮还得锁着", "job-1", seen.queuedJobId)
        assertTrue(seen.isQueued)
        assertFalse("改草稿之前不该解锁，免得同一批排两次", seen.canExecute)
    }

    @Test
    fun `收信号是幂等的：没排过队、连收两次，都是 null`() {
        assertNull(BatchUiState().openJobId)
        assertNull(BatchUiState().consumeOpenJob().openJobId)
        assertNull(BatchUiState().consumeOpenJob().consumeOpenJob().openJobId)
    }
}
