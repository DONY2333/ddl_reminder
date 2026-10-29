package com.example.ddlreminder.logging

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 匿名行为日志。禁止向本表写入任务标题或任何个人身份信息。 */
@Entity(
    tableName = "event_logs",
    indices = [Index(value = ["eventType", "ddlId"])],
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val eventTime: Long,
    val ddlId: Long? = null,
    val deadlineMillis: Long? = null,
    val reminderType: String? = null,
    val metadataJson: String? = null,
)

object EventTypes {
    const val DDL_CREATED = "ddl_created"
    const val DDL_EDITED = "ddl_edited"
    const val DDL_COMPLETED = "ddl_completed"
    const val DDL_RESTORED = "ddl_restored"
    const val DDL_DELETED = "ddl_deleted"
    const val REMINDER_SCHEDULED = "reminder_scheduled"
    const val REMINDER_FIRED = "reminder_fired"
    const val REMINDER_CLICKED = "reminder_clicked"
    const val DDL_OVERDUE = "ddl_overdue"
    const val APP_OPENED = "app_opened"
    const val THREE_DAY_ITEM_CLICKED = "three_day_item_clicked"
    const val SMART_ADD_OPENED = "smart_add_opened"
    const val NATURAL_LANGUAGE_PARSE_REQUESTED = "natural_language_parse_requested"
    const val NATURAL_LANGUAGE_PARSE_SUCCEEDED = "natural_language_parse_succeeded"
    const val NATURAL_LANGUAGE_PARSE_FAILED = "natural_language_parse_failed"
    const val VOICE_INPUT_STARTED = "voice_input_started"
    const val VOICE_INPUT_SUCCEEDED = "voice_input_succeeded"
    const val VOICE_INPUT_FAILED = "voice_input_failed"
    const val CSV_IMPORT_STARTED = "csv_import_started"
    const val CSV_IMPORT_PREVIEWED = "csv_import_previewed"
    const val CSV_IMPORT_COMPLETED = "csv_import_completed"
    const val BATCH_DDL_CREATED = "batch_ddl_created"
    const val CUSTOM_REMINDER_USED = "custom_reminder_used"
    const val REMINDER_PRESET_USED = "reminder_preset_used"
    const val AI_PARSE_STARTED = "ai_parse_started"
    const val AI_PARSE_SUCCEEDED = "ai_parse_succeeded"
    const val AI_PARSE_FAILED = "ai_parse_failed"
    const val AI_PARSE_FALLBACK_USED = "ai_parse_fallback_used"
}
