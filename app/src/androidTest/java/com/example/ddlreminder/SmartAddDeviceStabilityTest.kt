package com.example.ddlreminder

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 需要真实设备/模拟器；不配置 Key，不请求任何外部 AI 服务。 */
@RunWith(AndroidJUnit4::class)
class SmartAddDeviceStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun open() { compose.onNodeWithText("智能添加").performClick() }
    private fun clearTop() {
        compose.onNodeWithTag("smart_list").performScrollToIndex(0)
        compose.onNodeWithTag("smart_input").assertIsDisplayed()
        val first = compose.onNodeWithTag("smart_input").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("smart_topbar").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top >= bar.bottom)
    }

    @Test fun actualImeOpenCloseAndScrollKeepsTopClear() {
        open()
        repeat(10) {
            clearTop()
            compose.onNodeWithTag("smart_input").performClick()
            compose.runOnUiThread {
                WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).show(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(8_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            compose.onNodeWithTag("smart_list").performScrollToNode(hasText("下载 CSV 示例"))
            compose.onNodeWithText("下载 CSV 示例").assertIsDisplayed()
            compose.runOnUiThread {
                WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(8_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            clearTop()
        }
    }

    @Test fun thirtyScrollsAndTwentyNavigationLoops() {
        open()
        repeat(30) {
            compose.onNodeWithTag("smart_list").performTouchInput { swipeUp() }
            compose.onNodeWithTag("smart_list").performTouchInput { swipeDown() }
        }
        clearTop()
        repeat(20) {
            compose.onNodeWithContentDescription("返回").performClick()
            open()
        }
        clearTop()
    }

    @Test fun recreationAndBackgroundKeepInput() {
        open()
        compose.onNodeWithTag("smart_input").performTextInput("saved local input")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("smart_input").assertTextContains("saved local input")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        clearTop()
    }
}
