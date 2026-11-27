// Tests alert retention, size limits, and explicit native model wake-up.
package com.fghbuild.gomode.voice

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
                .drain()
                ?.text
                ?.split("\n\n")
                ?.size,
        )
    }

    @Test
    fun alertsPastTheLimitWaitForTheNextDrain() {
        val pending = PendingVoiceContext { null }
        repeat(3) { pending.addAlert("x".repeat(10)) }
        assertEquals(
            "two alerts and their separator fit",
            2,
            pending
                .drain(limit = 22)
                ?.text
                ?.split("\n\n")
                ?.size,
        )
        assertTrue(pending.announces())
        assertNull(
            "an alert longer than the limit waits",
            PendingVoiceContext { null }.also { it.addAlert("long") }.drain(3),
        )
        assertEquals(
            "a turn that must respond takes the first alert regardless",
            VoiceContextUpdate("x".repeat(10), true),
            pending.drain(limit = 1, announce = true),
        )
        assertFalse(pending.announces())
    }

    @Test
    fun clearingAConversationDropsItsPendingAlerts() {
        val pending = PendingVoiceContext { null }
        pending.addAlert("Retired alert")
        pending.markContextChanged()
        pending.clear()
        assertNull(pending.drain())
    }
}
