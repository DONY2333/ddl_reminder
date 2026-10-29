package com.example.ddlreminder

import android.app.Application
import com.example.ddlreminder.data.AppDatabase
import com.example.ddlreminder.data.DdlRepository
import com.example.ddlreminder.reminder.AndroidAlarmScheduler
import com.example.ddlreminder.reminder.NotificationHelper
import com.example.ddlreminder.settings.DataStoreReminderSettingsRepository
import com.example.ddlreminder.settings.ReminderSettingsRepository
import com.example.ddlreminder.settings.AiSettingsRepository
import com.example.ddlreminder.settings.SecureAiSettingsRepository
import com.example.ddlreminder.settings.ReminderPresetRepository
import com.example.ddlreminder.settings.DataStoreReminderPresetRepository
import com.example.ddlreminder.logging.EventLogger
import com.example.ddlreminder.logging.RoomEventLogger
import com.example.ddlreminder.logging.CsvExportManager
import com.example.ddlreminder.smartadd.AiApiClient
import com.example.ddlreminder.smartadd.HttpAiApiClient
import com.example.ddlreminder.diagnostics.StabilityDiagnostics
import com.example.ddlreminder.diagnostics.recoverable

class DdlReminderApplication : Application() {
    var initializationFailed: Boolean = false
        private set
    lateinit var repository: DdlRepository
        private set
    lateinit var reminderSettings: ReminderSettingsRepository
        private set
    lateinit var eventLogger: EventLogger
        private set
    lateinit var csvExportManager: CsvExportManager
        private set
    lateinit var aiSettings: AiSettingsRepository
        private set
    lateinit var presetRepository: ReminderPresetRepository
        private set
    lateinit var aiApiClient: AiApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        recoverable { StabilityDiagnostics.initialize(this) }
        recoverable {
        NotificationHelper.createChannel(this)
        reminderSettings = DataStoreReminderSettingsRepository(this)
        aiSettings = SecureAiSettingsRepository(this)
        presetRepository = DataStoreReminderPresetRepository(this)
        aiApiClient = HttpAiApiClient()
        val database = AppDatabase.getInstance(this)
        eventLogger = RoomEventLogger(database.eventLogDao())
        csvExportManager = CsvExportManager(this, eventLogger)
        repository = DdlRepository(
            database.ddlDao(),
            AndroidAlarmScheduler(this, eventLogger),
            eventLogger,
        )
        }.onFailure {
            initializationFailed = true
            StabilityDiagnostics.record(StabilityDiagnostics.Node.INITIALIZATION_FAILED, error = it)
        }
    }
}
