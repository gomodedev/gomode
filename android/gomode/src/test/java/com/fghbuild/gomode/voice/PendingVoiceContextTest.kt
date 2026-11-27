// Tests delivery-time context diffs, alert retention, and explicit native model wake-up.
package com.fghbuild.gomode.voice

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingVoiceContextTest {
    @Test
    fun contextIsDiffedOnceWhenDrainedAheadOfQueuedTexts() {
        var updates = 0
        var context: VoiceContextUpdate? = VoiceContextUpdate("Current references", false)
        val pending =
            PendingVoiceContext {
                updates++
                context
            }
        pending.markContextChanged()
        pending.add("First alert", true)
        pending.markContextChanged()
        pending.add("Second alert", true)
        assertEquals(0, updates)
        assertEquals(
            VoiceContextUpdate("Current references\n\nFirst alert\n\nSecond alert", true),
            pending.drain(),
        )
        assertEquals("two changes during a turn send the context once", 1, updates)
        assertNull(pending.drain())
        assertEquals(1, updates)

        context = null
        pending.markContextChanged()
        assertTrue(pending.isNotEmpty())
        assertNull("a change the model already has sends nothing", pending.drain())
    }

    @Test
    fun actionableContextRequestsGenerationWithoutInventingUserSpeech() {
        assertEquals(
            """{"kind":"context.update","context":{"text":"Quiet references"}}""",
            Json.encodeToString(gatewayContextUpdate("Quiet references")),
        )
        assertEquals(
            """{"generate":true,"kind":"context.update","context":{"text":"New alert"}}""",
            Json.encodeToString(gatewayContextUpdate("New alert", true)),
        )
    }

    @Test
    fun alertBufferRejectsOverflowWithoutDroppingEarlierOccurrences() {
        val pending = PendingVoiceContext { null }
        repeat(64) { assertTrue(pending.add("Alert $it", true)) }
        assertFalse(pending.add("Overflow", true))
        assertEquals(
            64,
            pending
                .drain()
                ?.text
                ?.split("\n\n")
                ?.size,
        )
    }

    @Test
    fun clearingAConversationDropsItsPendingAlerts() {
        val pending = PendingVoiceContext { null }
        pending.add("Retired alert", true)
        pending.markContextChanged()
        pending.clear()
        assertNull(pending.drain())
    }
}
