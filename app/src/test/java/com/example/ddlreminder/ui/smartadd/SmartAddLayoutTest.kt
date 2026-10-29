package com.example.ddlreminder.ui.smartadd

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ddlreminder.smartadd.ScheduleDraft
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
class SmartAddLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val preview = SmartAddUiState(input = "input", phase = SmartAddPhase.Preview,
        drafts = (1..40).map { ScheduleDraft("event $it", 4_000_000_000_000) })

    private fun set(state: SmartAddUiState = SmartAddUiState()) {
        compose.setContent { MaterialTheme { SmartAddLayout(state, topBarInsets = WindowInsets(top = 28.dp)) } }
    }
    private fun topIsClear() {
        compose.onNodeWithText("智能添加").assertIsDisplayed()
        compose.onNodeWithTag("smart_input").assertIsDisplayed()
        val topBar = compose.onNodeWithTag("smart_topbar").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("smart_input").fetchSemanticsNode().boundsInRoot
        assertTrue("first item must be below fixed top bar", first.top >= topBar.bottom)
        val title = compose.onNodeWithText("智能添加").fetchSemanticsNode().boundsInRoot
        assertTrue(title.top >= 28 * compose.density.density)
    }
    @Test fun `opening Smart Add keeps title and first input visible`() { set(); topIsClear() }
    @Test fun `scroll bottom then back keeps first item fully below top bar`() {
        set(preview)
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("下载 CSV 示例"))
        compose.onNodeWithTag("smart_list").performScrollToIndex(0)
        topIsClear()
    }
    @Test fun `repeated up and down scroll never moves topbar into list`() {
        set(preview)
        repeat(5) {
            compose.onNodeWithTag("smart_list").performTouchInput { swipeUp() }
            compose.onNodeWithTag("smart_list").performTouchInput { swipeDown() }
        }
        compose.onNodeWithTag("smart_list").performScrollToIndex(0)
        topIsClear()
    }
    @Test fun `focused input with reduced keyboard viewport still allows bottom scrolling`() {
        var keyboardViewport by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            Box(Modifier.height(if (keyboardViewport) 360.dp else 640.dp)) {
                SmartAddLayout(preview, topBarInsets = WindowInsets(top = 28.dp))
            }
        } }
        compose.onNodeWithTag("smart_input").performClick()
        compose.runOnIdle { keyboardViewport = true }
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("下载 CSV 示例"))
        compose.onNodeWithText("下载 CSV 示例").assertIsDisplayed()
    }
    @Test fun `restoring keyboard viewport restores first item geometry`() {
        var keyboardViewport by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            Box(Modifier.height(if (keyboardViewport) 360.dp else 640.dp)) {
                SmartAddLayout(preview, topBarInsets = WindowInsets(top = 28.dp))
            }
        } }
        val initial = compose.onNodeWithTag("smart_input").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { keyboardViewport = true }
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("下载 CSV 示例"))
        compose.runOnIdle { keyboardViewport = false }
        compose.onNodeWithTag("smart_list").performScrollToIndex(0)
        assertEquals(initial, compose.onNodeWithTag("smart_input").fetchSemanticsNode().boundsInRoot.top, 1f)
        topIsClear()
    }
    @Test fun `loading has explicit content and parse button is disabled`() {
        compose.mainClock.autoAdvance = false
        set(SmartAddUiState(input = "input", phase = SmartAddPhase.Parsing))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("正在识别日程…"))
        compose.onNodeWithText("正在识别日程…").assertIsDisplayed()
        compose.onNodeWithText("解析日程").assertIsNotEnabled()
        compose.onNodeWithTag("smart_parsing_spinner").assertIsDisplayed()
    }
    @Test fun `loading after one and half seconds shows understanding without losing input or back`() {
        compose.mainClock.autoAdvance = false
        var back = 0
        compose.setContent { MaterialTheme {
            SmartAddLayout(SmartAddUiState(input = "保留输入", phase = SmartAddPhase.Parsing), onClose = { back++ })
        } }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("正在理解通知内容…"))
        compose.onNodeWithText("正在理解通知内容…").assertIsDisplayed()
        compose.onNodeWithTag("smart_parsing_spinner").assertIsDisplayed()
        compose.onNodeWithTag("smart_input").assertTextContains("保留输入")
        compose.onNodeWithText("解析日程").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, back)
    }
    @Test fun `leaving parsing removes spinner and resets delay for next attempt`() {
        compose.mainClock.autoAdvance = false
        var phase by mutableStateOf(SmartAddPhase.Parsing)
        compose.setContent { MaterialTheme {
            SmartAddLayout(SmartAddUiState(input = "保留输入", phase = phase, message = if (phase == SmartAddPhase.ParseFailed) "解析失败" else null))
        } }
        compose.mainClock.advanceTimeBy(1_600)
        compose.runOnIdle { phase = SmartAddPhase.ParseFailed }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("重试"))
        compose.onNodeWithText("解析失败").assertIsDisplayed()
        compose.onNodeWithTag("smart_parsing_spinner").assertDoesNotExist()
        compose.onNodeWithText("解析日程").assertIsEnabled()
        compose.runOnIdle { phase = SmartAddPhase.Parsing }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("正在识别日程…").assertExists()
        compose.onNodeWithText("正在理解通知内容…").assertDoesNotExist()
    }
    @Test fun `failed parse has error retry local parser and working back`() {
        var backCalls = 0
        compose.setContent { MaterialTheme {
            SmartAddLayout(SmartAddUiState(phase = SmartAddPhase.ParseFailed, message = "AI解析失败，请重试"), onClose = { backCalls++ })
        } }
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("重试"))
        compose.onNodeWithText("AI解析失败，请重试").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.onNodeWithText("使用本地解析").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backCalls)
    }
    @Test fun `preview has visible event and summary`() {
        set(preview)
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("识别到 40 个有效日程"))
        compose.onNodeWithText("识别到 40 个有效日程").assertIsDisplayed()
        compose.onNodeWithTag("smart_list").performScrollToNode(hasText("event 1"))
        compose.onNodeWithText("event 1").assertIsDisplayed()
    }
    @Test fun `idle always renders editable input`() {
        set(); compose.onNodeWithTag("smart_input").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun `every expected phase renders page controls not blank content`() {
        var phase by mutableStateOf(SmartAddPhase.Idle)
        compose.setContent { MaterialTheme { SmartAddLayout(SmartAddUiState(input = "input", phase = phase)) } }
        SmartAddPhase.entries.forEach {
            compose.runOnIdle { phase = it }
            compose.onNodeWithTag("smart_page").assertIsDisplayed()
            compose.onNodeWithText("智能添加").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").assertIsDisplayed()
            compose.onNodeWithTag("smart_input").assertExists()
        }
    }
}
