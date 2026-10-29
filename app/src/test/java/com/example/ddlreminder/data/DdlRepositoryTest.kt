package com.example.ddlreminder.data

import com.example.ddlreminder.reminder.AlarmScheduler
import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.data.reminderOffsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DdlRepositoryTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `creating task stores it and schedules alarms`() = runTest {
        val dao = FakeDao(mutableListOf())
        val scheduler = FakeAlarmScheduler()
        val repository = DdlRepository(dao, scheduler)

        val result = repository.create("coursework", 10 * day, ReminderOffset.LEGACY_DEFAULT, 100)

        assertTrue(result is OperationResult.Success)
        assertEquals("coursework", dao.getById(1)!!.title)
        assertEquals(listOf(1L), scheduler.scheduled.map { it.id })
    }

    @Test
    fun `completing task cancels remaining alarms`() = runTest {
        val fixture = fixture(deadline = 10 * day)
        fixture.repository.setCompleted(1, true, 100)

        assertEquals(listOf(1L), fixture.scheduler.cancelled)
        assertTrue(fixture.dao.getById(1)!!.isCompleted)
    }

    @Test
    fun `editing deadline cancels old alarms and schedules new alarms`() = runTest {
        val fixture = fixture(deadline = 10 * day)
        fixture.repository.update(1, "changed", 20 * day, ReminderOffset.LEGACY_DEFAULT, 100)

        assertEquals(listOf(1L), fixture.scheduler.cancelled)
        assertEquals(20 * day, fixture.scheduler.scheduled.single().deadlineMillis)
    }

    @Test
    fun `editing reminder settings cancels old alarms and stores new selection`() = runTest {
        val fixture = fixture(deadline = 10 * day)

        fixture.repository.update(
            1,
            "test",
            10 * day,
            setOf(ReminderOffset(60)),
            100,
        )

        assertEquals(listOf(1L), fixture.scheduler.cancelled)
        assertEquals(setOf(ReminderOffset(60)), fixture.dao.getById(1)!!.reminderOffsets())
        assertEquals(1, fixture.scheduler.scheduled.size)
    }

    @Test
    fun `deleting task cancels alarms`() = runTest {
        val fixture = fixture(deadline = 10 * day)
        fixture.repository.delete(1)

        assertEquals(listOf(1L), fixture.scheduler.cancelled)
        assertEquals(null, fixture.dao.getById(1))
    }

    @Test
    fun `overdue status is derived correctly`() {
        val overdue = entity(deadline = 999)
        val completed = entity(deadline = 999).copy(isCompleted = true)

        assertTrue(overdue.isOverdue(1_000))
        assertFalse(completed.isOverdue(1_000))
    }

    @Test
    fun `startup or boot synchronization restores only unfinished future alarms`() = runTest {
        val dao = FakeDao(
            mutableListOf(
                entity(1, deadline = 10 * day),
                entity(2, deadline = 10 * day).copy(isCompleted = true),
                entity(3, deadline = 50),
            ),
        )
        val scheduler = FakeAlarmScheduler()
        val repository = DdlRepository(dao, scheduler)

        repository.synchronizeReminders(100)

        assertEquals(listOf(1L), scheduler.scheduled.map { it.id })
    }

    @Test
    fun `batch create returns correct count`() = runTest {
        val dao = FakeDao(mutableListOf())
        val repository = DdlRepository(dao, FakeAlarmScheduler())
        val result = repository.createBatch(listOf(
            BatchDdlInput("a", 10 * day, setOf(ReminderOffset(15))),
            BatchDdlInput("b", 11 * day, setOf(ReminderOffset(0))),
        ), 100)
        assertEquals(2, result.createdCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun `editing custom reminders cancels old ddl alarms`() = runTest {
        val fixture = fixture(deadline = 10 * day)
        fixture.repository.update(1, "test", 10 * day, setOf(ReminderOffset(15), ReminderOffset(0)), 100)
        assertEquals(listOf(1L), fixture.scheduler.cancelled)
        assertEquals(setOf(ReminderOffset(15), ReminderOffset(0)), fixture.dao.getById(1)!!.reminderOffsets())
    }

    @Test
    fun `completing task with custom reminders cancels all old alarms`() = runTest {
        val dao = FakeDao(mutableListOf(entity(deadline = 10 * day).copy(reminderOffsetsCsv = "10080,15,0")))
        val scheduler = FakeAlarmScheduler()
        DdlRepository(dao, scheduler).setCompleted(1, true, 100)
        assertEquals(listOf(1L), scheduler.cancelled)
    }

    @Test
    fun `creating and editing ddl preserves notes`() = runTest {
        val dao = FakeDao(mutableListOf())
        val repository = DdlRepository(dao, FakeAlarmScheduler())
        repository.create("exam", 10 * day, emptySet(), 100, "closed book")
        assertEquals("closed book", dao.getById(1)!!.notes)
        repository.update(1, "exam", 10 * day, emptySet(), 200, "bring student card")
        assertEquals("bring student card", dao.getById(1)!!.notes)
    }

    @Test
    fun `creating without notes stores null`() = runTest {
        val dao = FakeDao(mutableListOf())
        val result = DdlRepository(dao, FakeAlarmScheduler()).create("exam", 10 * day, emptySet(), 100)
        assertTrue(result is OperationResult.Success)
        assertEquals(null, dao.getById(1)!!.notes)
    }

    @Test
    fun `clearing notes stores null`() = runTest {
        val dao = FakeDao(mutableListOf())
        val repository = DdlRepository(dao, FakeAlarmScheduler())
        repository.create("exam", 10 * day, emptySet(), 100, "closed book")
        repository.update(1, "exam", 10 * day, emptySet(), 200, "")
        assertEquals(null, dao.getById(1)!!.notes)
    }

    @Test
    fun `whitespace notes are optional`() = runTest {
        val dao = FakeDao(mutableListOf())
        DdlRepository(dao, FakeAlarmScheduler()).create("exam", 10 * day, emptySet(), 100, " \n ")
        assertEquals(null, dao.getById(1)!!.notes)
    }

    @Test
    fun `long multiline notes are stored in full`() = runTest {
        val dao = FakeDao(mutableListOf())
        val notes = "闭卷，考试时长1小时；A/B卷。\n".repeat(200).trim()
        DdlRepository(dao, FakeAlarmScheduler()).create("exam", 10 * day, emptySet(), 100, notes)
        assertEquals(notes, dao.getById(1)!!.notes)
    }

    private fun fixture(deadline: Long): Fixture {
        val dao = FakeDao(mutableListOf(entity(deadline = deadline)))
        val scheduler = FakeAlarmScheduler()
        return Fixture(dao, scheduler, DdlRepository(dao, scheduler))
    }

    private fun entity(id: Long = 1, deadline: Long) = DdlEntity(
        id = id,
        title = "test",
        deadlineMillis = deadline,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    private data class Fixture(
        val dao: FakeDao,
        val scheduler: FakeAlarmScheduler,
        val repository: DdlRepository,
    )
}

private class FakeAlarmScheduler : AlarmScheduler {
    val scheduled = mutableListOf<DdlEntity>()
    val cancelled = mutableListOf<Long>()
    override suspend fun schedule(ddl: DdlEntity, nowMillis: Long): String? { scheduled += ddl; return null }
    override fun cancel(ddl: DdlEntity) { cancelled += ddl.id }
    override fun scheduleDebugTest(triggerAtMillis: Long): String? = null
    override fun canScheduleExact(): Boolean = true
}

private class FakeDao(initial: MutableList<DdlEntity>) : DdlDao {
    private val data = initial
    private val flow = MutableStateFlow(data.toList())
    override fun observeAll(): Flow<List<DdlEntity>> = flow
    override suspend fun getById(id: Long): DdlEntity? = data.find { it.id == id }
    override suspend fun getActiveFuture(nowMillis: Long): List<DdlEntity> =
        data.filter { !it.isCompleted && it.deadlineMillis > nowMillis }
    override suspend fun getOverdue(nowMillis: Long): List<DdlEntity> =
        data.filter { !it.isCompleted && it.deadlineMillis <= nowMillis }
    override suspend fun insert(ddl: DdlEntity): Long {
        val id = (data.maxOfOrNull { it.id } ?: 0) + 1
        data += ddl.copy(id = id); emit(); return id
    }
    override suspend fun update(ddl: DdlEntity) { data.replaceAll { if (it.id == ddl.id) ddl else it }; emit() }
    override suspend fun delete(ddl: DdlEntity) { data.removeAll { it.id == ddl.id }; emit() }
    private fun emit() { flow.value = data.toList() }
}
