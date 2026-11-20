// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneticStandingTest {
    @Test fun onlyRealWordsAreLookedUp() {
        assertTrue(PhoneticStanding.eligible("ami"))
        assertTrue(PhoneticStanding.eligible("bhalobashi"))
        assertFalse(PhoneticStanding.eligible("ok")) // too short
        assertFalse(PhoneticStanding.eligible("tbh")) // no vowel
        assertFalse(PhoneticStanding.eligible("Ami")) // lower case only (callers lower-case first)
        assertFalse(PhoneticStanding.eligible("3ala"))
        assertFalse(PhoneticStanding.eligible("a".repeat(21)))
    }

    @Test fun weightDecidesTheStanding() {
        assertEquals(Romanized.Standing.COMMON, PhoneticStanding.of("bn", 60_000)) // আমি-like
        assertEquals(Romanized.Standing.WORD, PhoneticStanding.of("bn", 1_000))
        assertEquals(Romanized.Standing.WORD, PhoneticStanding.of("bn", 5)) // the whole dictionary counts
        assertEquals(Romanized.Standing.NONE, PhoneticStanding.of("bn", null))
        assertEquals(Romanized.Standing.NONE, PhoneticStanding.of("ur", 60_000)) // no Varnam pack
    }

    @Test fun everyVarnamLanguageHasCutOffs() {
        assertEquals(setOf("as", "bn", "gu", "hi", "kn", "ml", "mr", "ne", "or", "pa", "sa", "ta", "te"), PhoneticStanding.LANGUAGES)
    }
}
