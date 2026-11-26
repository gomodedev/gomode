// G2Session: one Even G2 connection from both arms' links to a closed page, ending with a typed close cause.
//
// Order: bond each arm that has no bond, the right then the left, one at a time and without a
// deadline, since the wearer may have to consent. Connect both arms in parallel with
// notifications on each. AUTH the right arm, then the left, waiting for each arm's success (the
// behaviour of the GPL faceclaw client; the Python library also AUTHs the right arm first but
// listens on the right arm only and never waits for the left). Then, as the even-g2-protocol Python library (MIT)
// does: send the prelude to the right arm and wait for its ACK. Then read the settings of the
// right arm, as faceclaw does, to learn the firmware (G2Settings); an unanswered read counts as
// stock firmware. Create the text page and send a heartbeat every G2Protocol.HEARTBEAT_INTERVAL_MS.
// On stock firmware the text is updated in place whenever the host's text differs from the
// acknowledged one, and an image shows as empty text. Every Even Hub command goes to the right arm.
//
// On Faceclaw firmware, as faceclaw does: take the framebuffer lease on both arms before the page,
// renew it every G2Settings.LEASE_RENEW_MS, and release it on every exit while a link remains, so
// stock widgets repaint at once. The page is created with empty text: its container only captures
// input. Each frame goes through the private stream (G2CfwStream) to the left arm, which hands it
// to the right lens; every message waits for both lenses' ACK, and a missing or refused ACK ends
// the session. Its packets go out back to back, which the stream allows, and carry up to 252
// bytes, zlib-compressed, where the Even Hub messages carry 139 bytes 50 ms apart.
//
// Once the page may be open, a press from either arm is reported and leaves the page open.
// A dropped link, a missing or refused reply, or a page exit event ends the session with a
// G2CloseCause. From the moment the create request is written, the page may be open: unless
// the right link is gone or the glasses closed the page, every exit shuts it down before both
// links close, so a quick reconnect over a link Android keeps alive does not meet an open page.
//
// A direct session gives each arm G2Protocol.CONNECT_TIMEOUT_MS to connect. A background session
// waits for the arms without a deadline, as Android's background connection does, until the first
// arm's link is set up; the other arm then has CONNECT_TIMEOUT_MS, so one arm alone never holds a
// link that no session uses.

package com.fghbuild.eveng2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Request identifiers that stay unique across the sessions of one [G2Glasses]. */
internal class G2Ids {
    private var magic = 0
    private var sync = 0
    private var stream = 0
    private var session = 0L

    /** Next request magic; the range fits both AUTH and Even Hub. */
    @Synchronized
    fun magic(): Int {
        magic = magic % G2Auth.MAGIC_RANGE.last + 1
        return magic
    }

    @Synchronized
    fun sync(): Int {
        sync = (sync + 1) and 0xFF
        return sync
    }

    /** Next first sequence of a private-stream message, whose ACKs name it. */
    @Synchronized
    fun stream(): Int {
        stream = (stream + 1) and 0xFF
        return stream
    }

    @Synchronized
    fun session(): Long {
        session = session % UINT32_MAX + 1
        return session
    }
}

private class G2Closed(
    val reason: G2CloseCause,
    cause: Throwable? = null,
) : Exception(reason.toString(), cause)

private sealed interface Inbound {
    class Message(
        val side: G2Side,
        val message: G2Message,
    ) : Inbound

    class Lost(
        val side: G2Side,
        val status: Int?,
    ) : Inbound
}

internal class G2Session(
    private val pair: G2Pair,
    private val connector: G2ArmConnector,
    private val background: Boolean,
    private val page: G2TextPage,
    private val ids: G2Ids,
    private val desired: StateFlow<G2Content>,
    private val onReady: (G2Firmware, G2Content) -> Unit,
    private val onPress: () -> Unit,
) {
    private val links = ConcurrentHashMap<G2Side, G2ArmLink>()
    private val inbox = Channel<Inbound>(INBOX_CAPACITY)
    private val writeLock = Mutex()
    private val ringFilter = G2RingFilter()
    private val stream = G2CfwStream()

    // The session ID of the page that may be open; null until its create request is written.
    private var pageSession: Long? = null

    // Whether the framebuffer lease may be held; set before its first request is written.
    private var leased = false

    /** Run until the session ends; returns why. Cancellation closes the page and both links. */
    suspend fun run(): G2CloseCause {
        try {
            coroutineScope {
                connectBoth()
                links.values.forEach { pump(this, it) }
                authenticate(G2Side.RIGHT)
                authenticate(G2Side.LEFT)
                prelude()
                val firmware = firmware()
                if (firmware is G2Firmware.Faceclaw) {
                    leased = true
                    lease(G2Settings.LEASE_ACQUIRE)
                    launch { renewLease() }
                }
                val shown = createPage(firmware)
                launch { heartbeat() }
                val wake = Channel<Unit>(Channel.CONFLATED)
                launch { desired.collect { wake.send(Unit) } }
                serve(firmware, shown, wake)
            }
        } catch (e: G2Closed) {
            val reason = e.reason
            val rightLost = reason is G2CloseCause.Disconnected && reason.side == G2Side.RIGHT
            releaseLease()
            if (reason !is G2CloseCause.PageExited && !rightLost) shutdownPage()
            return reason
        } catch (e: CancellationException) {
            releaseLease()
            shutdownPage()
            throw e
        } finally {
            links.values.forEach { it.close() }
            stream.close()
        }
    }

    private suspend fun connectBoth() =
        coroutineScope {
            for (arm in listOf(pair.right, pair.left)) {
                try {
                    connector.bond(arm)
                } catch (e: G2Exception) {
                    throw G2Closed(G2CloseCause.PairingFailed(arm.side, e.message.orEmpty()), e)
                }
            }
            val firstUp = CompletableDeferred<Unit>()
            val arms =
                listOf(pair.left, pair.right).map { arm ->
                    launch {
                        try {
                            // Register before anything can resume: a cancellation that lands as
                            // connect() returns would otherwise lose an open link.
                            if (background) {
                                connector.connect(arm, background = true).also { links[arm.side] = it }
                            } else {
                                withTimeout(G2Protocol.CONNECT_TIMEOUT_MS) {
                                    connector.connect(arm, background = false).also { links[arm.side] = it }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            throw G2Closed(G2CloseCause.ConnectFailed(arm.side, "timed out"), e)
                        } catch (e: G2Exception) {
                            throw G2Closed(G2CloseCause.ConnectFailed(arm.side, e.message.orEmpty()), e)
                        }
                        firstUp.complete(Unit)
                    }
                }
            if (background) {
                firstUp.await()
                if (withTimeoutOrNull(G2Protocol.CONNECT_TIMEOUT_MS) { arms.joinAll() } == null) {
                    // An arm whose link registered at the deadline finishes on its own.
                    G2Side.entries.firstOrNull { !links.containsKey(it) }?.let {
                        throw G2Closed(G2CloseCause.ConnectFailed(it, "timed out"))
                    }
                }
            }
        }

    /** Reassemble one arm's notifications into [inbox], then report the link's loss. */
    private fun pump(
        scope: CoroutineScope,
        link: G2ArmLink,
    ) {
        scope.launch {
            val assembler = G2FrameAssembler()
            val status =
                try {
                    for (packet in link.notifications) {
                        assembler.accept(packet)?.let { inbox.send(Inbound.Message(link.side, it)) }
                    }
                    null
                } catch (e: G2LinkLostException) {
                    e.status
                }
            inbox.send(Inbound.Lost(link.side, status))
        }
    }

    /** Accept one inbound item: throws on a lost link or an exit of this session's page, and reports presses. */
    private fun accept(inbound: Inbound): Inbound.Message {
        when (inbound) {
            is Inbound.Lost -> {
                throw G2Closed(G2CloseCause.Disconnected(inbound.side, inbound.status))
            }

            is Inbound.Message -> {
                val session = pageSession ?: return inbound
                if (inbound.side == G2Side.RIGHT) {
                    G2EvenHub.pageExit(inbound.message, page, session)?.let { throw G2Closed(it) }
                }
                val ring = G2EvenHub.ringReport(inbound.message)
                if ((ring == null || ringFilter.accept(ring)) &&
                    G2EvenHub.press(inbound.message, page, session)
                ) {
                    onPress()
                }
                return inbound
            }
        }
    }

    /** First match from either arm within [timeoutMs], or null. */
    private suspend fun <T : Any> await(
        timeoutMs: Long,
        match: (G2Side, G2Message) -> T?,
    ): T? =
        withTimeoutOrNull(timeoutMs) {
            var found: T? = null
            while (found == null) {
                val inbound = accept(inbox.receive())
                found = match(inbound.side, inbound.message)
            }
            found
        }

    private suspend fun write(
        side: G2Side,
        packets: List<ByteArray>,
        gapMs: Long = PACKET_GAP_MS,
    ) {
        val link = links.getValue(side)
        writeLock.withLock {
            try {
                packets.forEachIndexed { index, packet ->
                    if (index > 0 && gapMs > 0) delay(gapMs)
                    link.write(packet)
                }
            } catch (e: G2LinkLostException) {
                throw G2Closed(G2CloseCause.Disconnected(side, e.status), e)
            } catch (e: G2Exception) {
                throw G2Closed(G2CloseCause.WriteFailed(side, e.message.orEmpty()), e)
            }
        }
    }

    private suspend fun authenticate(side: G2Side) {
        val magics = mutableSetOf<Int>()
        repeat((G2Protocol.AUTH_TIMEOUT_MS / G2Protocol.AUTH_RESEND_MS).toInt()) {
            val magic = ids.magic()
            magics += magic
            write(side, listOf(G2Auth.request(magic, ids.sync())))
            val authenticated =
                await(G2Protocol.AUTH_RESEND_MS) { from, message ->
                    (from == side && G2Auth.reply(message, magics) == G2AuthReply.AUTHENTICATED).takeIf { it }
                }
            if (authenticated != null) return
        }
        throw G2Closed(G2CloseCause.AuthTimedOut(side))
    }

    private suspend fun prelude() {
        write(G2Side.RIGHT, listOf(G2Prelude.frame()))
        await(G2Protocol.COMMAND_TIMEOUT_MS) { from, message ->
            (from == G2Side.RIGHT && G2Prelude.isAck(message)).takeIf { it }
        } ?: throw G2Closed(G2CloseCause.PreludeTimedOut)
    }

    // TODO(hardware): faceclaw reads the settings on stock firmware too, but reports that a right arm
    // on stock 2.2.9 sometimes stays silent after AUTH and then asks the left arm. On a pair on stock
    // firmware and on Faceclaw/45, log this read's reply on each connection. If the right arm misses
    // it, ask the left arm too before taking the firmware as stock, since a missed reply on Faceclaw
    // firmware draws only the stock text container until the next connection.
    private suspend fun firmware(): G2Firmware {
        val magic = ids.magic()
        write(G2Side.RIGHT, G2Settings.read(magic, ids.sync()))
        return await(G2Protocol.COMMAND_TIMEOUT_MS) { from, message ->
            if (from == G2Side.RIGHT) G2Settings.firmware(message, magic) else null
        } ?: G2Firmware.EvenHub(null)
    }

    private suspend fun lease(op: Int) {
        write(G2Side.RIGHT, G2Settings.lease(op, ids.sync()))
        write(G2Side.LEFT, G2Settings.lease(op, ids.sync()))
    }

    private suspend fun renewLease() {
        while (true) {
            delay(G2Settings.LEASE_RENEW_MS)
            lease(G2Settings.LEASE_ACQUIRE)
        }
    }

    /** Best effort, after the pumps stop: release the lease on every arm whose link remains. */
    private suspend fun releaseLease() {
        if (!leased) return
        withContext(NonCancellable) {
            withTimeoutOrNull(G2Protocol.COMMAND_TIMEOUT_MS) {
                for (link in listOfNotNull(links[G2Side.RIGHT], links[G2Side.LEFT])) {
                    try {
                        G2Settings.lease(G2Settings.LEASE_RELEASE, ids.sync()).forEach { link.write(it) }
                    } catch (_: G2Exception) {
                        // The link is gone; the firmware drops the lease on its own.
                    }
                }
            }
        }
    }

    /** Send [message] on the private stream and wait until both lenses acknowledge it. */
    private suspend fun cfw(message: ByteArray) {
        // TODO(hardware): Every drawing message waits for its ACK before the next one goes out; faceclaw
        // keeps three in flight. g2flash says a PRESENT fails when the display task does not answer within
        // a second, which would end the session here. On a pair on Faceclaw/45, time a text frame and a
        // 640×480 photo, and count NACKs over an hour of transcripts. If a frame takes over a second,
        // pipeline the messages as CfwMessageWindow.kt of faceclaw does; if NACKs occur, resend a refused
        // message once with a reset record before ending the session. faceclaw also requests
        // CONNECTION_PRIORITY_HIGH on each arm, which G2GattConnector does not; g2flash's firmware asks
        // for a 7.5 ms interval on its own (patches/ble_link.c). If frames are slow, request it too.
        val link = links.getValue(G2Side.LEFT)
        val id = ids.stream()
        write(G2Side.LEFT, stream.packets(message, id, link.mtu), gapMs = 0)
        val checksum = G2Frame.crc16(message)
        val acked = mutableSetOf<Int>()
        val outcome =
            await(G2Protocol.COMMAND_TIMEOUT_MS) { from, inbound ->
                val ack = if (from == G2Side.LEFT) G2CfwStream.ack(inbound) else null
                when {
                    ack == null || ack.stream != id || ack.message != 0 -> null
                    ack.nack -> false
                    ack.size != message.size || ack.checksum != checksum -> null
                    else -> true.takeIf { acked.add(ack.lens) && acked.size == 2 }
                }
            }
        if (outcome != true) throw G2Closed(G2CloseCause.DrawFailed(rejected = outcome == false))
    }

    private suspend fun draw(content: G2Content) = G2Draw.frame(content).forEach { cfw(it) }

    private suspend fun command(
        command: G2HubCommand,
        build: (magic: Int, sync: Int) -> List<ByteArray>,
    ) {
        val magic = ids.magic()
        val sync = ids.sync()
        write(G2Side.RIGHT, build(magic, sync))
        val reply =
            await(G2Protocol.COMMAND_TIMEOUT_MS) { from, message ->
                if (from == G2Side.RIGHT) G2EvenHub.reply(message, command, magic, sync) else null
            }
        when (reply) {
            null -> throw G2Closed(G2CloseCause.CommandTimedOut(command))
            is G2HubReply.Rejected -> throw G2Closed(G2CloseCause.CommandRejected(command, reply.result))
            G2HubReply.Success -> Unit
        }
    }

    /**
     * Open the page and show the host's content on it: on stock firmware its text, or empty text when
     * it exceeds the creation limit; on Faceclaw firmware a frame drawn over an empty container.
     * Returns what is shown.
     */
    private suspend fun createPage(firmware: G2Firmware): G2Content {
        val target = shown(firmware, desired.value)
        val text =
            (target as? G2Content.Text)
                ?.text
                ?.takeIf { firmware is G2Firmware.EvenHub && it.toByteArray().size <= G2Display.MAX_CREATE_TEXT_BYTES }
                .orEmpty()
        val session = ids.session()
        // Set before the request is written: the page may open even when its ACK never arrives.
        pageSession = session
        command(G2HubCommand.CREATE_PAGE) { magic, sync -> G2EvenHub.createPage(page, text, session, magic, sync) }
        val shown =
            if (firmware is G2Firmware.Faceclaw) {
                cfw(G2Draw.clearRoot)
                draw(target)
                target
            } else {
                G2Content.Text(text)
            }
        onReady(firmware, shown)
        return shown
    }

    // What [firmware] shows of [content]: stock firmware shows an image as empty text.
    private fun shown(
        firmware: G2Firmware,
        content: G2Content,
    ): G2Content = if (firmware is G2Firmware.EvenHub && content is G2Content.Image) G2Content.Text("") else content

    // TODO(hardware): Heartbeats are not awaited because both consumer-firmware sources send
    // them fire-and-forget; the enterprise SDK expects an ACK (command 13, or 12 on enterprise
    // firmware) within 3 s. On a real pair, log the replies to heartbeats. If they are reliable,
    // await each one with command(G2HubCommand.HEARTBEAT) to detect a stalled page without a
    // link drop.
    private suspend fun heartbeat() {
        while (true) {
            delay(G2Protocol.HEARTBEAT_INTERVAL_MS)
            write(G2Side.RIGHT, G2EvenHub.heartbeat(ids.magic(), ids.sync()))
        }
    }

    /** Show the host's content, forever; unsolicited messages only matter as page exits and presses. */
    private suspend fun serve(
        firmware: G2Firmware,
        initial: G2Content,
        wake: Channel<Unit>,
    ): Nothing {
        var shown = initial
        while (true) {
            val target = shown(firmware, desired.value)
            if (target == shown) {
                select<Unit> {
                    wake.onReceive { }
                    inbox.onReceive { accept(it) }
                }
            } else {
                when (firmware) {
                    is G2Firmware.Faceclaw -> {
                        draw(target)
                    }

                    is G2Firmware.EvenHub -> {
                        val text = (target as G2Content.Text).text
                        command(
                            G2HubCommand.UPDATE_TEXT,
                        ) { magic, sync -> G2EvenHub.updateText(page, text, magic, sync) }
                    }
                }
                shown = target
                onReady(firmware, target)
            }
        }
    }

    /**
     * Best effort: ask the right arm to close the page and wait briefly for its ACK. The pumps
     * have stopped, so this reads the right link's notifications directly.
     */
    private suspend fun shutdownPage() {
        if (pageSession == null) return
        val link = links[G2Side.RIGHT] ?: return
        withContext(NonCancellable) {
            withTimeoutOrNull(G2Protocol.COMMAND_TIMEOUT_MS) {
                try {
                    val magic = ids.magic()
                    val sync = ids.sync()
                    G2EvenHub.shutdownPage(magic, sync).forEach { link.write(it) }
                    val assembler = G2FrameAssembler()
                    for (packet in link.notifications) {
                        val message = assembler.accept(packet) ?: continue
                        if (G2EvenHub.reply(message, G2HubCommand.SHUTDOWN_PAGE, magic, sync) != null) break
                    }
                } catch (_: G2Exception) {
                    // The link is gone; closing it is all that remains.
                }
            }
        }
    }

    private companion object {
        const val INBOX_CAPACITY = 64

        // The Python library spaces the packets of one message 50 ms apart and reports that the
        // glasses need it.
        const val PACKET_GAP_MS = 50L
    }
}
