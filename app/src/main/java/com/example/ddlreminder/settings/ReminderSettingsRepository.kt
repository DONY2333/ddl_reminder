package com.example.ddlreminder.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.reminderDataStore by preferencesDataStore(name = "reminder_settings")

interface ReminderSettingsRepository {
    val defaults: Flow<Set<ReminderOffset>>
    suspend fun getDefaults(): Set<ReminderOffset>
    suspend fun setDefaults(offsets: Set<ReminderOffset>)
}

/** DataStore Preferences 适合保存少量全局设置，并以 Flow 向 Compose 提供更新。 */
class DataStoreReminderSettingsRepository(private val context: Context) : ReminderSettingsRepository {
    override val defaults: Flow<Set<ReminderOffset>> = context.reminderDataStore.data.map { preferences ->
        preferences[DEFAULT_REMINDERS]
            ?.let { stored ->
                ReminderOffset.decode(stored).ifEmpty { ReminderOffset.decodeLegacy(stored) }
            }
            ?: ReminderOffset.LEGACY_DEFAULT
    }

    override suspend fun getDefaults(): Set<ReminderOffset> = defaults.first()

    override suspend fun setDefaults(offsets: Set<ReminderOffset>) {
        context.reminderDataStore.edit { preferences ->
            preferences[DEFAULT_REMINDERS] = ReminderOffset.encode(offsets)
        }
    }

    private companion object {
        val DEFAULT_REMINDERS: Preferences.Key<String> = stringPreferencesKey("default_reminders")
    }
}
