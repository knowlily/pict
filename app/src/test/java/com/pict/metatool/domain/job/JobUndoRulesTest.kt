package com.pict.metatool.domain.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * docs/07 T5.8、docs/01 FR-34：哪一条能撤。
 *
 * 规则一句话：只有**最近一次留了备份**的那条能撤，其余各自标个理由。
 * 这里逐条钉边界：最近的按备份时间戳挑、撤过的照旧算最近、正好满 7 天算过期。
 */
class JobUndoRulesTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 12, 12, 0, 0)

    private fun at(
        day: Int = 12,
        hour: Int = 10,
        minute: Int = 15,
    ): LocalDateTime = LocalDateTime.of(2026, 9, day, hour, minute, 30)

    private fun entry(
        jobId: String = "job-1",
        status: JobStatus = JobStatus.COMPLETED,
        createdAtMillis: Long = 1_726_000_000_000L,
        dryRun: Boolean = false,
        undoneAtMillis: Long? = null,
    ) = JobHistoryEntry(
        jobId = jobId,
        label = "任务 $jobId",
        status = status,
        createdAtMillis = createdAtMillis,
        finishedAtMillis = createdAtMillis + 9_000L,
        total = 3,
        succeeded = 3,
        dryRun = dryRun,
        undoneAtMillis = undoneAtMillis,
    )

    private fun stamp(
        jobId: String = "job-1",
        folder: String = "20260912_101530",
        createdAt: LocalDateTime = at(),
        files: Int = 3,
    ) = JobBackupStamp(jobId = jobId, folder = folder, createdAt = createdAt, files = files)

    @Test
    fun `只有最近一次留了备份的能撤，更早的标成不是最近一次`() {
        val newer = entry("job-2", createdAtMillis = 2_000L)
        val older = entry("job-1", createdAtMillis = 1_000L)

        val marked = JobUndoRules.apply(
            entries = listOf(newer, older),
            stamps = listOf(
                stamp("job-1", folder = "20260912_101530", createdAt = at(hour = 10), files = 2),
                stamp("job-2", folder = "20260912_114500", createdAt = at(hour = 11), files = 5),
            ),
            now = now,
        )

        val byJob = marked.associateBy { it.jobId }
        assertEquals(UndoState.AVAILABLE, byJob.getValue("job-2").undoState)
        assertEquals("20260912_114500", byJob.getValue("job-2").backupFolder)
        assertEquals(5, byJob.getValue("job-2").backupFiles)
        // 不是最近的那次也把目录名与份数带上：界面要显示「留有备份，但撤不了」
        assertEquals(UndoState.NOT_LATEST, byJob.getValue("job-1").undoState)
        assertEquals("20260912_101530", byJob.getValue("job-1").backupFolder)
        assertEquals(2, byJob.getValue("job-1").backupFiles)
    }

    @Test
    fun `最近的按备份时间戳挑，不按任务创建时间`() {
        // 先跑的旧任务留了备份，后跑的那次没带备份目录：能撤的是旧的那次
        val older = entry("job-1", createdAtMillis = 1_000L)
        val newer = entry("job-2", createdAtMillis = 2_000L)

        val marked = JobUndoRules.apply(
            entries = listOf(newer, older),
            stamps = listOf(stamp("job-1", createdAt = at(hour = 11))),
            now = now,
        )

        val byJob = marked.associateBy { it.jobId }
        assertEquals(UndoState.AVAILABLE, byJob.getValue("job-1").undoState)
        assertEquals(UndoState.NO_BACKUP, byJob.getValue("job-2").undoState)
    }

    @Test
    fun `撤过的标成已撤销，按钮收掉但目录名照旧留着`() {
        val marked = JobUndoRules.apply(
            entries = listOf(entry(undoneAtMillis = 1_726_000_100_000L)),
            stamps = listOf(stamp(folder = "20260912_101530", files = 4)),
            now = now,
        )

        val only = marked.single()
        assertEquals(UndoState.UNDONE, only.undoState)
        assertFalse(only.canUndo)
        assertEquals("20260912_101530", only.backupFolder)
        assertEquals(4, only.backupFiles)
    }

    @Test
    fun `撤过的那次照旧算最近，更早的那次不能跟着撤`() {
        val undone = entry("job-2", createdAtMillis = 2_000L, undoneAtMillis = 1_726_000_100_000L)
        val older = entry("job-1", createdAtMillis = 1_000L)

        val marked = JobUndoRules.apply(
            entries = listOf(undone, older),
            stamps = listOf(
                stamp("job-1", folder = "20260912_101530", createdAt = at(hour = 10)),
                stamp("job-2", folder = "20260912_114500", createdAt = at(hour = 11)),
            ),
            now = now,
        )

        val byJob = marked.associateBy { it.jobId }
        assertEquals(UndoState.UNDONE, byJob.getValue("job-2").undoState)
        // 关键：撤过的那次仍占着「最近」这个位子，避免连撤两次把更早的改动一并回退
        assertEquals(UndoState.NOT_LATEST, byJob.getValue("job-1").undoState)
    }

    @Test
    fun `没留备份的标成没有备份`() {
        val marked = JobUndoRules.apply(entries = listOf(entry()), stamps = emptyList(), now = now)

        assertEquals(UndoState.NO_BACKUP, marked.single().undoState)
        assertFalse(marked.single().canUndo)
    }

    @Test
    fun `试运行不给撤，哪怕它真留了备份目录`() {
        val marked = JobUndoRules.apply(
            entries = listOf(entry(dryRun = true)),
            stamps = listOf(stamp()),
            now = now,
        )

        assertEquals(UndoState.DRY_RUN, marked.single().undoState)
    }

    @Test
    fun `正好满 7 天算过期，差一分钟还能撤`() {
        val createdAt = now.minusDays(7)
        val expired = JobUndoRules.apply(
            entries = listOf(entry()),
            stamps = listOf(stamp(createdAt = createdAt)),
            now = now,
        )
        val alive = JobUndoRules.apply(
            entries = listOf(entry()),
            stamps = listOf(stamp(createdAt = createdAt.plusMinutes(1))),
            now = now,
        )

        assertEquals(UndoState.EXPIRED, expired.single().undoState)
        assertEquals(UndoState.AVAILABLE, alive.single().undoState)
        assertTrue(JobUndoRules.isExpired(createdAt, now))
        assertFalse(JobUndoRules.isExpired(createdAt.plusMinutes(1), now))
    }

    @Test
    fun `过期的那条也把目录名留出来，好说清楚是备份过期了`() {
        val marked = JobUndoRules.apply(
            entries = listOf(entry()),
            stamps = listOf(stamp(folder = "20260901_090000", createdAt = now.minusDays(11), files = 6)),
            now = now,
        )

        assertEquals(UndoState.EXPIRED, marked.single().undoState)
        assertEquals("20260901_090000", marked.single().backupFolder)
        assertEquals(6, marked.single().backupFiles)
    }
}
