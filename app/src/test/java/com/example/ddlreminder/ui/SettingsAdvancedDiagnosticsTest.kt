package com.example.ddlreminder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ddlreminder.smartadd.AiApiErrorType
import com.example.ddlreminder.smartadd.AiFailureStage
import com.example.ddlreminder.smartadd.AiRequestStatus
import com.example.ddlreminder.smartadd.ParsePerformance
import com.example.ddlreminder.smartadd.ParseRoute
import com.example.ddlreminder.ui.settings.AdvancedAiDiagnostics
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAdvancedDiagnosticsTest {
    @get:Rule val compose = createComposeRule()

    private val request = AiRequestStatus(
        statusCode = 503,
        latencyMs = 120,
        model = "test-model",
        endpoint = "https://api.example.com/v1/chat/completions",
        errorType = AiApiErrorType.SERVER,
        failureStage = AiFailureStage.HTTP_ERROR,
    )
    private val performance = ParsePerformance(ParseRoute.LOCAL_FALLBACK, request,
        totalLatencyMs = 130, eventCount = 1)

    @Test fun `diagnostics are collapsed by default even after a request failed`() {
        compose.setContent { MaterialTheme { AdvancedAiDiagnostics(request, performance) } }
        compose.onNodeWithText("高级诊断").assertIsDisplayed()
        compose.onAllNodes(hasText("HTTP_ERROR", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Endpoint：", substring = true)).assertCountEquals(0)
    }

    @Test fun `expanding and collapsing retains complete failure details`() {
        compose.setContent { MaterialTheme { AdvancedAiDiagnostics(request, performance) } }
        repeat(2) {
            compose.onNodeWithText("高级诊断").performClick()
            compose.onNodeWithText(request.diagnosticText()).assertIsDisplayed()
            compose.onNodeWithText(performance.diagnosticText()).assertIsDisplayed()
            compose.onNodeWithText("收起高级诊断").performClick()
            compose.onNodeWithText(request.diagnosticText()).assertDoesNotExist()
        }
    }

    @Test fun `empty diagnostics explain that no request has been made`() {
        compose.setContent { MaterialTheme { AdvancedAiDiagnostics(null, null) } }
        compose.onNodeWithText("高级诊断").performClick()
        compose.onNodeWithText("暂无请求记录").assertIsDisplayed()
    }

    @Test fun `expanded diagnostics survive saved state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { AdvancedAiDiagnostics(request, null) } }
        compose.onNodeWithText("高级诊断").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("收起高级诊断").assertIsDisplayed()
        compose.onNodeWithText(request.diagnosticText()).assertIsDisplayed()
    }
}
