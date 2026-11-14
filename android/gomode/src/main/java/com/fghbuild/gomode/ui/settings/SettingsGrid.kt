// Lays settings fields in equal columns at least 256 dp wide, like the web's auto-fill grid; narrow screens stack them.
package com.fghbuild.gomode.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

private val MIN_COLUMN_WIDTH = 256.dp
private val COLUMN_GAP = 32.dp
private val ROW_GAP = 12.dp

/** Marks a [SettingsGrid] child, such as a heading or a status line, that takes a row of its own. */
internal fun Modifier.fullSpan(): Modifier = then(FullSpan)

private object FullSpan : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = FullSpan
}

/**
 * Places children left to right in as many equal columns as fit, each at least 256 dp wide, wrapping into rows
 * whose children align at the top. Every row shares the column tracks, so fields line up from row to row. A
 * [fullSpan] child starts its own row at the full width. One column stacks children like a Column.
 */
@Composable
internal fun SettingsGrid(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val columnGap = COLUMN_GAP.roundToPx()
        val rowGap = ROW_GAP.roundToPx()
        val columns = maxOf(1, (width + columnGap) / (MIN_COLUMN_WIDTH.roundToPx() + columnGap))
        val columnWidth = (width - columnGap * (columns - 1)) / columns
        val placeables =
            measurables.map {
                val full = it.parentData === FullSpan
                it.measure(Constraints(maxWidth = if (full) width else columnWidth)) to full
            }
        // Each row: its children with their column, and its height.
        val rows = mutableListOf<Pair<List<Pair<Int, Placeable>>, Int>>()
        var row = mutableListOf<Pair<Int, Placeable>>()

        fun endRow() {
            if (row.isNotEmpty()) rows += row to row.maxOf { it.second.height }
            row = mutableListOf()
        }
        for ((placeable, full) in placeables) {
            if (full || row.size == columns) endRow()
            row += row.size to placeable
            if (full) endRow()
        }
        endRow()
        val height = rows.sumOf { it.second } + rowGap * maxOf(0, rows.size - 1)
        layout(width, height) {
            var y = 0
            for ((cells, rowHeight) in rows) {
                for ((column, placeable) in cells) placeable.place(column * (columnWidth + columnGap), y)
                y += rowHeight + rowGap
            }
        }
    }
}
