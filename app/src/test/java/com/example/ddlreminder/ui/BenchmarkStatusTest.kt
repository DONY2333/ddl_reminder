package com.example.ddlreminder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ddlreminder.smartadd.*
import com.example.ddlreminder.ui.settings.BenchmarkStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BenchmarkStatusTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `progress and cancel button are visible while request is pending`() {
        var cancelled = false
        compose.setContent { MaterialTheme { BenchmarkStatus(BenchmarkState(isRunning = true, currentIndex = 1)) { cancelled = true } } }
        compose.onNodeWithText("正在测试快速模型 1/4").assertIsDisplayed()
        compose.onNodeWithText("取消测试").assertIsDisplayed().performClick()
        assertTrue(cancelled)
    }
    @Test fun `completed row and average update before run finishes`() {
        var state by mutableStateOf(BenchmarkState(isRunning = true, currentIndex = 1))
        compose.setContent { MaterialTheme { BenchmarkStatus(state) {} } }
        compose.runOnIdle { state = state.copy(currentIndex = 2, attempts = listOf(BenchmarkAttempt(1, BenchmarkModel.FAST, true, 120))) }
        compose.onNodeWithText("正在测试快速模型 2/4", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1/4：成功 · 120ms", substring = true).assertIsDisplayed()
        compose.onNodeWithText("当前平均耗时 120ms", substring = true).assertIsDisplayed()
    }
    @Test fun `failure shows result and removes cancel button after finally`() {
        compose.setContent { MaterialTheme { BenchmarkStatus(BenchmarkState(message = "测试完成", attempts = listOf(BenchmarkAttempt(1, BenchmarkModel.FAST, false, 15000, "REQUEST_TIMEOUT")))) {} } }
        compose.onNodeWithText("REQUEST_TIMEOUT", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消测试").assertDoesNotExist()
    }
    @Test fun `same model message is visible without loading actions`() {
        compose.setContent { MaterialTheme { BenchmarkStatus(BenchmarkState(message = "两个模型配置相同")) {} } }
        compose.onNodeWithText("两个模型配置相同").assertIsDisplayed()
        compose.onNodeWithText("取消测试").assertDoesNotExist()
    }
}
