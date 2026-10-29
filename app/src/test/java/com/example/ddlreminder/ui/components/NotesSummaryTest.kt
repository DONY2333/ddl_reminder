package com.example.ddlreminder.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotesSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `null notes create no placeholder`() {
        compose.setContent { MaterialTheme { NotesSummary(null, Modifier.testTag("notes")) } }
        compose.onNodeWithTag("notes").assertDoesNotExist()
    }

    @Test fun `blank notes create no placeholder`() {
        compose.setContent { MaterialTheme { NotesSummary(" \n ", Modifier.testTag("notes")) } }
        compose.onNodeWithTag("notes").assertDoesNotExist()
    }

    @Test fun `long notes render only two lines with ellipsis without changing content`() {
        val notes = "闭卷考试，时长1小时，分A/B卷；题型包括单选、不定项、简答和论述。".repeat(20)
        var result: TextLayoutResult? = null
        compose.setContent {
            MaterialTheme {
                NotesSummary(notes, Modifier.width(140.dp).testTag("notes")) { result = it }
            }
        }
        compose.onNodeWithTag("notes").assertTextEquals(notes)
        compose.runOnIdle {
            assertEquals(2, result!!.lineCount)
            assertTrue(result!!.isLineEllipsized(1))
            assertEquals(notes, result!!.layoutInput.text.text)
        }
    }
}
