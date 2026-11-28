// Tests alert retention, size limits, and explicit native model wake-up.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.service.MODEL_CONTEXT_LIMIT
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingVoiceContextTest {
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
        repeat(64) { assertTrue(pending.addAlert("Alert $it")) }
        assertFalse(pending.addAlert("Overflow"))
        assertEquals(
            64,
            pending
                .drain(announce = true)
                ?.text
                ?.split("\n\n")
                ?.size,
        )
    }

    @Test
    fun alertsPastTheLimitWaitForTheNextDrain() {
        val pending = PendingVoiceContext { null }
        repeat(3) { pending.addAlert("x".repeat(6000)) }
        assertEquals(
            "two alerts and their separator fit the limit",
            2,
            pending
                .drain(announce = false)
                ?.text
                ?.split("\n\n")
                ?.size,
        )
        assertTrue(pending.announces())
        assertEquals(
            1,
            pending
                .drain(announce = false)
                ?.text
                ?.split("\n\n")
                ?.size,
        )
        val long = "x".repeat(MODEL_CONTEXT_LIMIT + 1)
        pending.addAlert(long)
        assertNull("an alert longer than the limit waits", pending.drain(announce = false))
        assertEquals(
            "an update that requests a turn takes the first alert regardless",
            VoiceContextUpdate(long, true),
            pending.drain(announce = true),
        )
        assertFalse(pending.announces())
    }

    @Test
    fun clearingAConversationDropsItsPendingAlerts() {
        val pending = PendingVoiceContext { null }
        pending.addAlert("Retired alert")
        pending.markContextChanged()
        pending.clear()
        assertNull(pending.drain(announce = true))
    }
}
