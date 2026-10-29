package com.example.ddlreminder.logging

import com.example.ddlreminder.data.DdlDao
import com.example.ddlreminder.data.DdlEntity
import com.example.ddlreminder.data.DdlRepository
import com.example.ddlreminder.data.OperationResult
import com.example.ddlreminder.reminder.AlarmScheduler
import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLoggingTest {
    private val minute = 60_000L

    @Test
    fun `ddl created writes expected anonymous event`() = runTest {
        val dao = TestDdlDao()
        val logger = CapturingEventLogger()
        val repository = DdlRepository(dao, TestAlarmScheduler(), logger)

        repository.create(
            title = "private course title",
            deadlineMillis = 4_200 * minute,
            reminderOffsets = setOf(ReminderOffset(1440), ReminderOffset(60)),
            nowMillis = 0,
        )

        val event = logger.events.single()
        assertEquals(EventTypes.DDL_CREATED, event.eventType)
        assertEquals(1L, event.ddlId)
        assertEquals(4_200 * minute, event.deadlineMillis)
        assertEquals("{\"minutes_until_deadline\":4200,\"reminder_count\":2}", event.metadataJson)
    }

    @Test
    fun `ddl created event never contains task title`() = runTest {
        val secretTitle = "SECRET personal assignment"
        val logger = CapturingEventLogger()
        val repository = DdlRepository(TestDdlDao(), TestAlarmScheduler(), logger)

        repository.create(secretTitle, 10 * minute, emptySet(), 0)

        assertFalse(logger.events.single().toString().contains(secretTitle))
    }

    @Test
    fun `ddl completed calculates early and overdue minutes`() = runTest {
        val earlyLogger = CapturingEventLogger()
        val earlyRepo = DdlRepository(
            TestDdlDao(mutableListOf(task(1, 300 * minute))),
            TestAlarmScheduler(),
            earlyLogger,
        )
        earlyRepo.setCompleted(1, true, 120 * minute)

        val lateLogger = CapturingEventLogger()
        val lateRepo = DdlRepository(
            TestDdlDao(mutableListOf(task(2, 300 * minute))),
            TestAlarmScheduler(),
            lateLogger,
        )
        lateRepo.setCompleted(2, true, 360 * minute)

        assertEquals(
            "{\"minutes_before_deadline\":180}",
            earlyLogger.events.single { it.eventType == EventTypes.DDL_COMPLETED }.metadataJson,
        )
        assertEquals(
            "{\"minutes_before_deadline\":-60}",
            lateLogger.events.single { it.eventType == EventTypes.DDL_COMPLETED }.metadataJson,
        )
    }

    @Test
    fun `ddl overdue is written only once per task`() = runTest {
        val dao = TestEventLogDao()
        val logger = RoomEventLogger(dao)

        logger.logOverdueOnce(7, 1_000, 2_000)
        logger.logOverdueOnce(7, 1_000, 3_000)

        assertEquals(1, dao.logs.count { it.eventType == EventTypes.DDL_OVERDUE })
    }

    @Test
    fun `same reminder schedule is logged only once`() = runTest {
        val dao = TestEventLogDao()
        val logger = RoomEventLogger(dao)

        logger.logReminderScheduledOnce(7, 10_000, ReminderOffset(60), 5_000, 1)
        logger.logReminderScheduledOnce(7, 10_000, ReminderOffset(60), 5_000, 2)

        assertEquals(1, dao.logs.count { it.eventType == EventTypes.REMINDER_SCHEDULED })
    }

    @Test
    fun `logging failure while deleting does not keep task`() = runTest {
        val dao = TestDdlDao(mutableListOf(task(1, 100_000)))
        val repository = DdlRepository(dao, TestAlarmScheduler(), ThrowingEventLogger)

        val result = repository.delete(1)

        assertTrue(result is OperationResult.Success)
        assertEquals(null, dao.getById(1))
    }

    @Test
    fun `clearing logs does not delete ddl data`() = runTest {
        val ddlDao = TestDdlDao(mutableListOf(task(1, 100_000)))
        val logDao = TestEventLogDao(
            mutableListOf(EventLogEntity(id = 1, eventType = EventTypes.APP_OPENED, eventTime = 1)),
        )

        RoomEventLogger(logDao).deleteAllLogs()

        assertTrue(logDao.logs.isEmpty())
        assertEquals(1L, ddlDao.getById(1)?.id)
    }

    @Test
    fun `notes contents never enter lifecycle logs`() = runTest {
        val logger = CapturingEventLogger()
        val repository = DdlRepository(TestDdlDao(), TestAlarmScheduler(), logger)
        val secret = "PRIVATE_NOTES_ONLY 闭卷，第三组，教材P86"
        repository.create("exam", 10 * minute, emptySet(), 0, secret)
        repository.update(1, "exam", 20 * minute, emptySet(), 1, "$secret edited")
        repository.setCompleted(1, true, 2)
        repository.setCompleted(1, false, 3)
        repository.delete(1)
        assertEquals(5, logger.events.size)
        logger.events.forEach {
            assertFalse(it.toString().contains("PRIVATE_NOTES_ONLY"))
            assertFalse(it.toString().contains("闭卷"))
            assertFalse(it.toString().contains("教材"))
        }
    }

    private fun task(id: Long, deadline: Long) = DdlEntity(
        id = id,
        title = "not logged",
        deadlineMillis = deadline,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )
}

private class CapturingEventLogger : EventLogger {
    val events = mutableListOf<EventLogEntity>()
    override suspend fun log(eventType: String, eventTime: Long, ddlId: Long?, deadlineMillis: Long?, reminderOffset: ReminderOffset?, metadataJson: String?) {
        events += EventLogEntity(0, eventType, eventTime, ddlId, deadlineMillis, reminderOffset?.minutes?.toString(), metadataJson)
    }
    override suspend fun logReminderScheduledOnce(ddlId: Long, deadlineMillis: Long, reminderOffset: ReminderOffset, triggerAtMillis: Long, eventTime: Long) = Unit
    override suspend fun logOverdueOnce(ddlId: Long, deadlineMillis: Long, eventTime: Long) {
        log(EventTypes.DDL_OVERDUE, eventTime, ddlId, deadlineMillis, null, null)
    }
    override suspend fun getAllLogs() = events.toList()
    override suspend fun deleteAllLogs() = events.clear()
}

private object ThrowingEventLogger : EventLogger {
    override suspend fun log(eventType: String, eventTime: Long, ddlId: Long?, deadlineMillis: Long?, reminderOffset: ReminderOffset?, metadataJson: String?): Unit = error("log failed")
    override suspend fun logReminderScheduledOnce(ddlId: Long, deadlineMillis: Long, reminderOffset: ReminderOffset, triggerAtMillis: Long, eventTime: Long): Unit = error("log failed")
    override suspend fun logOverdueOnce(ddlId: Long, deadlineMillis: Long, eventTime: Long): Unit = error("log failed")
    override suspend fun getAllLogs(): List<EventLogEntity> = error("log failed")
    override suspend fun deleteAllLogs(): Unit = error("log failed")
}

private class TestAlarmScheduler : AlarmScheduler {
    override suspend fun schedule(ddl: DdlEntity, nowMillis: Long): String? = null
    override fun cancel(ddl: DdlEntity) = Unit
    override fun scheduleDebugTest(triggerAtMillis: Long): String? = null
    override fun canScheduleExact() = true
}

private class TestDdlDao(initial: MutableList<DdlEntity> = mutableListOf()) : DdlDao {
    private val data = initial
    private val flow = MutableStateFlow(data.toList())
    override fun observeAll(): Flow<List<DdlEntity>> = flow
    override suspend fun getById(id: Long) = data.find { it.id == id }
    override suspend fun getActiveFuture(nowMillis: Long) = data.filter { !it.isCompleted && it.deadlineMillis > nowMillis }
    override suspend fun getOverdue(nowMillis: Long) = data.filter { !it.isCompleted && it.deadlineMillis <= nowMillis }
    override suspend fun insert(ddl: DdlEntity): Long {
        val id = (data.maxOfOrNull { it.id } ?: 0) + 1
        data += ddl.copy(id = id); flow.value = data.toList(); return id
    }
    override suspend fun update(ddl: DdlEntity) { data.replaceAll { if (it.id == ddl.id) ddl else it }; flow.value = data.toList() }
    override suspend fun delete(ddl: DdlEntity) { data.removeAll { it.id == ddl.id }; flow.value = data.toList() }
}

private class TestEventLogDao(initial: MutableList<EventLogEntity> = mutableListOf()) : EventLogDao {
    val logs = initial
    override suspend fun insert(log: EventLogEntity): Long {
        val id = (logs.maxOfOrNull { it.id } ?: 0) + 1
        logs += log.copy(id = id); return id
    }
    override suspend fun getAllLogs() = logs.toList()
    override suspend fun deleteAllLogs() = logs.clear()
    override suspend fun countScheduled(eventType: String, ddlId: Long, deadlineMillis: Long, reminderType: String) =
        logs.count { it.eventType == eventType && it.ddlId == ddlId && it.deadlineMillis == deadlineMillis && it.reminderType == reminderType }
    override suspend fun countForDdl(eventType: String, ddlId: Long) =
        logs.count { it.eventType == eventType && it.ddlId == ddlId }
}
