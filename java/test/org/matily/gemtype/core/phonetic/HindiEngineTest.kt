// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from GemType for iOS (GemTypeCoreTests/PhoneticHindiTests.swift).

package org.matily.gemtype.core.phonetic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Hindi: ITRANS-style rules for casual Hinglish, plus the word-list suggestions. */
class HindiEngineTest {
    private val data = PhoneticData.Folder(File("assets/gemtype/phonetic"))
    private val hi = HindiEngine(data)

    @Test
    fun casualHinglish() {
        val cases = listOf(
            "namaste" to "नमस्ते", "kaise ho" to "कैसे हो", "mera naam" to "मेरा नाम",
            "hindi" to "हिंदी", "kya haal hai" to "क्या हाल है", "pyaar" to "प्यार", "tumhara" to "तुम्हारा",
            "dhanyavad" to "धन्यवाद", "kitaab" to "किताब", "kal" to "कल", "ham" to "हम", "kaam" to "काम",
            "bahut" to "बहुत", "main ghar ja raha hoon" to "मैं घर जा रहा हूँ", "aap kaise hain" to "आप कैसे हैं",
            "kahaan" to "कहाँ", "karoon" to "करूँ", "sangeet" to "संगीत", "ganga" to "गंगा", "angrez" to "अंग्रेज़",
            "dost" to "दोस्त", "mantri" to "मंत्री", "bachcha" to "बच्चा", "accha" to "अच्छा", "kaun" to "कौन",
            "pani" to "पानी", "khana" to "खाना", "gyaan" to "ज्ञान", "kshama" to "क्षमा",
            "zindagi" to "ज़िंदगी", "fir milenge" to "फिर मिलेंगे", "Namaste" to "नमस्ते", "Mera Naam" to "मेरा नाम",
        )
        for ((roman, dev) in cases) assertEquals(roman, dev, hi.convert(roman))
    }

    @Test
    fun schwaAtWordEndAndLongFinalVowels() {
        assertEquals("common word", "राम", hi.convert("ram"))
        assertEquals("सब", hi.convert("sab"))
        assertEquals("ज़ुबान", hi.convert("zubaan"))
        assertEquals("शोर", hi.convert("shor"))
        assertEquals("no final a: inherent vowel, nothing added", "कर", hi.convert("kar"))
        assertEquals("final a is long", "करा", hi.convert("kara"))
        assertEquals("की", hi.convert("ki"))
        assertEquals("तेरा", hi.convert("tera"))
    }

    @Test
    fun strictITRANS() {
        val cases = listOf(
            "namaskAra" to "नमस्कार", "bhAratIya" to "भारतीय", "kaThina" to "कठिन", "kaTina" to "कटिन",
            "aSa" to "अस", "haM" to "हं", "duHkha" to "दुःख", "aShTa" to "अष्ट", "DaNDa" to "डण्ड",
        )
        for ((roman, dev) in cases) assertEquals(roman, dev, hi.convert(roman))
    }

    @Test
    fun punctuationAndSpacesPassThrough() {
        assertEquals("कैसे हो?", hi.convert("kaise ho?"))
        assertEquals("नहीं, 2 बजे", hi.convert("nahi, 2 baje"))
        assertEquals("", hi.convert(""))
        assertEquals("😀", hi.convert("😀"))
        for (s in listOf("a", "x", "kh", "zzz", "Q", "aaaa")) assertFalse(s, hi.convert(s).isEmpty())
    }

    @Test
    fun suggestions() {
        fun first(roman: String): String? = hi.suggestions(roman, 5).firstOrNull()
        assertEquals("राम", first("ram"))
        assertEquals("नमस्ते", first("namaste"))
        assertEquals("किताब", first("kitab"))
        assertEquals("किताब", first("kitaab"))
        assertEquals("हिंदी", first("hindi"))
        assertEquals("कैसे", first("kaise"))
        assertEquals("मेरा", first("mera"))
        assertEquals("बहुत", first("bahut"))
        assertEquals("पानी", first("pani"))
        assertEquals("हो", first("ho"))
        assertEquals("शांति", first("shanti"))
        assertEquals("ड़ typed as d", "लड़की", first("ladki"))
        assertEquals("कल", first("kal"))
        assertEquals("phrases use their last word", "हो", first("kaise ho"))
        assertTrue("ड़ typed as r", hi.suggestions("bara", 5).contains("बड़ा"))
        assertTrue("completion of a partial word", hi.suggestions("namas", 5).contains("नमस्कार"))
        assertEquals(2, hi.suggestions("hindi", 2).size)
        assertEquals(emptyList<String>(), hi.suggestions("", 5))
        assertEquals(emptyList<String>(), hi.suggestions("kal", 0))
        assertEquals(emptyList<String>(), hi.suggestions("123", 5))
    }

    @Test
    fun closestWord() {
        // Not in the Swift tests; checks the semantics Romanized relies on (band <= max, distance <= 1).
        val ram = hi.closestWord("ram")
        assertNotNull(ram)
        assertEquals("राम", ram!!.word)
        assertEquals(0, ram.distance)
        assertTrue(ram.band in 0..7)
        val kitab = hi.closestWord("kitab")
        assertNotNull(kitab)
        assertEquals("किताब", kitab!!.word)
        assertTrue(kitab.distance <= 1)
        assertNull("no sounds, no word", hi.closestWord("123"))
        assertNull(hi.closestWord(""))
    }

    @Test
    fun engineRegistered() {
        assertTrue(PhoneticEngines(data).engine("hi") is HindiEngine)
        assertEquals("hi", hi.language)
    }

    @Test
    fun missingDataGivesNoSuggestions() {
        val empty = HindiEngine(PhoneticData.Folder(File("does-not-exist")))
        assertNull(HindiDictionary.load(PhoneticData.Folder(File("does-not-exist"))))
        assertEquals(emptyList<String>(), empty.suggestions("ram", 5))
        assertNull(empty.closestWord("ram"))
        assertEquals("राम", empty.convert("ram"))
    }

    @Test
    fun keyMatchesBuildScript() {
        // The keys in hindi-words.bin come from tools/build_hindi_data.py. Re-derive them in Kotlin for a
        // spread of words and check the word is filed under that key: guards the two tables drifting.
        val dictionary = HindiDictionary.load(data)
        assertNotNull(dictionary)
        dictionary!!
        assertTrue(dictionary.wordCount > 150_000)
        var checked = 0
        var id = 0
        while (id < dictionary.wordCount) {
            val w = dictionary.word(id)
            val key = HindiKey.key(w)
            if (key.isNotEmpty()) {
                val ids = dictionary.exact(key)
                // ड़/ढ़ words are also filed under an r-variant, but the primary key must always be there.
                if (!ids.contains(id)) fail("$w key $key does not list it")
                checked += 1
            }
            id += 37
        }
        assertTrue(checked > 5000)
    }

    @Test
    fun hindiKeyExamples() {
        assertEquals(HindiKey.key("रम"), HindiKey.key("राम"))
        assertEquals("kitb", HindiKey.key("किताब"))
        assertEquals("kese", HindiKey.key("कैसे"))
        assertEquals(HindiKey.key("हिन्दी"), HindiKey.key("हिंदी"))
        assertEquals("ldki", HindiKey.key("लड़की"))
    }
}
