// Renders voice problems as one line or a bulleted list beside one X that clears them all.
package com.fghbuild.gomode.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.R

/** The list shows the newest problems up to this count, like the browser's. */
private const val MAX_SHOWN_VOICE_ISSUES = 3

/** Longer problem messages are cut with "…"; TalkBack reads the full text. */
private const val MAX_VOICE_ISSUE_CHARS = 160

private val DismissTarget = 48.dp

/**
 * One problem renders as a line; several as a bulleted list, oldest first. The X calls [onDismiss],
 * which resolves every problem in [issues], shown or not. The X stays beside the first line while
 * problems wrap below it.
 */
@Composable
internal fun VoiceIssueList(
    issues: List<VoiceIssue>,
    onDismiss: () -> Unit,
    contentInset: Dp,
) {
    val shown = issues.takeLast(MAX_SHOWN_VOICE_ISSUES)
    if (shown.isEmpty()) return
    val style = MaterialTheme.typography.bodyMedium
    // Center the first line on the X.
    val firstLineInset =
        with(LocalDensity.current) { ((DismissTarget - style.lineHeight.toDp()) / 2).coerceAtLeast(0.dp) }
    val bulleted = shown.size > 1
    Row(
        Modifier.fillMaxWidth().padding(horizontal = contentInset).testTag("gomode-voice-issues"),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(top = firstLineInset, bottom = 8.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (bulleted) collectionInfo = CollectionInfo(rowCount = shown.size, columnCount = 1)
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (issue in shown) {
                key(issue.key) {
                    Row(Modifier.testTag("gomode-voice-issue")) {
                        if (bulleted) {
                            Text(
                                "•",
                                style = style,
                                color = MaterialTheme.colorScheme.error,
                                modifier =
                                    Modifier
                                        .padding(end = 8.dp)
                                        .semantics { hideFromAccessibility() }
                                        .testTag("gomode-voice-issue-bullet"),
                            )
                        }
                        val cut = cutVoiceIssueMessage(issue.message)
                        // TalkBack reads a cut message in full.
                        val full =
                            if (cut == issue.message) {
                                Modifier
                            } else {
                                Modifier.semantics { contentDescription = issue.message }
                            }
                        Text(
                            cut,
                            style = style,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f).then(full).testTag("gomode-voice-issue-message"),
                        )
                    }
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("gomode-voice-issues-dismiss")) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss errors")
        }
    }
}

private fun cutVoiceIssueMessage(message: String): String =
    if (message.length <= MAX_VOICE_ISSUE_CHARS) message else "${message.take(MAX_VOICE_ISSUE_CHARS - 1).trimEnd()}…"
