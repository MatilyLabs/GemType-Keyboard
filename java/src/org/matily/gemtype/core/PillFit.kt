// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * How the offered convert pills share the suggestion strip with the words: the pills sit at the trailing
 * end and the word slots get the rest. Pure, so the rule is easy to check by hand (all sizes in pixels).
 */
object PillFit {
    /** How many pills to show (the first ones: they come best first) and how wide the pill area is. */
    data class Layout(val pills: Int, val width: Int)

    /**
     * For a strip [stripWidth] wide holding [slotCount] word slots: pills are dropped from the end until
     * each slot keeps [minSlotWidth]; one pill always stays. The area never takes more than half the strip:
     * a long language name is cut short instead. [gap] is the space between two pills and between the
     * pills and the words.
     */
    fun layout(stripWidth: Int, pillWidths: List<Int>, minSlotWidth: Int, gap: Int, slotCount: Int = 3): Layout {
        if (pillWidths.isEmpty() || stripWidth <= 0) return Layout(0, 0)
        fun width(n: Int) = pillWidths.take(n).sum() + gap * (n - 1)
        var n = pillWidths.size
        while (n > 1 && stripWidth - width(n) - gap < slotCount * minSlotWidth) n--
        return Layout(n, minOf(width(n), stripWidth / 2))
    }
}
