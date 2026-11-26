// G2Session: one Even G2 connection from both arms' links to a typed close cause, reopening pages the glasses close.
//
// Order: bond each arm that has no bond, the right then the left, one at a time and without a
// deadline, since the wearer may have to consent. Connect both arms in parallel with
// notifications on each. AUTH the right arm, then the left, waiting for each arm's success (the
// behaviour of the GPL faceclaw client; the Python library also AUTHs the right arm first but
// listens on the right arm only and never waits for the left). Then, as the even-g2-protocol Python library (MIT)
// does: send the prelude to the right arm and wait for its ACK. Then read the settings of the
// right arm, as faceclaw does, to learn the firmware (G2Settings); an unanswered read counts as
// stock firmware. Create the text page and, while it is open, send a heartbeat every
// G2Protocol.HEARTBEAT_INTERVAL_MS.
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
// Once the page may be open, a gesture from either arm is reported and leaves the page open.
// A dropped link or a missing or refused reply ends the session with a G2CloseCause. From the
// moment the create request is written, the page may be open: unless the right link is gone or
// the glasses closed the page, every exit shuts it down before both links close, so a quick
// reconnect over a link Android keeps alive does not meet an open page.
//
// An exit event (6 abnormal, 7 exit) of the open page ends that page, not the session, as faceclaw
// treats it: the session creates a new page, with a new page session ID, that shows the latest
// content. On Faceclaw firmware the framebuffer lease outlives the page: g2flash's settings_ext.c
// and zlib_glue.c end it only at its deadline, on a release, or at CFW cleanup. The session keeps
// renewing it and draws the frame again over the new page. The next page follows at once when the
// closed page stayed open G2Protocol.PAGE_SETTLED_MS from its create request. Otherwise the session
// waits G2Protocol.PAGE_RETURN_DELAY_MS, doubled for each further page in a row that closes before
// it settles, up to G2Protocol.PAGE_RETURN_MAX_DELAY_MS: a page that keeps closing at once costs
// one create request every few seconds, and the page returns at most that long after the glasses
// keep it again. During the pause the session sends no heartbeat and still reads both arms, so a
// dropped link ends it.
//
// A direct session gives each arm G2Protocol.CONNECT_TIMEOUT_MS to connect. A background session
// waits for the arms without a deadline, as Android's background connection does, until the first
// arm's link is set up; the other arm then has CONNECT_TIMEOUT_MS, so one arm alone never holds a
// link that no session uses.
//
// Batteries (G2BatteryCodec), as faceclaw's session polls them: once the page may be open, the
// session reads the settings of the right arm, which hold the glasses' battery, and the case's state,
// also from the right arm. It does so only after G2Protocol.BATTERY_QUIET_MS without an Even Hub
// command, a drawing message, or an input event, so its requests never queue behind or between the
// frames of a transcript and the wearer's gestures. Heartbeats and lease renewals do not count:
// they never stop. The next round follows G2Protocol.BATTERY_CHARGING_POLL_MS later while the
// glasses charge or sit in the case, where nobody wears them, and G2Protocol.BATTERY_POLL_MS later
// otherwise. Any settings reply or push on the right link gives the glasses' level, including the
// firmware read at session start, so the level shows before the page opens. The case pushes its
// changes on its own, and docking wakes the next round, so the glasses' charging shows a few
// seconds after they enter the case; leaving it, the right arm pushes its settings, which clears
// the charging at once. Nothing awaits a reply: a
// missing one keeps the last reading, and a session whose arm or case never answers goes on
// without it.
//
// Microphone (G2Microphone): while the host listens and a page is open, the session starts the
// microphone with an Even Hub command and decodes the packets that either arm notifies, from the
// start's acknowledgement to the stop or the page's close, into PCM for the host. It stops the
// microphone when the last listener leaves. Each page starts with the microphone off, as faceclaw
// takes a page that comes back, so the session starts it again on the next page. An ending session
// sends no stop: it shuts the page down and closes both links. The decoding runs in the session's
// context: one packet of five frames took 30 µs on a desktop JVM.
//
// Sensors (G2Sensors): wear events and head up and down are read from either arm whenever they come;
// on Faceclaw firmware the session turns the stock wear detector on and then asks both arms for the
// wear state once, after the lease. While the
// host collects the sensors or motion and a page is open, the session streams the accelerometer with an
// Even Hub command, as it runs the microphone, and stops it when the last collector leaves; each page
// starts with it off. Its samples are no input: they never delay a battery round. On Faceclaw firmware
// the same collectors also run the compass through the private stream, and the collectors of the
// sensors alone the ambient light sensor: its polling stops stock auto-brightness, so motion leaves it
// off. Both outlive pages, a refused or unanswered control is logged and leaves the session up, and
// an exit stops the compass, best effort, while the lease's release stops the light sensor.

package com.fghbuild.eveng2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
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

// The glasses closed the open page; the session goes on with the next one.
private class G2PageClosed : Exception("page closed")

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

private class G2AudioPacket(
    val side: G2Side,
    val packet: ByteArray,
)

internal class G2Session(
    private val pair: G2Pair,
    private val connector: G2ArmConnector,
    private val background: Boolean,
    private val page: G2TextPage,
    private val ids: G2Ids,
    private val desired: StateFlow<G2Content>,
    // How many collectors listen to the microphone; it runs while there is one.
    private val listeners: StateFlow<Int>,
    // How many collectors read motion, the accelerometer and the compass; they run while there is one.
    private val motionReaders: StateFlow<Int>,
    // How many collectors read the light sensor; it runs while there is one.
    private val lightReaders: StateFlow<Int>,
    private val onReady: (G2Firmware, G2Content) -> Unit,
    private val onGesture: (G2Gesture) -> Unit,
    private val onEvent: (G2InputEvent) -> Unit,
    private val onBattery: (G2Battery) -> Unit,
    private val onSensors: (G2Sensors) -> Unit,
    private val onAudio: (ShortArray) -> Unit,
    private val log: (String) -> Unit,
) {
    private val links = ConcurrentHashMap<G2Side, G2ArmLink>()
    private val inbox = Channel<Inbound>(INBOX_CAPACITY)
    private val writeLock = Mutex()
    private val ringFilter = G2RingFilter()
    private val stream = G2CfwStream()

    // The session ID of the page that may be open; null before its create request is written and
    // once the glasses close it.
    private var pageSession: Long? = null

    // Whether the framebuffer lease may be held; set before its first request is written.
    private var leased = false

    // Bumped by each Even Hub command, drawing message, and input event; battery reads wait for it to rest.
    @Volatile private var activity = 0L

    // The readings so far; only accept() changes them.
    @Volatile private var battery = G2Battery()

    // Wakes the battery poll when the glasses enter the case.
    private val docked = Channel<Unit>(Channel.CONFLATED)

    // Both arms' microphone packets, in arrival order, for one decoder.
    private val audio = Channel<G2AudioPacket>(AUDIO_CAPACITY, BufferOverflow.DROP_OLDEST)

    // The microphone's stream from the acknowledgement of its start to its stop or the page's close;
    // null while it is off. Only microphone() and stopListening() change it.
    @Volatile private var listening: G2MicrophoneStream? = null

    // Packets that arrived while the microphone was off, since its last start; a count for the log.
    @Volatile private var stray = 0L

    // The readings so far; only updateSensors() changes them.
    @Volatile private var sensors = G2Sensors()

    // Whether this page's accelerometer stream may run: set before its start is written, cleared before
    // its stop and when the page closes. Only imu() and imuOff() change it.
    @Volatile private var imu = false

    // Samples since the stream's last start, and those that came while it was off; counts for the log.
    private var imuSamples = 0L
    private var strayImu = 0L

    // Whether Faceclaw firmware's light sensor may run: set before its start is written, cleared before
    // its stop. Only light() changes it.
    @Volatile private var lightRuns = false

    // Whether Faceclaw firmware's compass may run, as lightRuns for the light sensor. Only compass() changes it.
    @Volatile private var compassRuns = false

    // Light reports and headings since their last start; counts for the log.
    private var lightReports = 0L
    private var headings = 0L

    /** Run until the session ends; returns why. Cancellation closes the page and both links. */
    suspend fun run(): G2CloseCause {
        try {
            coroutineScope {
                connectBoth()
                links.values.forEach {
                    pump(this, it)
                    pumpAudio(this, it)
                }
                launch { decodeAudio() }
                authenticate(G2Side.RIGHT)
                authenticate(G2Side.LEFT)
                prelude()
                val firmware = firmware()
                if (firmware is G2Firmware.Faceclaw) {
                    leased = true
                    control(G2Settings.LEASE_ACQUIRE)
                    launch { renewLease() }
                    enableWearDetection()
                    control(G2Settings.WEAR_QUERY)
                }
                launch { pollBattery() }
                val wake = Channel<Unit>(Channel.CONFLATED)
                launch { desired.collect { wake.send(Unit) } }
                launch { listeners.collect { wake.send(Unit) } }
                launch { motionReaders.collect { wake.send(Unit) } }
                launch { lightReaders.collect { wake.send(Unit) } }
                servePages(this, firmware, wake)
            }
        } catch (e: G2Closed) {
            val reason = e.reason
            val rightLost = reason is G2CloseCause.Disconnected && reason.side == G2Side.RIGHT
            releaseLease()
            if (!rightLost) shutdownPage()
            return reason
        } catch (e: CancellationException) {
            releaseLease()
            shutdownPage()
            throw e
        } finally {
            stopListening()
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

    /** Forward one arm's microphone packets to [audio]; [pump] reports the link's loss. */
    private fun pumpAudio(
        scope: CoroutineScope,
        link: G2ArmLink,
    ) {
        scope.launch {
            try {
                for (packet in link.audio) audio.send(G2AudioPacket(link.side, packet))
            } catch (_: G2LinkLostException) {
                // The notification pump reports it.
            }
        }
    }

    /** Decode the microphone's packets into PCM for the host while it is on, forever. */
    private suspend fun decodeAudio(): Nothing {
        // The stream whose first packet went to the log.
        var logged: G2MicrophoneStream? = null
        while (true) {
            val received = audio.receive()
            val stream = listening
            if (stream == null) {
                stray++
                continue
            }
            if (stream !== logged) {
                logged = stream
                log("Even G2 first microphone packet from ${received.side}: ${received.packet.hex()}")
            }
            stream.accept(received.side, received.packet)?.let(onAudio)
        }
    }

    /** Accept one inbound item: throws on a lost link or an exit of the open page, and reports gestures. */
    private fun accept(inbound: Inbound): Inbound.Message {
        when (inbound) {
            is Inbound.Lost -> {
                throw G2Closed(G2CloseCause.Disconnected(inbound.side, inbound.status))
            }

            is Inbound.Message -> {
                G2EvenHub.inputEvent(inbound.message, inbound.side)?.let {
                    activity++
                    onEvent(it)
                    readHead(inbound.side, it)
                }
                readSensors(inbound.side, inbound.message)
                readBattery(inbound.side, inbound.message)
                val session = pageSession ?: return inbound
                if (inbound.side == G2Side.RIGHT) {
                    G2EvenHub.pageExit(inbound.message, page, session)?.let { exit ->
                        log("Even G2 page $session closed: $exit")
                        pageSession = null
                        throw G2PageClosed()
                    }
                }
                val ring = G2EvenHub.ringReport(inbound.message)
                if (ring == null || ringFilter.accept(ring)) {
                    G2EvenHub.gesture(inbound.message, page, session)?.let(onGesture)
                }
                return inbound
            }
        }
    }

    /** Takes where the head went from [event] that [side] reported, when it is head up or down. */
    private fun readHead(
        side: G2Side,
        event: G2InputEvent,
    ) {
        val head =
            when (event.type) {
                G2InputEvent.HEAD_UP -> G2Head.UP
                G2InputEvent.HEAD_DOWN -> G2Head.DOWN
                else -> return
            }
        log("Even G2 head $head from $side")
        updateSensors { it.copy(head = head) }
    }

    /** Takes the wear state or accelerometer sample that [message] from [side] may hold. */
    private fun readSensors(
        side: G2Side,
        message: G2Message,
    ) {
        G2SensorCodec.wearing(message)?.let { worn ->
            log("Even G2 $side reports the glasses ${if (worn) "worn" else "not worn"}: ${message.payload.hex()}")
            updateSensors { it.copy(wearing = worn) }
        }
        G2SensorCodec.light(message)?.let { light ->
            if (lightReports++ == 0L) log("Even G2 first light report from $side, $light: ${message.payload.hex()}")
            if (lightRuns) updateSensors { it.copy(light = light) }
        }
        G2SensorCodec.heading(message)?.let { heading ->
            if (headings++ == 0L) log("Even G2 first heading from $side, $heading°: ${message.payload.hex()}")
            if (compassRuns) updateSensors { it.copy(heading = heading) }
        }
        val sample = G2EvenHub.acceleration(message) ?: return
        if (!imu) {
            strayImu++
            return
        }
        if (imuSamples++ == 0L) log("Even G2 first IMU sample from $side, $sample: ${message.payload.hex()}")
        updateSensors { it.copy(acceleration = sample) }
    }

    private fun updateSensors(change: (G2Sensors) -> G2Sensors) {
        val next = change(sensors)
        if (next != sensors) {
            sensors = next
            onSensors(next)
        }
    }

    /** Takes the battery or case reading that [message] from [side] may hold. */
    private fun readBattery(
        side: G2Side,
        message: G2Message,
    ) {
        // Light reports come twice a second; readSensors logs the first.
        val settings = message.sid == G2Protocol.SID_SETTINGS && G2SensorCodec.light(message) == null
        if (settings || message.sid == G2Protocol.SID_CASE) {
            val sid = "%02x".format(message.sid)
            log("Even G2 $side reply on SID 0x$sid, result ${message.result}: ${message.payload.hex()}")
        }
        // Only the right arm reads and pushes the glasses' level; see G2Battery.
        val glasses = if (side == G2Side.RIGHT) G2BatteryCodec.glasses(message) else null
        val next =
            if (glasses != null) {
                battery.copy(glasses = glasses)
            } else {
                when (val case = G2BatteryCodec.case(message)) {
                    null -> return
                    is G2CaseInfo.InCase -> battery.copy(case = case.charge)
                    G2CaseInfo.OutOfCase -> battery.copy(case = null)
                }
            }
        if (battery.case == null && next.case != null) docked.trySend(Unit)
        if (next != battery) {
            battery = next
            onBattery(next)
        }
    }

    // The battery fields and pushes, confirmed on a pair on 2.3.0.24 with Faceclaw/45: see G2Battery.
    // Worn, the right arm answered the settings reads of both links, so the session reads it alone
    // and reports one level. Docked with this page open, the reply set charging (13) and the case
    // answered with field 4; taking the glasses out pushed the case's change and the arm's settings
    // at once, so the poll only catches what no push reports.
    // TODO(hardware): Whether the level is the right arm's alone or the pair's is unknown; nothing on
    // hand reads the left arm. When the Even app is at hand, compare its levels with this one, and if
    // this one is the right arm's, say so in G2Battery and on the host's battery row.

    /** Read the batteries in rounds, forever; see the header. */
    private suspend fun pollBattery(): Nothing {
        while (true) {
            do {
                val seen = activity
                delay(G2Protocol.BATTERY_QUIET_MS)
            } while (activity != seen)
            log("Even G2 battery round")
            write(G2Side.RIGHT, G2Settings.read(ids.magic(), ids.sync()))
            write(G2Side.RIGHT, G2BatteryCodec.caseRead(ids.magic(), ids.sync()))
            // This round's replies arrive well within the shorter wait, which therefore decides.
            val wake = withTimeoutOrNull(G2Protocol.BATTERY_CHARGING_POLL_MS) { docked.receive() }
            val charging = battery.let { it.case != null || it.glasses?.charging == true }
            if (wake == null && !charging) {
                withTimeoutOrNull(G2Protocol.BATTERY_POLL_MS - G2Protocol.BATTERY_CHARGING_POLL_MS) { docked.receive() }
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

    // A pair on 2.3.0.24 with Faceclaw/45 answered the wear query "not worn" while worn and sent no
    // wear event on taking the glasses off and on until this write, as faceclaw makes it before its
    // query; after it, the query answered "worn" and the right arm sent "not worn" and "worn" as the
    // glasses came off and went back on, with the accelerometer streaming.

    /** Turn the stock wear detector on, on the right arm, and log its reply, so the next wear query reads it. */
    private suspend fun enableWearDetection() {
        val magic = ids.magic()
        write(G2Side.RIGHT, G2Settings.enableWearDetection(magic, ids.sync()))
        val reply =
            await(G2Protocol.COMMAND_TIMEOUT_MS) { from, message ->
                message.takeIf { from == G2Side.RIGHT && G2Settings.answers(it, magic) }
            }
        log("Even G2 wear detection on: ${reply?.let { "result ${it.result}: ${it.payload.hex()}" } ?: "no answer"}")
    }

    /** Send Faceclaw control [op] to both arms. */
    private suspend fun control(op: Int) {
        write(G2Side.RIGHT, G2Settings.control(op, ids.sync()))
        write(G2Side.LEFT, G2Settings.control(op, ids.sync()))
    }

    private suspend fun renewLease() {
        while (true) {
            delay(G2Settings.LEASE_RENEW_MS)
            control(G2Settings.LEASE_ACQUIRE)
        }
    }

    /**
     * Best effort, after the pumps stop: stop the compass, which nothing else stops, then release the
     * lease on every arm whose link remains, which stops the light sensor.
     */
    private suspend fun releaseLease() {
        if (!leased) return
        withContext(NonCancellable) {
            withTimeoutOrNull(G2Protocol.COMMAND_TIMEOUT_MS) {
                val left = links[G2Side.LEFT]
                if (compassRuns && left != null) {
                    try {
                        stream.packets(G2SensorCodec.compass(false), ids.stream(), left.mtu).forEach { left.write(it) }
                    } catch (_: G2Exception) {
                        // The link is gone; the compass runs until a later session stops it.
                    }
                }
                for (link in listOfNotNull(links[G2Side.RIGHT], links[G2Side.LEFT])) {
                    try {
                        G2Settings.control(G2Settings.LEASE_RELEASE, ids.sync()).forEach { link.write(it) }
                    } catch (_: G2Exception) {
                        // The link is gone; the firmware drops the lease on its own.
                    }
                }
            }
        }
    }

    /** Send [message] on the private stream and wait until both lenses acknowledge it; throws otherwise. */
    private suspend fun cfw(message: ByteArray) {
        // TODO(hardware): Every drawing message waits for its ACK before the next one goes out; faceclaw
        // keeps three in flight. g2flash says a PRESENT fails when the display task does not answer within
        // a second, which would end the session here. On a pair on Faceclaw/45, time a text frame and a
        // 640×480 photo, and count NACKs over an hour of transcripts. If a frame takes over a second,
        // pipeline the messages as CfwMessageWindow.kt of faceclaw does; if NACKs occur, resend a refused
        // message once with a reset record before ending the session. faceclaw also requests
        // CONNECTION_PRIORITY_HIGH on each arm, which G2GattConnector does not; g2flash's firmware asks
        // for a 7.5 ms interval on its own (patches/ble_link.c). If frames are slow, request it too.
        val outcome = send(message)
        if (outcome != true) throw G2Closed(G2CloseCause.DrawFailed(rejected = outcome == false))
    }

    /**
     * Send [message] on the private stream and wait for both lenses: true when both acknowledge it, false
     * when one refuses it, and null without both answers in time.
     */
    private suspend fun send(message: ByteArray): Boolean? {
        activity++
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
        return outcome
    }

    /** Start Faceclaw firmware's light sensor when [on], else stop it; a failure is logged. */
    private suspend fun light(on: Boolean) {
        if (on) {
            lightReports = 0
            lightRuns = true
        } else {
            lightRuns = false
            log("Even G2 light off after $lightReports light reports")
            updateSensors { it.copy(light = null) }
        }
        cfwSensor("light", on, G2SensorCodec.ambientLight(on))
    }

    /** Start Faceclaw firmware's compass when [on], else stop it; a failure is logged. */
    private suspend fun compass(on: Boolean) {
        if (on) {
            headings = 0
            compassRuns = true
        } else {
            compassRuns = false
            log("Even G2 compass off after $headings headings")
            updateSensors { it.copy(heading = null) }
        }
        cfwSensor("compass", on, G2SensorCodec.compass(on))
    }

    // Sends the [name]d sensor's control [message] and logs the lenses' answer.
    private suspend fun cfwSensor(
        name: String,
        on: Boolean,
        message: ByteArray,
    ) {
        val outcome = send(message)
        val answer = outcome?.let { if (it) "ACK" else "NACK" } ?: "no answer"
        log("Even G2 $name ${if (on) "start" else "stop"}: $answer")
        // A refusal resets the lens's zlib context; an unanswered message may have.
        if (outcome != true) stream.reset()
    }

    private suspend fun draw(content: G2Content) = G2Draw.frame(content).forEach { cfw(it) }

    private suspend fun command(
        command: G2HubCommand,
        build: (magic: Int, sync: Int) -> List<ByteArray>,
    ) {
        val reply = exchange(command, build) { message, magic, sync -> G2EvenHub.reply(message, command, magic, sync) }
        if (reply is G2HubReply.Rejected) throw G2Closed(G2CloseCause.CommandRejected(command, reply.result))
    }

    /**
     * Send [command] to the right arm and return the first message from it that [reply] matches, given
     * the request's magic and sync; throws when none comes in time.
     */
    private suspend fun <T : Any> exchange(
        command: G2HubCommand,
        build: (magic: Int, sync: Int) -> List<ByteArray>,
        reply: (message: G2Message, magic: Int, sync: Int) -> T?,
    ): T {
        activity++
        val magic = ids.magic()
        val sync = ids.sync()
        write(G2Side.RIGHT, build(magic, sync))
        return await(G2Protocol.COMMAND_TIMEOUT_MS) { from, message ->
            if (from == G2Side.RIGHT) reply(message, magic, sync) else null
        } ?: throw G2Closed(G2CloseCause.CommandTimedOut(command))
    }

    // On a pair on 2.3.0.24 with Faceclaw/45, a worn 5 s recording came from the left arm alone: 100
    // packets, none lost, none duplicated, none from the right arm. Both pumps stay, since one recording
    // does not show which arm sends on other firmware or when an arm drops.
    // TODO(hardware): Unverified on a pair: whether the glasses stop the microphone when they close the
    // page. faceclaw takes a page that comes back as one with the microphone off, which this follows.
    // Each start logs the packets that came while it was off. Record with the glasses on, take them off
    // and on so that the page comes back, and read the log: if packets came between the close and the
    // next start, the close leaves the microphone on, and the session must either stop it before the
    // next page or keep its stream across pages. The decoding fixtures are liblc3's encoding of a
    // synthetic signal: each start logs its first packet in hex, so a recording on a pair can give a
    // fixture of the glasses' own encoder.

    /** Start the microphone when [on], else stop it; any reply with the request's magic counts, as faceclaw counts it. */
    private suspend fun microphone(on: Boolean) {
        if (!on) stopListening()
        val command = if (on) G2HubCommand.MICROPHONE_ON else G2HubCommand.MICROPHONE_OFF
        val reply =
            exchange(command, { magic, sync -> G2EvenHub.microphone(on, magic, sync) }) { message, magic, _ ->
                G2EvenHub.echo(message, magic)
            }
        if (!reply.expected(command)) log("Even G2 $command answered $reply")
        if (on) {
            log("Even G2 microphone on; $stray packets came while it was off")
            stray = 0
            listening = G2MicrophoneStream()
        }
    }

    /** Start the accelerometer's stream when [on], else stop it; any reply with its magic counts. */
    private suspend fun imu(on: Boolean) {
        if (on) {
            log("Even G2 IMU on; $strayImu samples came while it was off")
            strayImu = 0
            imuSamples = 0
            imu = true
        } else {
            imuOff()
        }
        activity++
        val magic = ids.magic()
        write(G2Side.RIGHT, G2SensorCodec.imu(on, magic, ids.sync()))
        val echo =
            await(G2Protocol.COMMAND_TIMEOUT_MS) { from, message ->
                if (from == G2Side.RIGHT) G2EvenHub.echo(message, magic) else null
            }
        log("Even G2 IMU ${if (on) "start" else "stop"} answered ${echo ?: "nothing"}")
    }

    // The accelerometer's stream ends, and with it its last sample; its count goes to the log.
    private fun imuOff() {
        if (!imu) return
        imu = false
        log("Even G2 IMU off after $imuSamples samples")
        updateSensors { it.copy(acceleration = null) }
    }

    // The microphone's stream ends; its counts go to the log.
    private fun stopListening() {
        val stream = listening ?: return
        listening = null
        log("Even G2 microphone off: $stream")
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
        log("Even G2 page $session requested")
        command(G2HubCommand.CREATE_PAGE) { magic, sync -> G2EvenHub.createPage(page, text, session, magic, sync) }
        log("Even G2 page $session acknowledged")
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

    // TODO(hardware): Why the glasses close the page is not established. On a pair on 2.3.0.24 with
    // Faceclaw/45, putting the glasses back on brought an exit (7) with no reason 0.5 s after the
    // right arm reported them worn, and the next create was acknowledged within a second, so the
    // page came back without a tap on the phone; hypotheses are the stock wear handler, the stock
    // heartbeat timeout, and the stock quit dialog. The host log now shows each exit's type, source, and reason
    // (field 4), and each create's request and acknowledgement: on a pair, take them off and on and
    // record whether the create is acknowledged while they are off. If the firmware refuses or
    // closes pages until they are worn again, create the next page on the wear event instead of
    // after these pauses; if it never answers that create, the session ends with CommandTimedOut.

    /** Keep a page open, forever: create one with the latest content and, each time it closes, the next. */
    private suspend fun servePages(
        scope: CoroutineScope,
        firmware: G2Firmware,
        wake: Channel<Unit>,
    ): Nothing {
        // The pause before the next page: none after a page that settled.
        var pause = 0L
        while (true) {
            // Completes once the page has stayed open long enough to settle.
            val settling = scope.launch { delay(G2Protocol.PAGE_SETTLED_MS) }
            // From the create request on, since the page may open even when its ACK never arrives.
            val beating = scope.launch { heartbeat() }
            try {
                serve(firmware, createPage(firmware), wake)
            } catch (_: G2PageClosed) {
                pause =
                    if (settling.isCompleted) {
                        0
                    } else {
                        (pause * 2).coerceIn(G2Protocol.PAGE_RETURN_DELAY_MS, G2Protocol.PAGE_RETURN_MAX_DELAY_MS)
                    }
            } finally {
                settling.cancel()
                beating.cancel()
                stopListening()
                imuOff()
            }
            if (pause > 0) {
                log("Even G2 page reopens in $pause ms")
                withTimeoutOrNull(pause) {
                    while (true) accept(inbox.receive())
                }
            }
        }
    }

    /**
     * Show the host's content and run the microphone while the host listens, until the page closes;
     * unsolicited messages only matter as page exits and gestures.
     */
    private suspend fun serve(
        firmware: G2Firmware,
        initial: G2Content,
        wake: Channel<Unit>,
    ): Nothing {
        var shown = initial
        while (true) {
            val target = shown(firmware, desired.value)
            val listen = listeners.value > 0
            val motion = motionReaders.value > 0
            val faceclaw = firmware is G2Firmware.Faceclaw
            val compass = faceclaw && motion
            val light = faceclaw && lightReaders.value > 0
            if (listen != (listening != null)) {
                microphone(listen)
            } else if (motion != imu) {
                imu(motion)
            } else if (compass != compassRuns) {
                compass(compass)
            } else if (light != lightRuns) {
                light(light)
            } else if (target == shown) {
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

        // 3.2 s of microphone packets; the oldest are dropped on overflow.
        const val AUDIO_CAPACITY = 64

        // The Python library spaces the packets of one message 50 ms apart and reports that the
        // glasses need it.
        const val PACKET_GAP_MS = 50L
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
