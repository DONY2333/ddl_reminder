package com.example.ddlreminder.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReminderPresetRepositoryTest {
    private lateinit var context: Context
    @Before fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `built in presets are present`() = runTest {
        assertEquals(4, DataStoreReminderPresetRepository(context).presets.first().count { it.builtIn })
    }

    @Test fun `custom preset can be created`() = runTest {
        val repository = DataStoreReminderPresetRepository(context)
        repository.saveCustom(null, "Choir", setOf(ReminderOffset(10080), ReminderOffset(0)))
        assertTrue(repository.presets.first().any { it.name == "Choir" && !it.builtIn })
    }

    @Test fun `custom preset can be deleted`() = runTest {
        val repository = DataStoreReminderPresetRepository(context)
        val id = repository.saveCustom(null, "Temporary", setOf(ReminderOffset(15)))
        repository.deleteCustom(id)
        assertFalse(repository.presets.first().any { it.id == id })
    }

    @Test fun `editing preset does not mutate copied ddl offsets`() = runTest {
        val repository = DataStoreReminderPresetRepository(context)
        val id = repository.saveCustom(null, "Before", setOf(ReminderOffset(60)))
        val copiedToDdl = repository.presets.first().single { it.id == id }.offsets.toSet()
        repository.saveCustom(id, "After", setOf(ReminderOffset(15)))
        assertEquals(setOf(ReminderOffset(60)), copiedToDdl)
        assertEquals(setOf(ReminderOffset(15)), repository.presets.first().single { it.id == id }.offsets)
    }

    @Test fun `global defaults round trip for new task input`() = runTest {
        val repository = DataStoreReminderSettingsRepository(context)
        val expected = setOf(ReminderOffset(2880), ReminderOffset(15), ReminderOffset(0))
        repository.setDefaults(expected)
        assertEquals(expected, repository.getDefaults())
    }
}
