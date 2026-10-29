// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingStyleTest {
    @Test fun banglaWearsIndiasFlagOnlyInIndia() {
        assertEquals(BanglaStyle.INDIA, banglaStyleFor("IN"))
        assertEquals(BanglaStyle.INDIA, banglaStyleFor("in"))
        assertEquals(BanglaStyle.BANGLADESH, banglaStyleFor("BD"))
        assertEquals(BanglaStyle.BANGLADESH, banglaStyleFor("US"))
        assertEquals(BanglaStyle.BANGLADESH, banglaStyleFor(""))
        assertEquals(BanglaStyle.BANGLADESH, banglaStyleFor(null))
    }

    @Test fun cycleHasFiveStylesWithBanglaFollowingTheCountry() {
        val bd = GreetingStyle.cycle(BanglaStyle.BANGLADESH, night = false)
        val india = GreetingStyle.cycle(BanglaStyle.INDIA, night = false)
        assertEquals(5, bd.size)
        assertEquals(5, india.size)
        assertTrue(bd[1] is GreetingStyle.Disc)
        assertTrue(india[1] is GreetingStyle.Split)
    }

    @Test fun darkThemeLiftsTheGreensAndNepalsOutline() {
        val night = GreetingStyle.cycle(BanglaStyle.BANGLADESH, night = true)
        assertEquals(0xFF2BB58A.toInt(), (night[1] as GreetingStyle.Disc).color)
        assertEquals(0xFF3FAE70.toInt(), (night[3] as GreetingStyle.Solid).color)
        assertEquals(0xFF6E94E0.toInt(), (night[4] as GreetingStyle.Solid).outlineColor)
    }
}
