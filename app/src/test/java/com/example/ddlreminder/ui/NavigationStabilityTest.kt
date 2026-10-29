package com.example.ddlreminder.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationStabilityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: TestNavHostController
    private fun page() {
        compose.setContent {
            val context = LocalContext.current
            nav = remember { TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) } }
            NavHost(nav, startDestination = "calendar") {
                composable("calendar") { Text("HOME_VISIBLE") }
                composable("smart_add") { Text("SMART_VISIBLE") }
            }
        }
    }
    @Test fun `double back cannot pop home into empty navhost`() {
        page()
        compose.runOnIdle { nav.openSafe("smart_add") }
        compose.onNodeWithText("SMART_VISIBLE").assertExists()
        compose.runOnIdle { nav.closeSafe("smart_add"); nav.closeSafe("smart_add") }
        compose.onNodeWithText("HOME_VISIBLE").assertExists()
        compose.runOnIdle { assertEquals("calendar", nav.currentDestination?.route) }
    }
    @Test fun `twenty home smart back loops keep valid destination`() {
        page()
        repeat(20) {
            compose.runOnIdle { nav.openSafe("smart_add") }
            compose.runOnIdle { nav.closeSafe("smart_add") }
        }
        compose.onNodeWithText("HOME_VISIBLE").assertExists()
    }
    @Test fun `unknown route falls back to home`() {
        page()
        compose.runOnIdle { nav.openSafe("unknown/private-input") }
        compose.onNodeWithText("HOME_VISIBLE").assertExists()
    }
    @Test fun `empty nav stack can recover to visible home`() {
        page()
        compose.runOnIdle { nav.popBackStack(); nav.returnHome() }
        compose.onNodeWithText("HOME_VISIBLE").assertExists()
    }
    @Test fun `invalid editor arguments map to safe home`() {
        assertEquals("calendar", safeDestination("editor/not-a-number/not-a-date"))
        assertEquals("calendar", safeDestination(null))
        assertEquals("calendar", safeDestination("editor/0/2026-11-20"))
        assertEquals("calendar", safeDestination("editor/-2/2026-11-20"))
        assertEquals("editor/1/2026-11-20", safeDestination("editor/1/2026-11-20"))
    }
}
