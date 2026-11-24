// G2Session: one Even G2 connection from both arms' links to a closed page, ending with a typed close cause.
//
// Order: connect both arms in parallel with notifications on each; AUTH the right arm, then
// the left, waiting for each arm's success (the behaviour of the GPL faceclaw client; the
// Python library also AUTHs the right arm first but listens on the right arm only and never
// waits for the left). Then, as the even-g2-protocol Python library (MIT)
// does: send the prelude to the right arm and wait for its ACK; create the text page; send a
// heartbeat every G2Protocol.HEARTBEAT_INTERVAL_MS. The text is updated in place whenever the
// host's text differs from the acknowledged one. Every Even Hub command goes to the right arm.
// A dropped link, a missing or refused reply, or a page exit event ends the session with a
// G2CloseCause. From the moment the create request is written, the page may be open: unless
// the right link is gone or the glasses closed the page, every exit shuts it down before both
// links close, so a quick reconnect over a link Android keeps alive does not meet an open page.

package com.fghbuild.eveng2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Request identifiers that stay unique across the reconnects of one [G2Glasses]. */
internal class G2Ids {
    private var magic = 0
    private var sync = 0
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
    private val page: G2TextPage,
    private val ids: G2Ids,
    private val desired: StateFlow<String>,
    private val onReady: (String) -> Unit,
) {
    private val links = ConcurrentHashMap<G2Side, G2ArmLink>()
    private val inbox = Channel<Inbound>(INBOX_CAPACITY)
    private val writeLock = Mutex()

    // The session ID of the page that may be open; null until its create request is written.
    private var pageSession: Long? = null

    /** Run until the session ends; returns why. Cancellation closes the page and both links. */
    suspend fun run(): G2CloseCause {
        try {
            coroutineScope {
                connectBoth()
                links.values.forEach { pump(this, it) }
                authenticate(G2Side.RIGHT)
                authenticate(G2Side.LEFT)
                prelude()
                val text = createPage()
                launch { heartbeat() }
                val wake = Channel<Unit>(Channel.CONFLATED)
                launch { desired.collect { wake.send(Unit) } }
                serve(text, wake)
            }
        } catch (e: G2Closed) {
            val reason = e.reason
            val rightLost = reason is G2CloseCause.Disconnected && reason.side == G2Side.RIGHT
            if (reason !is G2CloseCause.PageExited && !rightLost) shutdownPage()
            return reason
        } catch (e: CancellationException) {
            shutdownPage()
            throw e
        } finally {
            links.values.forEach { it.close() }
        }
    }

    private suspend fun connectBoth() =
        coroutineScope {
            for (arm in listOf(pair.left, pair.right)) {
                launch {
                    try {
                        // Register before anything can resume: a cancellation that lands as
                        // connect() returns would otherwise lose an open link.
                        withTimeout(G2Protocol.CONNECT_TIMEOUT_MS) {
                            connector.connect(arm).also { links[arm.side] = it }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw G2Closed(G2CloseCause.ConnectFailed(arm.side, "timed out"), e)
                    } catch (e: G2Exception) {
                        throw G2Closed(G2CloseCause.ConnectFailed(arm.side, e.message.orEmpty()), e)
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

    /** Accept one inbound item: throws on a lost link or an exit of this session's page. */
    private fun accept(inbound: Inbound): Inbound.Message {
        when (inbound) {
            is Inbound.Lost -> {
                throw G2Closed(G2CloseCause.Disconnected(inbound.side, inbound.status))
            }

            is Inbound.Message -> {
                val session = pageSession
                if (inbound.side == G2Side.RIGHT && session != null) {
                    G2EvenHub.pageExit(inbound.message, page, session)?.let { throw G2Closed(it) }
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
    ) {
        val link = links.getValue(side)
        writeLock.withLock {
            try {
                packets.forEachIndexed { index, packet ->
                    if (index > 0) delay(PACKET_GAP_MS)
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

    /** Open the page with the host's text, or empty when it exceeds the creation limit; returns the text shown. */
    private suspend fun createPage(): String {
        val text = desired.value.takeIf { it.toByteArray().size <= G2Display.MAX_CREATE_TEXT_BYTES }.orEmpty()
        val session = ids.session()
        // Set before the request is written: the page may open even when its ACK never arrives.
        pageSession = session
        command(G2HubCommand.CREATE_PAGE) { magic, sync -> G2EvenHub.createPage(page, text, session, magic, sync) }
        onReady(text)
        return text
    }

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

    /** Apply the host's text in place, forever; unsolicited messages only matter as page exits. */
    private suspend fun serve(
        initial: String,
        wake: Channel<Unit>,
    ): Nothing {
        var shown = initial
        while (true) {
            val target = desired.value
            if (target == shown) {
                select<Unit> {
                    wake.onReceive { }
                    inbox.onReceive { accept(it) }
                }
            } else {
                command(G2HubCommand.UPDATE_TEXT) { magic, sync -> G2EvenHub.updateText(page, target, magic, sync) }
                shown = target
                onReady(target)
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
