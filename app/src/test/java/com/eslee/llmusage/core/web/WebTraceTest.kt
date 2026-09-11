package com.eslee.llmusage.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trace exists to be shared out of the phone, so it must never carry the
 * credentials of the account whose sign-in it is describing.
 */
class WebTraceTest {
    @Before fun reset() = WebTrace.clear()

    @Test fun urlsKeepTheirShapeButNotTheirQuery() {
        WebTrace.url("start", "https://accounts.x.ai/sign-in?code=SUPERSECRETAUTHCODE&state=abc")
        val line = WebTrace.snapshot().single()
        assertTrue(line, line.contains("https://accounts.x.ai/sign-in"))
        assertFalse(line, line.contains("SUPERSECRETAUTHCODE"))
        assertFalse(line, line.contains("state"))
    }

    @Test fun addressesAndLongSecretsAreRemovedFromFreeText() {
        val scrubbed = WebTrace.scrub("login failed for someone@example.com token=abcdefghijklmnopqrstuvwxyz012345")
        assertFalse(scrubbed, scrubbed.contains("someone@example.com"))
        assertFalse(scrubbed, scrubbed.contains("abcdefghijklmnopqrstuvwxyz012345"))
        assertTrue(scrubbed, scrubbed.contains("[email]"))
        assertTrue(scrubbed, scrubbed.contains("[redacted]"))
    }

    @Test fun shortDiagnosticValuesSurviveScrubbing() {
        assertEquals("status=403 main=true", WebTrace.scrub("status=403 main=true"))
        assertEquals("code=-2 main=false", WebTrace.scrub("code=-2 main=false"))
        assertEquals("gesture=false dialog=false", WebTrace.scrub("gesture=false dialog=false"))
    }

    @Test fun theBufferStaysBoundedAndKeepsTheMostRecentEvents() {
        repeat(400) { WebTrace.record("event", "n$it") }
        val snapshot = WebTrace.snapshot()
        assertEquals(300, snapshot.size)
        assertTrue(snapshot.last().contains("n399"))
        assertFalse(snapshot.any { it.contains("n0 ") })
    }

    @Test fun everyEventIsTimestampedSoAStallIsVisible() {
        WebTrace.record("progress", "50%")
        assertTrue(WebTrace.snapshot().single().matches(Regex("""\d{2}:\d{2}:\d{2}\.\d{3} progress · 50%""")))
    }
}
