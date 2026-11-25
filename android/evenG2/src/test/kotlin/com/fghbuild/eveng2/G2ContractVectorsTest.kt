// Wire vectors of the Even Realities enterprise SDK's native/protocol/contract.json against the G2 codecs.
//
// The resource is a verbatim copy of contract.json from the enterprise SDK 0.1.0-beta.14 source
// bundle (Apache-2.0); see NOTICE in this module.

package com.fghbuild.eveng2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class G2ContractVectorsTest {
    private val vectors =
        Json
            .parseToJsonElement(
                checkNotNull(javaClass.getResource("/even-realities/contract.json")).readText(),
            ).jsonObject
            .getValue("vectors")
            .jsonObject

    private fun vector(name: String): ByteArray = hex(vectors.getValue(name).jsonPrimitive.content)

    private fun message(name: String): G2Message = checkNotNull(G2FrameAssembler().accept(vector(name))) { name }

    // The enterprise demo page: 528x240 at (24, 24), padding 8, widget 11417, session 123.
    private val demoPage =
        G2TextPage(
            x = 24,
            y = 24,
            width = 528,
            height = 240,
            padding = 8,
            containerId = 1,
            containerName = "native-text",
            widgetId = 11417,
        )

    @Test
    fun authRequestMatchesAndroidVector() {
        assertArrayEquals(vector("g2_business_auth_android_request_magic_42_sync_7_hex"), G2Auth.request(42, 7))
    }

    @Test
    fun authRepliesClassify() {
        // ACK with an empty AuthMgr, correlated by magic.
        val ack = message("g2_business_auth_ack_magic_195_sync_223_hex")
        assertEquals(G2AuthReply.AUTHENTICATED, G2Auth.reply(ack, setOf(195)))
        assertNull(G2Auth.reply(ack, setOf(42)))
        // Pushes carry the device's own magic and secAuth=1.
        for (name in listOf(
            "g2_business_auth_push_magic_67_sync_67_hex",
            "g2_business_auth_response_magic_42_sync_7_hex",
        )) {
            assertEquals(name, G2AuthReply.AUTHENTICATED, G2Auth.reply(message(name), setOf(1)))
        }
        val push =
            G2Message(
                1,
                G2Protocol.SID_DEVICE_CONFIG,
                G2Protocol.FLAG_NOTIFY,
                vector("g2_business_auth_success_protobuf_hex"),
            )
        assertEquals(G2AuthReply.AUTHENTICATED, G2Auth.reply(push, emptySet()))
    }

    @Test
    fun evenHubRequestsMatchVectors() {
        assertArrayEquals(
            vector("g2_evenhub_create_hex"),
            G2EvenHub.createPage(demoPage, "Hello EvenHub", 123, 42, 7).single(),
        )
        assertArrayEquals(vector("g2_evenhub_heartbeat_hex"), G2EvenHub.heartbeat(42, 7).single())
        assertArrayEquals(vector("g2_evenhub_close_hex"), G2EvenHub.shutdownPage(42, 7).single())
    }

    @Test
    fun evenHubAcksSucceed() {
        val acks =
            mapOf(
                G2HubCommand.CREATE_PAGE to "g2_evenhub_create_ack_hex",
                G2HubCommand.HEARTBEAT to "g2_evenhub_heartbeat_ack_hex",
                G2HubCommand.SHUTDOWN_PAGE to "g2_evenhub_close_ack_hex",
            )
        for ((command, name) in acks) {
            assertEquals(name, G2HubReply.Success, G2EvenHub.reply(message(name), command, 42, 7))
            assertNull(name, G2EvenHub.reply(message(name), command, 41, 7))
        }
        // Enterprise firmware echoes the heartbeat request command 12 instead of 13.
        val echo = G2Message(7, G2Protocol.SID_EVEN_HUB, 0, vector("g2_evenhub_heartbeat_echo_ack_protobuf_hex"))
        assertEquals(G2HubReply.Success, G2EvenHub.reply(echo, G2HubCommand.HEARTBEAT, 2, 7))
        assertNull(G2EvenHub.reply(echo, G2HubCommand.UPDATE_TEXT, 2, 7))
    }

    @Test
    fun clickIsAPressOfItsContainerAndNeitherAReplyNorAnExit() {
        val click = message("g2_evenhub_click_hex")
        assertTrue(G2EvenHub.press(click, demoPage, 123))
        assertFalse(G2EvenHub.press(click, demoPage.copy(containerId = 2), 123))
        assertFalse(G2EvenHub.press(click, demoPage.copy(containerName = "other"), 123))
        assertNull(G2EvenHub.reply(click, G2HubCommand.CREATE_PAGE, 42, 7))
        assertNull(G2EvenHub.pageExit(click, demoPage, 123))
    }

    @Test
    fun corruptedTrailerIsDropped() {
        val ack = vector("g2_evenhub_create_ack_hex")
        ack[ack.lastIndex] = (ack.last().toInt() xor 1).toByte()
        assertNull(G2FrameAssembler().accept(ack))
    }
}

internal fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
