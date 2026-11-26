// Unit tests for TxTextPage layout in Halo's circle: vertical centring, whether one page holds a text, and scroll lines.
package com.fghbuild.halo.msg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

private const val LONG_TEXT =
    "Three items are open. The plumber comes on Tuesday to move the sink drain. The electrician still has to " +
        "quote the under-cabinet lights. The countertop template is booked for the following Monday morning."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TxTextPageTest {
    private val circle = CircularTextLayout(HaloDisplay.SIZE_PX, HaloDisplay.SIZE_PX, fontSizePx = 24)

    @Test
    fun aShortPageSitsInTheMiddleOfTheCircle() {
        for (text in listOf("Hi", "Hello\nGo Mode", "one\ntwo\nthree")) {
            val page = checkNotNull(TxTextPage(circle, text).rasterizeNextPage()) { "no page for \"$text\"" }
            val tops = lineTops(page)
            assertEquals(text, text.lines().size, tops.size)
            val lineHeight = page.rasterizedSprites.first().height
            val middle = (tops.first() + tops.last() + lineHeight) / 2.0
            assertTrue("$text spans ${tops.first()} to ${tops.last() + lineHeight}", abs(middle - 128) <= 1)
        }
    }

    @Test
    fun aFullPageFillsTheCircleAndLeavesTheRestForTheNext() {
        val text = LONG_TEXT
        val pager = TxTextPage(circle, text)
        val first = checkNotNull(pager.measureNextPage()) { "no first page" }
        assertTrue(pager.hasMoreText)
        assertEquals(8, first.lineTexts.size)
        assertEquals(text, (first.lineTexts + pager.remainingText()).joinToString(" "))
        assertFalse(circle.fitsOnePage(text))
        assertTrue(circle.fitsOnePage(first.lineTexts.joinToString(" ")))
        assertTrue(circle.fitsOnePage(" "))
    }

    @Test
    fun aPageOfAnyRunOfLinesShowsEachOnOneRow() {
        val lines = circle.lines("$LONG_TEXT\n\n  Done.  \n$LONG_TEXT")
        assertEquals("$LONG_TEXT Done. $LONG_TEXT", lines.joinToString(" "))
        assertTrue(lines.none { it.isBlank() || it != it.trim() })
        // Five lines hold the most text at this size; every window of them keeps one line per row.
        for (start in 0..lines.size - 5) {
            val window = lines.subList(start, start + 5)
            val page = checkNotNull(TxTextPage(circle, window.joinToString("\n")).measureNextPage())
            assertEquals(window, page.lineTexts)
        }
        // A word wider than every row breaks between characters.
        val word = "x".repeat(200)
        assertEquals(word, circle.lines(word).joinToString(""))
        assertEquals(emptyList<String>(), circle.lines(" \n "))
    }

    // The y of each line's sprite in the page header that TextPageData.pack writes.
    private fun lineTops(page: TextPageData): List<Int> {
        val header = page.pack()
        return List(header[5].toInt()) { index ->
            val offset = 6 + index * 4 + 2
            ((header[offset].toInt() and 0xFF) shl 8) or (header[offset + 1].toInt() and 0xFF)
        }
    }
}
