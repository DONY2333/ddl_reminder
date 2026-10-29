package com.example.ddlreminder.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import com.example.ddlreminder.reminder.ReminderType
import com.example.ddlreminder.reminder.ReminderOffset

/** Room 会把这个 Kotlin 数据类映射为设备本地 SQLite 表。 */
@Entity(tableName = "ddls")
data class DdlEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val deadlineMillis: Long,
    val isCompleted: Boolean = false,
    val completedAtMillis: Long? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val notes: String? = null,
    /** 每个任务保存自己的实际提醒集合；之后修改全局默认值不会影响它。 */
    @ColumnInfo(defaultValue = "FIVE_DAYS,THREE_DAYS,ONE_DAY,TWELVE_HOURS")
    val reminderTypesCsv: String = ReminderType.encode(ReminderType.LEGACY_DEFAULT),
    /** V1.2 起使用通用分钟 offset；旧字段只用于兼容历史数据库。 */
    @ColumnInfo(defaultValue = "7200,4320,1440,720")
    val reminderOffsetsCsv: String = ReminderOffset.encode(ReminderOffset.LEGACY_DEFAULT),
)

fun DdlEntity.isOverdue(nowMillis: Long): Boolean = !isCompleted && deadlineMillis <= nowMillis
fun DdlEntity.reminderTypes(): Set<ReminderType> = ReminderType.decode(reminderTypesCsv)
fun DdlEntity.reminderOffsets(): Set<ReminderOffset> = ReminderOffset.decode(reminderOffsetsCsv)
