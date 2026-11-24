// Tests the transcript lines glasses show, their fit on the connected display, frame coalescing, and clearing.
package com.fghbuild.gomode.glasses

import com.fghbuild.gomode.voice.TranscriptEntry
import com.fghbuild.gomode.voice.TranscriptKind
import com.fghbuild.gomode.voice.TranscriptSpeaker
import com.fghbuild.gomode.voice.VoiceState
import com.fghbuild.halo.msg.CircularTextLayout
import com.fghbuild.halo.msg.HaloDisplay
import com.fghbuild.halo.msg.TxTextPage
import com.fghbuild.voicegateway.sdk.v2.MessageKind
import com.fghbuild.voicegateway.sdk.v2.TranscriptDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private val EARLIER =
    listOf(
        TranscriptEntry(TranscriptSpeaker.USER, "What time is it?", final = true),
        TranscriptEntry(TranscriptSpeaker.ASSISTANT, "It is noon.", final = true),
    )

private const val QUESTION =
    "Can you read me the list of everything that is still open on the kitchen renovation, starting with the plumbing?"
private const val REPLY =
    "Three items are open. The plumber comes on Tuesday to move the sink drain. The electrician still has to " +
        "quote the under-cabinet lights. The countertop template is booked for the following Monday morning."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassesTranscriptTest {
    @Test
    fun theCurrentExchangeShowsTheLabelledQuestionAndTheReplyWithoutReasoning() {
        val state =
            active(
                EARLIER +
                    TranscriptEntry(TranscriptSpeaker.USER, "What is the code word?", final = true) +
                    TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Recall the note.", kind = TranscriptKind.REASONING) +
                    TranscriptEntry(TranscriptSpeaker.ASSISTANT, "The code word\nis cedar."),
            )
        assertEquals(
            "You: What is the code word?\nThe code word is cedar.",
            glassesTranscriptText(state, HALO_TEXT_CAPACITY),
        )
    }

    @Test
    fun aGatewayDraftReplacesTheLastExchangeWhileTheUserSpeaks() {
        val state =
            active(
                EARLIER,
            ).copy(transcriptDraft = TranscriptDraft(MessageKind.TranscriptDraft, "d1", "and the"))
        assertEquals("You: and the", glassesTranscriptText(state, HALO_TEXT_CAPACITY))
    }

    @Test
    fun aDeviceRecognitionDraftShowsAsTheUsersLine() {
        val state = active(EARLIER + TranscriptEntry(TranscriptSpeaker.USER, "and the", final = false))
        assertEquals("You: and the", glassesTranscriptText(state, HALO_TEXT_CAPACITY))
    }

    @Test
    fun aLongReplyKeepsItsNewestWordsAndDropsTheQuestion() {
        val text = glassesTranscriptText(active(exchange(QUESTION, REPLY)), HALO_TEXT_CAPACITY)
        assertTrue(text, text.startsWith("…") && text.endsWith("Monday morning."))
        assertFalse(text, text.contains("You:") || text.contains('\n'))
        assertTrue(text, text.length <= HALO_TEXT_CAPACITY)
        // The cut starts at a word.
        assertTrue(text, REPLY.contains(" " + text.removePrefix("…")))
    }

    @Test
    fun aLongQuestionKeepsItsLabelAndNewestWordsAboveAShortReply() {
        val text = glassesTranscriptText(active(exchange(QUESTION, "Yes.")), HALO_TEXT_CAPACITY)
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(text, lines[0].startsWith("You: …") && lines[0].endsWith("starting with the plumbing?"))
        assertEquals("Yes.", lines[1])
    }

    @Test
    fun theLongestTranscriptsFitOneHaloPage() {
        val layout = CircularTextLayout(HaloDisplay.SIZE_PX, HaloDisplay.SIZE_PX, HALO_FONT_SIZE_PX)
        for (state in listOf(
            active(exchange(QUESTION, REPLY)),
            active(exchange(QUESTION, "Yes, the plumbing first.")),
        )) {
            val text = glassesTranscriptText(state, HALO_TEXT_CAPACITY)
            val page = TxTextPage(layout, text)
            page.measureNextPage()
            assertFalse("Halo cuts \"${page.remainingText()}\" from \"$text\"", page.hasMoreText)
        }
    }

    @Test
    fun aLargerDisplayKeepsTheWholeReplyAndTheNewestWordsOfTheQuestion() {
        val text = glassesTranscriptText(active(exchange(QUESTION, REPLY)), G2_TEXT_CAPACITY)
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(text, lines[0].startsWith("You: …") && lines[0].endsWith("starting with the plumbing?"))
        assertEquals(REPLY, lines[1])
        assertTrue(text, text.length <= G2_TEXT_CAPACITY)
    }

    @Test
    fun framesFollowTheConnectedDisplaysCapacityAndClearWithoutGlasses() =
        runTest {
            val capacity = MutableStateFlow<Int?>(HALO_TEXT_CAPACITY)
            val frames = follow(MutableStateFlow(active(exchange(QUESTION, REPLY))), capacity = capacity)
            capacity.value = G2_TEXT_CAPACITY
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            capacity.value = null
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            assertEquals(3, frames.size)
            assertFalse(frames[0], frames[0].contains("You:"))
            assertTrue(frames[1], frames[1].startsWith("You: …") && frames[1].endsWith(REPLY))
            assertEquals("", frames[2])
        }

    @Test
    fun framesFollowDraftRevisionsAtMostTwiceASecondAndEndOnTheNewest() =
        runTest {
            val words = "one two three four five six seven eight nine".split(" ")
            val voice = MutableStateFlow(active(EARLIER).withDraft(words.first()))
            val frames = follow(voice)
            for (count in 2..words.size) {
                voice.value = active(EARLIER).withDraft(words.take(count).joinToString(" "))
                advanceTimeBy(100)
                runCurrent()
            }
            advanceTimeBy(2 * GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            // Nine revisions in 0.8 s draw three frames: at once, after 0.5 s, and after 1 s.
            assertEquals(3, frames.size)
            assertEquals("You: one", frames.first())
            assertEquals("You: ${words.joinToString(" ")}", frames.last())
        }

    @Test
    fun aStateChangeThatLeavesTheTextAsItWasSendsNoFrame() =
        runTest {
            val voice = MutableStateFlow(active(EARLIER))
            val frames = follow(voice)
            voice.value = voice.value.copy(micLevel = 0.5f)
            advanceTimeBy(2 * GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf("You: What time is it?\nIt is noon."), frames)
        }

    @Test
    fun endingTheSessionClearsTheGlasses() =
        runTest {
            val voice = MutableStateFlow(active(EARLIER))
            val frames = follow(voice)
            voice.value = voice.value.copy(connected = false)
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf("You: What time is it?\nIt is noon.", ""), frames)
        }

    @Test
    fun theSettingClearsTheGlassesAndShowsTheTranscriptAgain() =
        runTest {
            val voice = MutableStateFlow(active(EARLIER))
            val enabled = MutableStateFlow(false)
            val frames = follow(voice, enabled)
            assertEquals(emptyList<String>(), frames)
            enabled.value = true
            runCurrent()
            enabled.value = false
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf("You: What time is it?\nIt is noon.", ""), frames)
        }

    @Test
    fun anIdleSessionLeavesTheGlassesToTheOtherOwner() =
        runTest {
            val voice = MutableStateFlow(VoiceState(transcript = EARLIER))
            val job =
                launch {
                    showVoiceTranscript(voice, MutableStateFlow(true), MutableStateFlow(HALO_TEXT_CAPACITY)) {
                        error("Idle sent \"$it\"")
                    }
                }
            runCurrent()
            job.cancel()
            runCurrent()
        }

    @Test
    fun cancellingWhileShowingClearsTheGlasses() =
        runTest {
            val frames = mutableListOf<String>()
            val job =
                launch {
                    showVoiceTranscript(
                        MutableStateFlow(active(EARLIER)),
                        MutableStateFlow(true),
                        MutableStateFlow(HALO_TEXT_CAPACITY),
                        frames::add,
                    )
                }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(listOf("You: What time is it?\nIt is noon.", ""), frames)
        }

    // Records what a fake display is told to show until the test ends.
    private fun TestScope.follow(
        voice: MutableStateFlow<VoiceState>,
        enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        capacity: MutableStateFlow<Int?> = MutableStateFlow(HALO_TEXT_CAPACITY),
    ): List<String> {
        val frames = mutableListOf<String>()
        backgroundScope.launch { showVoiceTranscript(voice, enabled, capacity, frames::add) }
        runCurrent()
        return frames
    }
}

private fun active(transcript: List<TranscriptEntry>) = VoiceState(connected = true, transcript = transcript)

private fun VoiceState.withDraft(text: String) =
    copy(transcriptDraft = TranscriptDraft(MessageKind.TranscriptDraft, "d1", text))

private fun exchange(
    question: String,
    reply: String,
) = EARLIER +
    TranscriptEntry(TranscriptSpeaker.USER, question, final = true) +
    TranscriptEntry(TranscriptSpeaker.ASSISTANT, reply, final = true)
