// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FixModeTest {
    private fun mode(text: String, romanized: Boolean = false) = FixMode.of(text, romanized)

    @Test fun `an English sentence is proofread`() {
        assertEquals(FixMode.PROOFREAD, mode("i dont think we can make it friday sorry"))
        assertEquals(FixMode.PROOFREAD, mode("Café menu: crème brûlée, naïve résumé."))
    }

    @Test fun `digits punctuation emoji and URLs do not count`() {
        assertEquals(FixMode.PROOFREAD, mode("See https://example.com/path?x=1 at 5pm 😀🚀 !!! 12:30"))
        assertEquals(FixMode.PROOFREAD, mode("meet me at 7 😊"))
        assertEquals(FixMode.PROOFREAD, mode("12345 ... 😀"))
        assertEquals(FixMode.PROOFREAD, mode(""))
    }

    @Test fun `Bangla and Hindi are rewritten`() {
        assertEquals(FixMode.REWRITE, mode("আমি কাল অফিসে যাব না"))
        assertEquals(FixMode.REWRITE, mode("मैं कल ऑफिस नहीं आऊंगा"))
        assertEquals(FixMode.REWRITE, mode("میں کل نہیں آؤں گا"))
        assertEquals(FixMode.REWRITE, mode("আমি ১২ তারিখে 😀 আসব।"))
    }

    @Test fun `Bangla with English words is rewritten`() {
        assertEquals(FixMode.REWRITE, mode("আমি কাল office এ meeting করব"))
        assertEquals(FixMode.REWRITE, mode("मैं कल meeting में आऊंगा please"))
    }

    @Test fun `an English text with one foreign word stays English`() {
        assertEquals(FixMode.PROOFREAD, mode("She said \"namaste\" (নমস্তে) when we all arrived at the old house yesterday evening"))
    }

    @Test fun `Banglish and Hinglish are rewritten`() {
        assertEquals(FixMode.REWRITE, mode("ami kal ashbo na", romanized = true))
        assertEquals(FixMode.REWRITE, mode("main kal nahi aunga yaar", romanized = true))
        // Detected romanized beats everything, even a text that is all Latin letters and emoji.
        assertEquals(FixMode.REWRITE, mode("tumi kemon acho 😊 https://example.com", romanized = true))
    }
}
