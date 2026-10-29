package com.example.ddlreminder.data

import com.example.ddlreminder.diagnostics.recoverable

import com.example.ddlreminder.reminder.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import com.example.ddlreminder.reminder.ReminderOffset
import com.example.ddlreminder.logging.EventLogger
import com.example.ddlreminder.logging.EventTypes
import com.example.ddlreminder.logging.NoOpEventLogger

class DdlRepository(
    private val dao: DdlDao,
    private val alarmScheduler: AlarmScheduler,
    private val eventLogger: EventLogger = NoOpEventLogger,
) {
    val allDdls: Flow<List<DdlEntity>> = dao.observeAll()

    suspend fun get(id: Long): DdlEntity? = dao.getById(id)

    suspend fun create(
        title: String,
        deadlineMillis: Long,
        reminderOffsets: Set<ReminderOffset>,
        nowMillis: Long,
        notes: String? = null,
    ): OperationResult {
        val validation = validate(title, deadlineMillis, nowMillis)
        if (validation != null) return OperationResult.Error(validation)
        return recoverable {
            val id = dao.insert(
                DdlEntity(
                    title = title.trim(),
                    deadlineMillis = deadlineMillis,
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    reminderOffsetsCsv = ReminderOffset.encode(reminderOffsets),
                ),
            )
            val saved = dao.getById(id) ?: error("保存后无法读取任务")
            val warning = alarmScheduler.schedule(saved, nowMillis)
            safeLog {
                eventLogger.log(
                    eventType = EventTypes.DDL_CREATED,
                    eventTime = nowMillis,
                    ddlId = saved.id,
                    deadlineMillis = saved.deadlineMillis,
                    metadataJson = "{\"minutes_until_deadline\":${minutesBetween(nowMillis, saved.deadlineMillis)}," +
                        "\"reminder_count\":${reminderOffsets.size}}",
                )
            }
            OperationResult.Success(warning)
        }.getOrElse { OperationResult.Error("保存失败：${readableMessage(it)}") }
    }

    suspend fun update(
        id: Long,
        title: String,
        deadlineMillis: Long,
        reminderOffsets: Set<ReminderOffset>,
        nowMillis: Long,
        notes: String? = null,
    ): OperationResult {
        val validation = validate(title, deadlineMillis, nowMillis)
        if (validation != null) return OperationResult.Error(validation)
        return recoverable {
            val old = dao.getById(id) ?: return OperationResult.Error("任务不存在或已被删除")
            val changed = old.copy(
                title = title.trim(),
                deadlineMillis = deadlineMillis,
                updatedAtMillis = nowMillis,
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                reminderOffsetsCsv = ReminderOffset.encode(reminderOffsets),
            )
            dao.update(changed)
            // 更新数据库后取消旧 PendingIntent；即使旧闹钟同时触发，Receiver 也会校验截止时间。
            alarmScheduler.cancel(old)
            val warning = if (!changed.isCompleted) alarmScheduler.schedule(changed, nowMillis) else null
            safeLog {
                eventLogger.log(
                    eventType = EventTypes.DDL_EDITED,
                    eventTime = nowMillis,
                    ddlId = changed.id,
                    deadlineMillis = changed.deadlineMillis,
                    metadataJson = "{\"deadline_changed\":${old.deadlineMillis != changed.deadlineMillis}," +
                        "\"reminders_changed\":${old.reminderOffsetsCsv != changed.reminderOffsetsCsv}," +
                        "\"reminder_count\":${reminderOffsets.size}}",
                )
            }
            OperationResult.Success(warning)
        }.getOrElse { OperationResult.Error("更新失败：${readableMessage(it)}") }
    }

    suspend fun setCompleted(id: Long, completed: Boolean, nowMillis: Long): OperationResult = recoverable {
        val old = dao.getById(id) ?: return OperationResult.Error("任务不存在或已被删除")
        if (old.isCompleted == completed) return OperationResult.Success()
        if (completed && old.deadlineMillis <= nowMillis) {
            safeLog { eventLogger.logOverdueOnce(old.id, old.deadlineMillis, nowMillis) }
        }
        val changed = old.copy(
            isCompleted = completed,
            completedAtMillis = if (completed) nowMillis else null,
            updatedAtMillis = nowMillis,
        )
        dao.update(changed)
        alarmScheduler.cancel(old)
        val warning = if (!completed && deadlineMillisIsFuture(changed, nowMillis)) {
            alarmScheduler.schedule(changed, nowMillis)
        } else null
        safeLog {
            if (completed) {
                eventLogger.log(
                    eventType = EventTypes.DDL_COMPLETED,
                    eventTime = nowMillis,
                    ddlId = changed.id,
                    deadlineMillis = changed.deadlineMillis,
                    metadataJson = "{\"minutes_before_deadline\":${minutesBetween(nowMillis, changed.deadlineMillis)}}",
                )
            } else {
                eventLogger.log(
                    eventType = EventTypes.DDL_RESTORED,
                    eventTime = nowMillis,
                    ddlId = changed.id,
                    deadlineMillis = changed.deadlineMillis,
                )
            }
        }
        OperationResult.Success(warning)
    }.getOrElse { OperationResult.Error("操作失败：${readableMessage(it)}") }

    suspend fun delete(id: Long): OperationResult = recoverable {
        val ddl = dao.getById(id) ?: return OperationResult.Success()
        dao.delete(ddl)
        alarmScheduler.cancel(ddl)
        safeLog {
            eventLogger.log(
                eventType = EventTypes.DDL_DELETED,
                ddlId = ddl.id,
                deadlineMillis = ddl.deadlineMillis,
            )
        }
        OperationResult.Success()
    }.getOrElse { OperationResult.Error("删除失败：${readableMessage(it)}") }

    suspend fun synchronizeReminders(nowMillis: Long): OperationResult = recoverable {
        val warnings = dao.getActiveFuture(nowMillis).mapNotNull { alarmScheduler.schedule(it, nowMillis) }
        recordOverdueEvents(nowMillis)
        OperationResult.Success(warnings.firstOrNull())
    }.getOrElse { OperationResult.Error("提醒同步失败：${readableMessage(it)}") }

    suspend fun recordOverdueEvents(nowMillis: Long) {
        recoverable {
            dao.getOverdue(nowMillis).forEach { ddl ->
                safeLog { eventLogger.logOverdueOnce(ddl.id, ddl.deadlineMillis, nowMillis) }
            }
        }
    }

    fun scheduleDebugTest(nowMillis: Long): OperationResult {
        val warning = alarmScheduler.scheduleDebugTest(nowMillis + 60_000)
        return OperationResult.Success(warning)
    }

    fun canScheduleExact(): Boolean = alarmScheduler.canScheduleExact()

    /** 批量创建逐项隔离：有效 DDL 会保存，单项失败不会回滚其他项目。 */
    suspend fun createBatch(items: List<BatchDdlInput>, nowMillis: Long): BatchCreateResult {
        val results = items.map { input ->
            create(input.title, input.deadlineMillis, input.reminderOffsets, nowMillis, input.notes)
        }
        val created = results.count { it is OperationResult.Success }
        safeLog {
            eventLogger.log(
                eventType = EventTypes.BATCH_DDL_CREATED,
                eventTime = nowMillis,
                metadataJson = "{\"requested_count\":${items.size},\"created_count\":$created}",
            )
        }
        return BatchCreateResult(created, results.size - created, results.filterIsInstance<OperationResult.Success>()
            .mapNotNull { it.warning }.firstOrNull())
    }

    private fun validate(title: String, deadlineMillis: Long, nowMillis: Long): String? = when {
        title.isBlank() -> "请输入任务名称"
        deadlineMillis <= nowMillis -> "截止时间必须晚于当前时间"
        else -> null
    }

    private fun deadlineMillisIsFuture(ddl: DdlEntity, nowMillis: Long) = ddl.deadlineMillis > nowMillis
    private fun minutesBetween(fromMillis: Long, toMillis: Long): Long =
        Math.floorDiv(toMillis - fromMillis, 60_000L)
    private suspend fun safeLog(block: suspend () -> Unit) { recoverable { block() } }
    private fun readableMessage(error: Throwable) = error.message ?: error.javaClass.simpleName
}

data class BatchDdlInput(
    val title: String,
    val deadlineMillis: Long,
    val reminderOffsets: Set<ReminderOffset>,
    val notes: String? = null,
)

data class BatchCreateResult(val createdCount: Int, val failedCount: Int, val warning: String? = null)

sealed interface OperationResult {
    data class Success(val warning: String? = null) : OperationResult
    data class Error(val message: String) : OperationResult
}
