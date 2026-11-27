// Tests alert retention and explicit native model wake-up.
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
    fun clearingAConversationDropsItsPendingAlerts() {
        val pending = PendingVoiceContext { null }
        pending.addAlert("Retired alert")
        pending.markContextChanged()
        pending.clear()
        assertNull(pending.drain())
    }
}
