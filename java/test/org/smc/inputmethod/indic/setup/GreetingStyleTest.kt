// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingStyleTest {
    private val texts = arrayOf("Welcome", "স্বাগতম", "स्वागत है", "خوش آمدید", "स्वागतम्")

    @Test fun firstBangladeshOrIndiaHintDecidesTheBanglaFlag() {
        assertEquals(BanglaStyle.BANGLADESH, banglaStyleFor(listOf("BD", "", "")))
        assertEquals(BanglaStyle.INDIA, banglaStyleFor(listOf("in", null, null)))
        // Phone in "English (US)" but a Bangladeshi SIM.
        assertEquals(BanglaStyle.BANGLADESH, banglaStyleFor(listOf("US", "bd", "us")))
        assertEquals(BanglaStyle.INDIA, banglaStyleFor(listOf("GB", "", "in")))
        assertEquals(BanglaStyle.BOTH, banglaStyleFor(listOf("US", "us", "us")))
        assertEquals(BanglaStyle.BOTH, banglaStyleFor(listOf("", null, null)))
    }

    @Test fun banglaWearsOneFlagInBangladeshOrIndia() {
        val bd = GreetingStyle.cycle(texts, BanglaStyle.BANGLADESH, night = false)
        val india = GreetingStyle.cycle(texts, BanglaStyle.INDIA, night = false)
        assertEquals(texts.toList(), bd.map { it.first })
        assertEquals(texts.toList(), india.map { it.first })
        assertTrue(bd[1].second is GreetingStyle.Disc)
        assertTrue(india[1].second is GreetingStyle.Split)
    }

    @Test fun banglaWearsBothFlagsElsewhere() {
        val both = GreetingStyle.cycle(texts, BanglaStyle.BOTH, night = false)
        assertEquals(6, both.size)
        val banglas = both.filter { it.first == "স্বাগতম" }.map { it.second }
        assertEquals(2, banglas.size)
        assertTrue(banglas[0] is GreetingStyle.Disc)
        assertTrue(banglas[1] is GreetingStyle.Split)
        // The two India-coloured words never follow each other.
        both.zipWithNext().forEach { (a, b) ->
            assertTrue(!(a.second is GreetingStyle.Split && b.second is GreetingStyle.Split))
        }
    }

    @Test fun darkThemeLiftsTheGreensAndNepalsOutline() {
        val night = GreetingStyle.cycle(texts, BanglaStyle.BANGLADESH, night = true).map { it.second }
        assertEquals(0xFF2BB58A.toInt(), (night[1] as GreetingStyle.Disc).color)
        assertEquals(0xFF3FAE70.toInt(), (night[3] as GreetingStyle.Solid).color)
        assertEquals(0xFF6E94E0.toInt(), (night[4] as GreetingStyle.Solid).outlineColor)
    }
}
