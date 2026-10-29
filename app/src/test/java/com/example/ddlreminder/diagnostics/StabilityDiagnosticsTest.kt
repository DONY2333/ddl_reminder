package com.example.ddlreminder.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StabilityDiagnosticsTest {
    @Test fun `breadcrumbs reject unknown routes and states and never write exception message`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        StabilityDiagnostics.initialize(context)
        StabilityDiagnostics.record(StabilityDiagnostics.Node.ROUTE, route = "PRIVATE_TITLE_AND_NOTES")
        StabilityDiagnostics.record(StabilityDiagnostics.Node.UI_STATE, state = "PRIVATE_INPUT")
        StabilityDiagnostics.record(StabilityDiagnostics.Node.RECOVERABLE_FAILURE, error = IllegalArgumentException("SECRET_KEY PRIVATE_RESPONSE"))
        val recent = context.getSharedPreferences("stability_diagnostics", Context.MODE_PRIVATE).getString("recent", "").orEmpty()
        listOf("PRIVATE_TITLE_AND_NOTES", "PRIVATE_INPUT", "SECRET_KEY", "PRIVATE_RESPONSE").forEach { assertFalse(recent.contains(it)) }
        assertTrue(recent.contains("IllegalArgumentException"))
    }
    @Test fun `local diagnostic ring is bounded`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        StabilityDiagnostics.initialize(context)
        repeat(150) { StabilityDiagnostics.record(StabilityDiagnostics.Node.UI_STATE, state = "Idle") }
        val recent = context.getSharedPreferences("stability_diagnostics", Context.MODE_PRIVATE).getString("recent", "").orEmpty()
        assertEquals(80, recent.lines().size)
    }
    @Test fun `fatal diagnostic preserves stack and class but strips message and cause`() {
        val original = IllegalStateException("SECRET_KEY", IllegalArgumentException("PRIVATE_NOTES"))
        val safe = StabilityDiagnostics.sanitizedCrash(original)
        assertTrue(safe.message!!.contains("IllegalStateException"))
        assertFalse(safe.toString().contains("SECRET_KEY"))
        assertNull(safe.cause)
        assertArrayEquals(original.stackTrace, safe.stackTrace)
    }
}
