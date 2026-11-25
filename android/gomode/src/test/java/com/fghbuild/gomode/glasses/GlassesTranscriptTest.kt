// Tests the transcript lines glasses show, held tool content and its screens, frame coalescing, and clearing.
package com.fghbuild.gomode.glasses

import android.graphics.Bitmap
import com.fghbuild.eveng2.G2Display
import com.fghbuild.eveng2.G2TextPage
import com.fghbuild.gomode.voice.TranscriptEntry
import com.fghbuild.gomode.voice.TranscriptKind
import com.fghbuild.gomode.voice.TranscriptSpeaker
import com.fghbuild.gomode.voice.UserContent
import com.fghbuild.gomode.voice.VoiceState
import com.fghbuild.gomode.voice.withDeviceUserText
import com.fghbuild.gomode.voice.withUserContent
import com.fghbuild.gomode.voice.withUserTurnStart
import com.fghbuild.halo.msg.TextPageData
import com.fghbuild.voicegateway.sdk.v2.MessageKind
import com.fghbuild.voicegateway.sdk.v2.TranscriptDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

// Capitals as wide as the font has: the last 96 characters of the reply overflow one Halo page.
private const val WIDE_REPLY =
    "WOW. MAXIMUM WARMTH WARNING ON WOMBAT MOUNTAIN. WHAM, MOW, WOMB, MEMO, WAWA, MUMM, WWWW, MMMM, " +
        "WAMW MWAW WOMW MOWM WMWM MWMW WWMM MMWW, WOW WOW WOW MOM MOM MOM, MAMMOTH MEMORY WAVE AT NOON."

// A link reads as one unbroken word, longer than a page.
private const val LINK_REPLY =
    "Open https://example.com/share/" +
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz" +
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-end"

private val G2_FIT = G2ScreenFit(G2TextPage())
private val HALO = GlassesLink.Connected(HALO_DISPLAY, HaloScreenFit)
private val G2 = GlassesLink.Connected(G2_DISPLAY, G2_FIT)

@OptIn(ExperimentalCoroutinesApi::class)
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
            glassesTranscriptText(state, HaloScreenFit),
        )
    }

    @Test
    fun aGatewayDraftReplacesTheLastExchangeWhileTheUserSpeaks() {
        val state =
            active(
                EARLIER,
            ).copy(transcriptDraft = TranscriptDraft(MessageKind.TranscriptDraft, "d1", "and the"))
        assertEquals("You: and the", glassesTranscriptText(state, HaloScreenFit))
    }

    @Test
    fun aDeviceRecognitionDraftShowsAsTheUsersLine() {
        val state = active(EARLIER + TranscriptEntry(TranscriptSpeaker.USER, "and the", final = false))
        assertEquals("You: and the", glassesTranscriptText(state, HaloScreenFit))
    }

    @Test
    fun aLongReplyKeepsItsNewestWordsAndDropsTheQuestion() {
        val text = glassesTranscriptText(active(exchange(QUESTION, REPLY)), HaloScreenFit)
        assertTrue(text, text.startsWith("…") && text.endsWith("Monday morning."))
        assertFalse(text, text.contains("You:") || text.contains('\n'))
        assertOneHaloPage(text)
        // The cut starts at a word.
        assertTrue(text, REPLY.contains(" " + text.removePrefix("…")))
    }

    @Test
    fun aLongQuestionKeepsItsLabelAndNewestWordsAboveAShortReply() {
        for (reply in listOf("Yes.", "Yes, the plumbing first.")) {
            val text = glassesTranscriptText(active(exchange(QUESTION, reply)), HaloScreenFit)
            val lines = text.lines()
            assertEquals(2, lines.size)
            assertTrue(text, lines[0].startsWith("You: …") && lines[0].endsWith("starting with the plumbing?"))
            assertEquals(reply, lines[1])
            assertOneHaloPage(text)
        }
    }

    @Test
    fun wideCapitalsKeepTheNewestWordsOnOneHaloPage() {
        assertFalse(HaloScreenFit.fits(WIDE_REPLY.takeLast(96)))
        val text = glassesTranscriptText(active(exchange(QUESTION, WIDE_REPLY)), HaloScreenFit)
        assertTrue(text, text.startsWith("…") && text.endsWith("MAMMOTH MEMORY WAVE AT NOON."))
        assertTrue(text, WIDE_REPLY.contains(" " + text.removePrefix("…")))
        assertOneHaloPage(text)
    }

    @Test
    fun aWordLongerThanAPageKeepsItsNewestCharactersOnOneHaloPage() {
        for (state in listOf(
            active(exchange(QUESTION, LINK_REPLY)),
            active(EARLIER).withDraft(LINK_REPLY),
        )) {
            val text = glassesTranscriptText(state, HaloScreenFit)
            val label = if (state.transcriptDraft != null) "You: …" else "…"
            assertTrue(text, text.startsWith(label) && text.endsWith("XYZ-end"))
            // The cut falls inside the link, so the page shows its end and not the word before it.
            assertTrue(text, LINK_REPLY.endsWith(text.removePrefix(label)) && !text.contains("Open"))
            assertOneHaloPage(text)
            // The page has no room for one more character of the link.
            val kept = text.length - label.length
            assertFalse(HaloScreenFit.fits(label + LINK_REPLY.takeLast(kept + 1)))
        }
    }

    @Test
    fun theLargerG2DisplayShowsTheWholeExchangeAndCutsALongerOne() {
        assertEquals("You: $QUESTION\n$REPLY", glassesTranscriptText(active(exchange(QUESTION, REPLY)), G2_FIT))
        val reply = List(4) { REPLY }.joinToString(" ")
        val text = glassesTranscriptText(active(exchange(QUESTION, reply)), G2_FIT)
        assertTrue(text, text.startsWith("…") && text.endsWith("Monday morning.") && !text.contains("You:"))
        assertTrue(text, text.length > 2 * REPLY.length)
        assertTrue(text, G2_FIT.fits(text))
    }

    @Test
    fun g2KeepsTheNewestCharactersWithinItsByteLimit() {
        // U+1D400 takes 4 UTF-8 bytes and is drawn 4 px wide: 520 of them fit ten lines but not 1999 bytes.
        val bold = "\uD835\uDC00"
        val text = glassesTranscriptText(active(exchange(QUESTION, bold.repeat(520))), G2_FIT)
        assertTrue(text.take(4), text.startsWith("…$bold") && text.endsWith(bold))
        assertTrue(text.toByteArray().size <= G2Display.MAX_TEXT_BYTES)
        // The glasses get the whole text: the byte limit cuts nothing.
        assertEquals(text, g2Text(text))
    }

    @Test
    fun framesFollowTheConnectedDisplaysFitAndClearWithoutGlasses() =
        runTest {
            val fit = MutableStateFlow<GlassesLink.Connected?>(HALO)
            val frames = follow(MutableStateFlow(active(exchange(QUESTION, REPLY))), screen = fit)
            fit.value = G2
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            fit.value = null
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            assertEquals(3, frames.size)
            assertFalse(frames[0], frames[0].contains("You:"))
            assertEquals("You: $QUESTION\n$REPLY", frames[1])
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
                    showVoiceTranscript(voice, MutableStateFlow(true), MutableStateFlow(HALO)) {
                        error("Idle sent $it")
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
                        MutableStateFlow(HALO),
                    ) { frames += it.text() }
                }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(listOf("You: What time is it?\nIt is noon.", ""), frames)
        }

    // The Halo page that shows [text] holds all of it, whatever the fit says.
    private fun assertOneHaloPage(text: String) {
        val page = haloTextMessages(text).first().second as TextPageData
        val shown = page.lineTexts.joinToString("").filterNot(Char::isWhitespace)
        assertEquals(text.filterNot(Char::isWhitespace), shown)
    }

    @Test
    fun heldToolTextReplacesTheTranscriptUntilTheUsersNextTurnStarts() =
        runTest {
            val held = active(EARLIER).withUserContent(listOf(UserContent.Text("Build green")))
            val voice = MutableStateFlow(held)
            val frames = follow(voice)
            // The reply to the tool result keeps the content up.
            voice.value =
                held.copy(transcript = EARLIER + TranscriptEntry(TranscriptSpeaker.ASSISTANT, "Done.", final = true))
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            // So does a realtime provider's late transcript of the speech that called the tool.
            voice.value =
                voice.value.copy(
                    transcript = voice.value.transcript + TranscriptEntry(TranscriptSpeaker.USER, "Show the build."),
                )
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            // The gateway reports new speech before its draft.
            voice.value = voice.value.withUserTurnStart().withDraft("and the")
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf("Build green", "You: and the"), frames)
        }

    @Test
    fun deviceRecognitionStartsAUserTurnWithTheFirstWordsOfEachUtterance() {
        var state = active(EARLIER)
        for ((text, final) in listOf("" to false, "and" to false, "and the" to false, "and the tests" to true)) {
            state = state.withDeviceUserText(text, final)
        }
        assertEquals(1, state.userTurnStarts)
        state = state.withDeviceUserText("thanks", final = true)
        assertEquals(2, state.userTurnStarts)
    }

    @Test
    fun heldToolContentShowsWithTheTranscriptSettingOffAndEachDisplayDrawsItsLastDrawableBlock() =
        runTest {
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val content = listOf(UserContent.Text("2 running"), UserContent.Image(image))
            val voice = MutableStateFlow(active(EARLIER).withUserContent(content))
            val screen = MutableStateFlow<GlassesLink.Connected?>(HALO)
            val frames = mutableListOf<GlassesFrame>()
            backgroundScope.launch { showVoiceTranscript(voice, MutableStateFlow(false), screen, frames::add) }
            runCurrent()
            // Even G2 declares no images, so it draws the text.
            screen.value = G2
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            voice.value = active(EARLIER + TranscriptEntry(TranscriptSpeaker.USER, "Thanks"))
            advanceTimeBy(GLASSES_FRAME_INTERVAL_MS)
            runCurrent()
            val expected = listOf(GlassesFrame.Image(image), GlassesFrame.Text("2 running"), GlassesFrame.Text(""))
            assertEquals(expected, frames)
        }

    @Test
    fun aLongHeldTextShowsItsScreensInTurnEachForItsReadingTime() =
        runTest {
            val pages = glassesPages(REPLY, HaloScreenFit)
            assertEquals(List(pages.size) { "${it + 1}/${pages.size}" }, pages.map { it.lines().last() })
            val frames = follow(MutableStateFlow(active(EARLIER).withUserContent(listOf(UserContent.Text(REPLY)))))
            assertEquals(pages.take(1), frames)
            advanceTimeBy(glassesPageMillis(pages[0]) - 1)
            runCurrent()
            assertEquals(pages.take(1), frames)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(pages.take(2), frames)
            for (page in pages.drop(1)) advanceTimeBy(glassesPageMillis(page))
            runCurrent()
            assertEquals(pages + pages.first(), frames)
        }

    @Test
    fun screensOfAHeldTextEachFitAndKeepEveryWordInOrder() {
        for (fit in listOf(HaloScreenFit, G2_FIT)) {
            val text = "$REPLY\n$WIDE_REPLY $LINK_REPLY $QUESTION"
            val pages = glassesPages(text, fit)
            assertTrue(pages.size > 1)
            assertTrue(pages.all(fit::fits))
            val bodies = pages.mapIndexed { index, page -> page.removeSuffix("\n${index + 1}/${pages.size}") }
            assertEquals(text.filterNot(Char::isWhitespace), bodies.joinToString("").filterNot(Char::isWhitespace))
        }
        assertEquals(listOf("Build green"), glassesPages("  Build green\n", HaloScreenFit))
        assertEquals(GLASSES_MAX_PAGES, glassesPages(REPLY.repeat(GLASSES_MAX_PAGES), HaloScreenFit).size)
    }

    // Records the text a fake display is told to show until the test ends.
    private fun TestScope.follow(
        voice: MutableStateFlow<VoiceState>,
        enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        screen: MutableStateFlow<GlassesLink.Connected?> = MutableStateFlow(HALO),
    ): List<String> {
        val frames = mutableListOf<String>()
        backgroundScope.launch { showVoiceTranscript(voice, enabled, screen) { frames += it.text() } }
        runCurrent()
        return frames
    }
}

private fun GlassesFrame.text(): String = (this as GlassesFrame.Text).text

private fun active(transcript: List<TranscriptEntry>) = VoiceState(connected = true, transcript = transcript)

private fun VoiceState.withDraft(text: String) =
    copy(transcriptDraft = TranscriptDraft(MessageKind.TranscriptDraft, "d1", text))

private fun exchange(
    question: String,
    reply: String,
) = EARLIER +
    TranscriptEntry(TranscriptSpeaker.USER, question, final = true) +
    TranscriptEntry(TranscriptSpeaker.ASSISTANT, reply, final = true)
