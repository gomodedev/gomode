// G2TextLayout: how stock Even G2 firmware wraps text in a text container, measured with its font's metrics.
//
// The metrics are the firmware fonts' as Even Realities publishes them for Even Hub apps in
// @evenrealities/pretext 0.1.4 (MIT), whose README states they were compared with the glasses'
// rendering; resources/com/fghbuild/eveng2/evenhub-font.json is its font table, see NOTICE in this
// module. A glyph comes from the first of four fonts that has it: Latin, Cyrillic and Greek, CJK,
// then emoji. Advances and kerning are in 1/16 px. The firmware rounds each glyph's advance with its
// kerning to the next glyph to whole pixels, (advance + kerning + 8) >> 4, and gives a missing
// printable glyph 4 px. Lines are 27 px high.
//
// Wrapping follows the firmware's LVGL labels. A line feed ends a line. A line breaks after its last
// space, hyphen, or CJK character, or with none before the character that overflows. Spaces stay
// where they fall, so a space that overflows a line starts the next one; jimrandomh/faceclaw reports
// this from firmware 2.2.9.22 (read for behaviour only), where pretext drops such a space.
package com.fghbuild.eveng2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object G2TextLayout {
    /** The height of every line of text. */
    val lineHeightPx: Int get() = table.lineHeight

    // Firmware placeholder for a printable code point no font has.
    private const val MISSING_GLYPH_PX = 4
    private const val LINE_FEED = '\n'.code
    private const val SPACE = ' '.code
    private const val HYPHEN = '-'.code

    // The table also names each font.
    private val tableJson = Json { ignoreUnknownKeys = true }

    private val table: FontTable by lazy {
        val stream =
            checkNotNull(G2TextLayout::class.java.getResourceAsStream("evenhub-font.json")) {
                "evenhub-font.json is missing from the Even G2 SDK"
            }
        stream.bufferedReader().use { tableJson.decodeFromString<FontTable>(it.readText()) }
    }

    /** Reads the font table on an IO thread, so that measuring later does not. */
    suspend fun load() {
        withContext(Dispatchers.IO) { table }
    }

    /** How many lines the firmware wraps [text] into at [width] pixels; 0 for empty text. */
    fun lineCount(
        text: String,
        width: Int,
    ): Int {
        val cps = text.codePoints().toArray()
        if (cps.isEmpty()) return 0
        var lines = 1
        var lineStart = 0
        var lineWidth = 0
        var lastBreak = -1
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            if (cp == LINE_FEED) {
                i++
            } else {
                val advance = advancePx(cp, cps.getOrElse(i + 1) { 0 })
                if (lineWidth + advance <= width || i == lineStart) {
                    lineWidth += advance
                    if (cp == SPACE || cp == HYPHEN || isCjk(cp)) lastBreak = i
                    i++
                    continue
                }
                // The line continues after its last break, or at the overflowing character.
                if (cp != SPACE && lastBreak >= 0) i = lastBreak + 1
            }
            lines++
            lineStart = i
            lineWidth = 0
            lastBreak = -1
        }
        return lines
    }

    private fun advancePx(
        cp: Int,
        next: Int,
    ): Int {
        for (font in table.fonts) {
            val advance = font.advance(cp) ?: continue
            if (advance == 0) break
            val kerning = if (next > 0) font.kern?.between(cp, next) ?: 0 else 0
            return (advance + kerning + 8) shr 4
        }
        return if (cp >= SPACE) MISSING_GLYPH_PX else 0
    }

    private fun isCjk(cp: Int): Boolean = cp in 0x2E80..0x9FFF || cp in 0xF900..0xFAFF || cp in 0xAC00..0xD7AF

    @Serializable
    private class FontTable(
        @SerialName("line_height") val lineHeight: Int,
        val fonts: List<Font>,
    )

    // Glyph advances in 1/16 px by code point: listed in glyphs, or in ranges, sorted and disjoint
    // [first, last] pairs, with the default advance unless exceptions lists them. Kerning applies
    // only between glyphs of the font that has the first one.
    @Serializable
    private class Font(
        val glyphs: Map<Int, Int> = emptyMap(),
        val ranges: List<List<Int>> = emptyList(),
        @SerialName("default_adv_w") val defaultAdvance: Int = 0,
        val exceptions: Map<Int, Int> = emptyMap(),
        val kern: Kerning? = null,
    ) {
        fun advance(cp: Int): Int? {
            glyphs[cp]?.let { return it }
            val range =
                ranges.binarySearch { (first, last) ->
                    if (cp < first) {
                        1
                    } else if (cp > last) {
                        -1
                    } else {
                        0
                    }
                }
            return if (range >= 0) exceptions[cp] ?: defaultAdvance else null
        }
    }

    // Class-pair kerning: each code point has a left and a right class, numbered from 1, and values
    // holds one row of rightCount values per left class.
    @Serializable
    private class Kerning(
        @SerialName("right_cnt") val rightCount: Int,
        @SerialName("cp_to_left") val left: Map<Int, Int>,
        @SerialName("cp_to_right") val right: Map<Int, Int>,
        val values: List<Int>,
    ) {
        fun between(
            cp: Int,
            next: Int,
        ): Int? {
            val leftClass = left[cp] ?: return null
            val rightClass = right[next] ?: return null
            return values[(leftClass - 1) * rightCount + rightClass - 1]
        }
    }
}
