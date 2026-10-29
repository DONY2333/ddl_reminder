package com.example.ddlreminder.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64

data class ReminderPreset(
    val id: String,
    val name: String,
    val offsets: Set<ReminderOffset>,
    val builtIn: Boolean,
)

interface ReminderPresetRepository {
    val presets: Flow<List<ReminderPreset>>
    suspend fun saveCustom(id: String?, name: String, offsets: Set<ReminderOffset>): String
    suspend fun deleteCustom(id: String)
}

class DataStoreReminderPresetRepository(private val context: Context) : ReminderPresetRepository {
    override val presets: Flow<List<ReminderPreset>> = context.reminderDataStore.data.map { prefs ->
        BUILT_INS + decode(prefs[CUSTOM_PRESETS].orEmpty())
    }

    override suspend fun saveCustom(id: String?, name: String, offsets: Set<ReminderOffset>): String {
        val actualId = id ?: "custom_${System.currentTimeMillis()}"
        context.reminderDataStore.edit { prefs ->
            val current = decode(prefs[CUSTOM_PRESETS].orEmpty()).filterNot { it.id == actualId }
            prefs[CUSTOM_PRESETS] = encode(current + ReminderPreset(actualId, name.trim(), offsets, false))
        }
        return actualId
    }

    override suspend fun deleteCustom(id: String) {
        context.reminderDataStore.edit { prefs ->
            prefs[CUSTOM_PRESETS] = encode(decode(prefs[CUSTOM_PRESETS].orEmpty()).filterNot { it.id == id })
        }
    }

    private fun encode(items: List<ReminderPreset>): String = items.joinToString(";") {
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(it.name.toByteArray(Charsets.UTF_8))
        "${it.id}|$name|${ReminderOffset.encode(it.offsets)}"
    }

    private fun decode(raw: String): List<ReminderPreset> = raw.split(';').mapNotNull { row ->
        val parts = row.split('|', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        runCatching {
            ReminderPreset(parts[0], String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8),
                ReminderOffset.decode(parts[2]), false)
        }.getOrNull()
    }

    companion object {
        private val CUSTOM_PRESETS = stringPreferencesKey("custom_reminder_presets")
        val BUILT_INS = listOf(
            ReminderPreset("builtin_ddl", "普通 DDL", ReminderOffset.normalize(listOf(7200, 4320, 1440, 720, 60, 30)), true),
            ReminderPreset("builtin_birthday", "生日", ReminderOffset.normalize(listOf(10080, 1440, 0)), true),
            ReminderPreset("builtin_exam", "考试", ReminderOffset.normalize(listOf(10080, 4320, 1440, 60)), true),
            ReminderPreset("builtin_quick", "临时任务", ReminderOffset.normalize(listOf(60, 30)), true),
        )
    }
}
