package com.example.ddlreminder.logging

import com.example.ddlreminder.diagnostics.recoverable

import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface EventLogger {
    suspend fun log(
        eventType: String,
        eventTime: Long = System.currentTimeMillis(),
        ddlId: Long? = null,
        deadlineMillis: Long? = null,
        reminderOffset: ReminderOffset? = null,
        metadataJson: String? = null,
    )

    suspend fun logReminderScheduledOnce(
        ddlId: Long,
        deadlineMillis: Long,
        reminderOffset: ReminderOffset,
        triggerAtMillis: Long,
        eventTime: Long = System.currentTimeMillis(),
    )

    suspend fun logOverdueOnce(
        ddlId: Long,
        deadlineMillis: Long,
        eventTime: Long = System.currentTimeMillis(),
    )

    suspend fun getAllLogs(): List<EventLogEntity>
    suspend fun deleteAllLogs()
}

class RoomEventLogger(private val dao: EventLogDao) : EventLogger {
    private val dedupeMutex = Mutex()
    override suspend fun log(
        eventType: String,
        eventTime: Long,
        ddlId: Long?,
        deadlineMillis: Long?,
        reminderOffset: ReminderOffset?,
        metadataJson: String?,
    ) {
        // 埋点永远不能影响主流程，所以所有数据库异常都在此边界内被隔离。
        recoverable {
            dao.insert(
                EventLogEntity(
                    eventType = eventType,
                    eventTime = eventTime,
                    ddlId = ddlId,
                    deadlineMillis = deadlineMillis,
                    reminderType = reminderOffset?.minutes?.toString(),
                    metadataJson = metadataJson,
                ),
            )
        }
    }

    override suspend fun logReminderScheduledOnce(
        ddlId: Long,
        deadlineMillis: Long,
        reminderOffset: ReminderOffset,
        triggerAtMillis: Long,
        eventTime: Long,
    ) {
        recoverable {
            dedupeMutex.withLock {
                if (dao.countScheduled(
                    EventTypes.REMINDER_SCHEDULED,
                    ddlId,
                    deadlineMillis,
                    reminderOffset.minutes.toString(),
                ) == 0) {
                    dao.insert(
                        EventLogEntity(
                            eventType = EventTypes.REMINDER_SCHEDULED,
                            eventTime = eventTime,
                            ddlId = ddlId,
                            deadlineMillis = deadlineMillis,
                            reminderType = reminderOffset.minutes.toString(),
                            metadataJson = "{\"trigger_time\":$triggerAtMillis}",
                        ),
                    )
                }
            }
        }
    }

    override suspend fun logOverdueOnce(ddlId: Long, deadlineMillis: Long, eventTime: Long) {
        recoverable {
            dedupeMutex.withLock {
                if (dao.countForDdl(EventTypes.DDL_OVERDUE, ddlId) == 0) {
                    dao.insert(
                        EventLogEntity(
                            eventType = EventTypes.DDL_OVERDUE,
                            eventTime = eventTime,
                            ddlId = ddlId,
                            deadlineMillis = deadlineMillis,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun getAllLogs(): List<EventLogEntity> =
        recoverable { dao.getAllLogs() }.getOrDefault(emptyList())

    override suspend fun deleteAllLogs() {
        recoverable { dao.deleteAllLogs() }
    }
}

object NoOpEventLogger : EventLogger {
    override suspend fun log(eventType: String, eventTime: Long, ddlId: Long?, deadlineMillis: Long?, reminderOffset: ReminderOffset?, metadataJson: String?) = Unit
    override suspend fun logReminderScheduledOnce(ddlId: Long, deadlineMillis: Long, reminderOffset: ReminderOffset, triggerAtMillis: Long, eventTime: Long) = Unit
    override suspend fun logOverdueOnce(ddlId: Long, deadlineMillis: Long, eventTime: Long) = Unit
    override suspend fun getAllLogs(): List<EventLogEntity> = emptyList()
    override suspend fun deleteAllLogs() = Unit
}
