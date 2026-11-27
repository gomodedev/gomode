// Compose tests for the voice panel's problem list: one line, bullets per key, resolution, and one X.
package com.fghbuild.gomode.voice

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.fghbuild.gomode.ui.theme.GoModeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceIssueListTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val issues = VoiceIssues()

    @Test
    fun aFieldProblemOfAnotherOriginIsStale() {
        val a = VoiceIssueOrigin.Setup("a", "https://a.example", 0)
        val b = VoiceIssueOrigin.Setup("b", "https://b.example", 0)
        issues.raise(VoiceIssueKey.SAVE_SPEAKING_VOICE, "Unable to save speaking voice: disk full", a)
        issues.raise(VoiceIssueKey.SAVE_SPEECH, "Unable to save speech: disk full", a)
        assertEquals(null, issues.state.value.message(VoiceIssueKey.SAVE_SPEAKING_VOICE, b))
        issues.resolveStale(VoiceIssueKey.SAVE_SPEAKING_VOICE, a)
        assertEquals(2, issues.state.value.entries.size)
        issues.resolveStale(VoiceIssueKey.SAVE_SPEAKING_VOICE, b)
        assertEquals(
            listOf(VoiceIssueKey.SAVE_SPEECH),
            issues.state.value.entries
                .map { it.key },
        )
    }

    @Test
    fun aSingleProblemShowsAsOneLineWithoutABullet() {
        issues.raise(VoiceIssueKey.CONNECTION, "Gateway unavailable")
        showPanel()
        composeRule.onAllNodesWithTag("gomode-voice-issue").assertCountEquals(1)
        composeRule.onAllNodesWithTag("gomode-voice-issue-bullet", useUnmergedTree = true).assertCountEquals(0)
        composeRule
            .onNodeWithTag("gomode-voice-issue-message", useUnmergedTree = true)
            .assertTextEquals("Gateway unavailable")
    }

    @Test
    fun eachKeyShowsOneBulletAndARepeatedKeyReplacesItsBulletInPlace() {
        issues.raise(VoiceIssueKey.CONNECTION, "Gateway unavailable")
        issues.raise(VoiceIssueKey.AUDIO_ROUTE, "Could not switch audio device")
        issues.raise(VoiceIssueKey.CONNECTION, "Connection timed out")
        showPanel()
        composeRule.onAllNodesWithTag("gomode-voice-issue").assertCountEquals(2)
        composeRule.onAllNodesWithTag("gomode-voice-issue-bullet", useUnmergedTree = true).assertCountEquals(2)
        assertEquals(listOf("Connection timed out", "Could not switch audio device"), shownMessages())
    }

    @Test
    fun aProblemLeavesTheListWhenItsSourceResolvesIt() {
        issues.raise(VoiceIssueKey.CONNECTION, "Gateway unavailable")
        issues.raise(VoiceIssueKey.GATEWAY, "llm: out of memory")
        showPanel()
        composeRule.onAllNodesWithTag("gomode-voice-issue-bullet", useUnmergedTree = true).assertCountEquals(2)
        issues.resolve(VoiceIssueKey.CONNECTION)
        composeRule.waitForIdle()
        assertEquals(listOf("llm: out of memory"), shownMessages())
        composeRule.onAllNodesWithTag("gomode-voice-issue-bullet", useUnmergedTree = true).assertCountEquals(0)
        issues.resolve(VoiceIssueKey.GATEWAY)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("gomode-voice-issues").assertDoesNotExist()
    }

    @Test
    fun oneXClearsEveryProblemIncludingThoseBeyondTheNewestThree() {
        issues.raise(VoiceIssueKey.CONNECTION, "Gateway unavailable")
        issues.raise(VoiceIssueKey.GATEWAY, "llm: out of memory")
        issues.raise(VoiceIssueKey.AUDIO_ROUTE, "Could not switch audio device")
        issues.raise(VoiceIssueKey.CAR_CALL, "Go Mode could not start the call.")
        showPanel()
        assertEquals(
            listOf("llm: out of memory", "Could not switch audio device", "Go Mode could not start the call."),
            shownMessages(),
        )
        composeRule.onNodeWithTag("gomode-voice-issues-dismiss").performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<VoiceIssue>(), issues.state.value.entries)
        composeRule.onNodeWithTag("gomode-voice-issues").assertDoesNotExist()
    }

    @Test
    fun aLongProblemIsCutWithAnEllipsisAndReadInFull() {
        val message = "Voice gateway rejected the session: " + "reason ".repeat(40).trim()
        issues.raise(VoiceIssueKey.CONNECTION, message)
        showPanel()
        val shown = shownMessages().single()
        assertEquals(160, shown.length)
        assertEquals('…', shown.last())
        composeRule
            .onNodeWithTag("gomode-voice-issue-message", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(message)))
    }

    @Test
    fun aSettingsFieldProblemIsListedOnlyWhileSettingsAreHidden() {
        issues.raise(VoiceIssueKey.SAVE_SPEECH, "Unable to save speech: disk full")
        val hide = issues.showSettings()
        showPanel()
        composeRule.onNodeWithTag("gomode-voice-issues").assertDoesNotExist()
        hide()
        hide()
        composeRule.waitForIdle()
        assertEquals(listOf("Unable to save speech: disk full"), shownMessages())
    }

    private fun shownMessages(): List<String> =
        composeRule
            .onAllNodesWithTag("gomode-voice-issue-message", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    // Lists and dismisses like the shell: the X resolves every listed problem.
    private fun showPanel() {
        composeRule.setContent {
            val state by issues.state.collectAsState()
            GoModeTheme {
                VoicePanel(
                    voiceState = VoiceState(),
                    voiceEnabled = true,
                    onConnect = {},
                    onDisconnect = {},
                    onSelectMicrophoneMode = {},
                    onSelectDevice = {},
                    onOpenSettings = {},
                    serviceReconnect = null,
                    onCycleApp = {},
                    canCycleApps = false,
                    issues = state.listed,
                    onDismissIssues = { issues.resolve(*state.listed.map { it.key }.toTypedArray()) },
                )
            }
        }
        composeRule.waitForIdle()
    }
}
