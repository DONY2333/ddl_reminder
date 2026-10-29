package com.example.ddlreminder.logging

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(log: EventLogEntity): Long

    @Query("SELECT * FROM event_logs ORDER BY id ASC")
    suspend fun getAllLogs(): List<EventLogEntity>

    @Query("DELETE FROM event_logs")
    suspend fun deleteAllLogs()

    @Query(
        "SELECT COUNT(*) FROM event_logs WHERE eventType = :eventType " +
            "AND ddlId = :ddlId AND deadlineMillis = :deadlineMillis AND reminderType = :reminderType",
    )
    suspend fun countScheduled(
        eventType: String,
        ddlId: Long,
        deadlineMillis: Long,
        reminderType: String,
    ): Int

    @Query("SELECT COUNT(*) FROM event_logs WHERE eventType = :eventType AND ddlId = :ddlId")
    suspend fun countForDdl(eventType: String, ddlId: Long): Int
}
