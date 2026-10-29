package com.example.ddlreminder.ui

import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ddlreminder.DdlReminderApplication
import com.example.ddlreminder.settings.*
import com.example.ddlreminder.smartadd.*
import com.example.ddlreminder.ui.settings.SettingsScreen
import com.example.ddlreminder.ui.settings.SettingsViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSingleModelUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun cleanup() { store.clear() }

    private fun show(config: AiSettings = AiSettings()): SettingsViewModel {
        val app = ApplicationProvider.getApplicationContext<DdlReminderApplication>()
        val vm = SettingsViewModel(app.reminderSettings, app.eventLogger, app.csvExportManager, app.presetRepository,
            object : AiSettingsRepository {
                override fun get() = config
                override fun save(settings: AiSettings) = Unit
            }, object : AiApiClient {
                override suspend fun complete(config: AiSettings, systemPrompt: String, userPrompt: String): AiApiResult =
                    error("Opening settings must never issue HTTP")
            })
        store.put("settings", vm)
        compose.setContent { MaterialTheme { SettingsScreen(vm, PaddingValues()) {} } }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); !vm.loading.value }
        return vm
    }

    @Test fun `normal settings show only address key and one default model even in Debug`() {
        show()
        compose.onNodeWithText("AI 模型").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("API 地址").assertExists()
        compose.onNodeWithText("API Key").assertExists()
        compose.onNodeWithText("gpt-6-luna").assertExists()
        compose.onNodeWithText("快速解析模型").assertDoesNotExist()
        compose.onNodeWithText("高级兜底模型").assertDoesNotExist()
        compose.onNodeWithText("测试快速模型").assertDoesNotExist()
        compose.onNodeWithText("测试高级模型").assertDoesNotExist()
        compose.onAllNodes(hasText("模型速度测试", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("测试 AI 连接").assertExists()
    }

    @Test fun `old fast custom ID appears in single editable model field`() {
        val vm = show(AiSettings("https://api.example/v1", "key", "legacy", true, "custom-luna", "unused-strong"))
        compose.onNodeWithText("AI 模型").performScrollTo()
        compose.onNodeWithText("custom-luna").assertExists()
        compose.onNodeWithText("unused-strong").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("custom-luna")).performTextReplacement("proxy/luna-id")
        compose.onNodeWithText("proxy/luna-id").assertExists()
        assertEquals("custom-luna", vm.aiSettings.value.currentModel)
    }
}
