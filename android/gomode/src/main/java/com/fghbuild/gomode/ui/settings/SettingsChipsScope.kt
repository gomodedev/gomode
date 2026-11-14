// Wrapping filter chip setting with its label above, a leading check on the current chip, a hint, and its error.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Chips of a [SettingsChips] group. */
internal class SettingsChipsScope(
    private val enabled: Boolean,
) {
    /** A choice; the current one carries a leading check and reselecting it saves nothing. */
    @Composable
    fun Item(
        text: String,
        selected: Boolean,
        tag: String,
        enabled: Boolean = true,
        onSelect: () -> Unit,
    ) {
        FilterChip(
            selected = selected,
            onClick = { if (!selected) onSelect() },
            label = { Text(text) },
            enabled = this.enabled && enabled,
            leadingIcon =
                if (selected) {
                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            // One chip per group is checked, so it reads as a radio button rather than FilterChip's checkbox.
            modifier = Modifier.semantics { role = Role.RadioButton }.testTag(tag),
        )
    }

    /** A label that starts a line and names the chips after it. */
    @Composable
    fun Header(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
    }
}

/** Picks one of a few models from chips that wrap across the width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsChips(
    label: String,
    enabled: Boolean,
    tag: String,
    error: String?,
    errorTag: String,
    /** Guidance below the chips, styled like the web's hints and tagged "[tag]-hint". */
    hint: String? = null,
    items: @Composable SettingsChipsScope.() -> Unit,
) {
    val scope = remember(enabled) { SettingsChipsScope(enabled) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { hideFromAccessibility() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    // The label, hint, and error render outside the chips; the group's semantics carry them.
                    .semantics {
                        contentDescription = listOfNotNull(label, hint).joinToString(". ")
                        error?.let { this.error(it) }
                    }.testTag(tag),
        ) {
            scope.items()
        }
        hint?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { hideFromAccessibility() }.testTag("$tag-hint"),
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { hideFromAccessibility() }.testTag(errorTag),
            )
        }
    }
}
