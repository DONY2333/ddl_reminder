package com.example.ddlreminder.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.example.ddlreminder.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun open() { compose.onNodeWithText("智能添加").performClick(); compose.onNodeWithTag("smart_input").assertExists() }

    @Test fun `activity recreation restores smart route and saved input`() {
        open()
        compose.onNodeWithTag("smart_input").performTextInput("restored local input")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("smart_input").assertTextContains("restored local input")
        compose.onNodeWithText("智能添加").assertIsDisplayed()
    }
    @Test fun `background foreground preserves visible page and input`() {
        open()
        compose.onNodeWithTag("smart_input").performTextInput("local input")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("smart_input").assertTextContains("local input")
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
    }
    @Test fun `back then reopen has a complete visible page`() {
        open()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("DDL Reminder").assertExists()
        open()
        compose.onNodeWithText("输入日程后点击解析，确认预览后才会保存。").assertExists()
    }
}
