package com.example.ddlreminder.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiModelSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `legacy model initializes both models and retains credentials and consent`() {
        // Robolectric 无 AndroidKeyStore，与现有 repository 的隔离测试存储一致。
        context.getSharedPreferences("ai_settings_test_fallback", Context.MODE_PRIVATE).edit().clear()
            .putString("base_url", "https://legacy.example/v1").putString("api_key", "legacy-secret")
            .putString("model", "legacy model/id").putBoolean("privacy_accepted", true).commit()
        val config = SecureAiSettingsRepository(context).get()
        assertEquals("legacy model/id", config.fastModel); assertEquals("legacy model/id", config.strongModel)
        assertEquals("https://legacy.example/v1", config.baseUrl); assertEquals("legacy-secret", config.apiKey)
        assertTrue(config.privacyAccepted)
    }
    @Test fun `both models save and reload with internal contents untouched`() {
        val repo = SecureAiSettingsRepository(context)
        repo.save(AiSettings(" https://api.example/v1 ", " secret ", " advanced /id ", true, " fast internal /id ", " advanced /id "))
        val config = repo.get()
        assertEquals("fast internal /id", config.fastModel); assertEquals("advanced /id", config.strongModel)
        assertEquals("fast internal /id", config.modelName)
        assertEquals("secret", config.apiKey); assertEquals("https://api.example/v1", config.baseUrl)
        assertTrue(config.isConfigured)
    }
    @Test fun `empty legacy fast model falls back to old model name`() {
        val repo = SecureAiSettingsRepository(context)
        repo.save(AiSettings("https://api.example/v1", "secret", "strong", true, "", "strong"))
        assertEquals("strong", repo.get().modelName); assertEquals("strong", repo.get().fastModel)
    }
    @Test fun `migrated settings remain stable after saving`() {
        val repo = SecureAiSettingsRepository(context)
        repo.save(AiSettings("https://api.example/v1", "secret", "old", true))
        val first = repo.get(); repo.save(first); assertEquals(first, repo.get())
    }
    @Test fun `unconfigured settings show Luna default without enabling networking`() {
        val config = AiSettings().forSingleModel(); assertFalse(config.isConfigured)
        assertEquals("gpt-6-luna", config.modelName)
    }

    @Test fun `old dual configuration prefers fast and preserves credentials and consent`() {
        context.getSharedPreferences("ai_settings_test_fallback", Context.MODE_PRIVATE).edit().clear()
            .putString("base_url", "https://legacy.example/v1").putString("api_key", "legacy-secret")
            .putString("model", "old").putString("fast_model", " custom Luna/id ")
            .putString("strong_model", "old-strong").putBoolean("privacy_accepted", true).commit()
        val repo = SecureAiSettingsRepository(context)
        val config = repo.get()
        assertEquals("custom Luna/id", config.modelName)
        assertEquals("https://legacy.example/v1", config.baseUrl); assertEquals("legacy-secret", config.apiKey)
        assertTrue(config.privacyAccepted); assertEquals("old-strong", config.strongModel)
        repo.save(config); assertEquals(config, repo.get())
    }

    @Test fun `empty old model fields use default not strong`() {
        val repo = SecureAiSettingsRepository(context)
        repo.save(AiSettings("https://api.example/v1", "key", " ", true, " ", "unused-strong"))
        assertEquals("gpt-6-luna", repo.get().modelName)
        assertEquals("unused-strong", repo.get().strongModel)
    }
}
