package com.example.ddlreminder.smartadd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeechSupportTest {
    @Test fun `checking unavailable speech service never crashes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = runCatching { SpeechSupport.isAvailable(context) }
        assertNotNull(result.getOrNull())
    }
}
