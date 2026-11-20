// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PillFitTest {
    private fun fit(strip: Int, vararg pills: Int) = PillFit.layout(strip, pills.toList(), minSlotWidth = 60, gap = 6)

    @Test fun pillsThatFitAllShow() {
        // 2 pills of 90 + gap 6 = 186; 400 - 186 - 6 = 208 >= 3 * 60.
        assertEquals(PillFit.Layout(2, 186), fit(400, 90, 90))
    }

    @Test fun pillsAreDroppedFromTheEndUntilTheWordsKeepTheirRoom() {
        // 300 - 186 - 6 = 108 < 180: only the first pill stays.
        assertEquals(PillFit.Layout(1, 90), fit(300, 90, 90))
    }

    @Test fun onePillAlwaysStaysButNeverTakesMoreThanHalf() {
        assertEquals(PillFit.Layout(1, 100), fit(200, 140))
        assertEquals(PillFit.Layout(1, 140), fit(1000, 140))
    }

    @Test fun nothingToShow() {
        assertEquals(PillFit.Layout(0, 0), fit(400))
        assertEquals(PillFit.Layout(0, 0), fit(0, 90))
    }
}
