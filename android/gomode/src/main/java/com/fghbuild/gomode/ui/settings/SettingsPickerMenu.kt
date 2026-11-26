// Outlined dropdown setting with its label above, a checked current item, and the error it owns.
//
// The field fits its widest item, so short choices such as speeds or voice names do not stretch across the
// column; a label wider than the column wraps.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A [SettingsPicker] choice; the current one carries a trailing check and reselecting it saves nothing. */
internal class SettingsPickerItem(
    val text: String,
    val selected: Boolean,
    val tag: String,
    val onSelect: () -> Unit,
)

// The outlined field's 16 dp start padding, plus the trailing icon's 48 dp slot and its 16 dp end padding.
private val PICKER_CHROME_WIDTH = 80.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPicker(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    menuTag: String,
    error: String?,
    errorTag: String,
    items: List<SettingsPickerItem>,
) {
    // Disabling closes the menu.
    var expanded by remember(enabled) { mutableStateOf(false) }
    val textStyle = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    val texts = items.map { it.text } + value
    val widestText =
        remember(texts, textStyle, measurer) {
            texts.maxOf { measurer.measure(it, textStyle, maxLines = 1).size.width }
        }
    val fitWidth = with(LocalDensity.current) { widestText.toDp() } + PICKER_CHROME_WIDTH
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = enabled && it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                textStyle = textStyle,
                isError = error != null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                        // Bound before filling: an exact incoming width would override the bound.
                        .widthIn(max = fitWidth)
                        .fillMaxWidth()
                        // The label and error render outside the field; its semantics carry them.
                        .semantics {
                            contentDescription = label
                            error?.let { this.error(it) }
                        }.testTag(tag),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Menus open a popup window, which does not inherit the screen's flag.
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(menuTag),
            ) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.text) },
                        onClick = {
                            expanded = false
                            if (!item.selected) item.onSelect()
                        },
                        // A trailing check marks the current item without shifting item labels.
                        trailingIcon = {
                            if (item.selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                        },
                        modifier = Modifier.semantics { selected = item.selected }.testTag(item.tag),
                    )
                }
            }
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

/** One picker's save in progress and its failure, which shows until the next successful save. */
internal class PickerSave(
    private val scope: CoroutineScope,
    private val noun: String,
) {
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun launch(write: suspend () -> Unit) {
        scope.launch {
            saving = true
            error = null
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Display failed persistence.
                error = "Unable to save $noun: ${e.message}"
            } finally {
                saving = false
            }
        }
    }
}

/** A [PickerSave] for [noun] that starts over when [keys] change. */
@Composable
internal fun rememberPickerSave(
    noun: String,
    vararg keys: Any?,
): PickerSave {
    val scope = rememberCoroutineScope()
    return remember(scope, noun, *keys) { PickerSave(scope, noun) }
}
