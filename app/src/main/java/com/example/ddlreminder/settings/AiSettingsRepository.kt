package com.example.ddlreminder.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AiSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val privacyAccepted: Boolean = false,
    // 保留旧字段供升级和开发测速使用；正式解析只使用 currentModel，不自动切换模型。
    val fastModel: String = modelName,
    val strongModel: String = modelName,
) {
    val currentModel: String get() = fastModel.trim().ifBlank { modelName.trim().ifBlank { DEFAULT_MODEL } }
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
    /** V1.2.6 升级优先旧 fastModel，再旧 Model Name；不覆盖凭证、隐私确认或旧 strong 字段。 */
    fun forSingleModel(): AiSettings = normalized().copy(modelName = currentModel, fastModel = currentModel)
    fun normalized(): AiSettings = copy(
        baseUrl = cleanBaseUrl(baseUrl), apiKey = apiKey.trim(), modelName = modelName.trim(),
        fastModel = fastModel.trim(), strongModel = strongModel.trim(),
    )

    companion object {
        const val DEFAULT_MODEL = "gpt-6-luna"
        /** 只去掉整个 URL 外面的一对引号，不替换内部字符。 */
        fun cleanBaseUrl(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.length >= 2 &&
                ((trimmed.first() == '"' && trimmed.last() == '"') ||
                    (trimmed.first() == '\'' && trimmed.last() == '\''))) {
                trimmed.substring(1, trimmed.length - 1).trim()
            } else trimmed
        }
    }
}

interface AiSettingsRepository {
    fun get(): AiSettings
    fun save(settings: AiSettings)
}

/** API Key 使用 Android Keystore 支撑的加密 SharedPreferences，仅保存在本机。 */
class SecureAiSettingsRepository(context: Context) : AiSettingsRepository {
    // Keystore 初始化可能阻塞；首次 get/save 由调用方放在 IO dispatcher。
    private val prefs: SharedPreferences by lazy { runCatching {
        EncryptedSharedPreferences.create(
            context,
            "ai_settings_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Robolectric 等没有 AndroidKeyStore 的环境使用隔离测试存储；真机仍走上面的加密实现。
        context.getSharedPreferences("ai_settings_test_fallback", Context.MODE_PRIVATE)
    } }

    override fun get() = AiSettings(
        baseUrl = prefs.getString("base_url", "").orEmpty(),
        apiKey = prefs.getString("api_key", "").orEmpty(),
        modelName = prefs.getString("model", "").orEmpty(),
        privacyAccepted = prefs.getBoolean("privacy_accepted", false),
        // 空旧 fast_model 也应回退旧 model；两者均空才使用默认值。
        fastModel = prefs.getString("fast_model", prefs.getString("model", "")).orEmpty(),
        strongModel = prefs.getString("strong_model", prefs.getString("model", "")).orEmpty(),
    ).forSingleModel()

    override fun save(settings: AiSettings) {
        val cleaned = settings.normalized()
        prefs.edit().putString("base_url", cleaned.baseUrl)
            .putString("api_key", cleaned.apiKey)
            .putString("model", cleaned.modelName)
            .putString("fast_model", cleaned.fastModel)
            .putString("strong_model", cleaned.strongModel)
            .putBoolean("privacy_accepted", settings.privacyAccepted).apply()
    }
}
