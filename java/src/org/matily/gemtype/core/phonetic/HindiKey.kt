// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from GemType for iOS (GemTypeCore/Phonetic/HindiKey.swift).

package org.matily.gemtype.core.phonetic

/**
 * The "loose key" of a Devanagari word: a Latin skeleton of how it sounds, with the schwa and vowel
 * length discarded and retroflex/dental pairs merged, so casual spellings meet the right word
 * (ram/raam -> राम, kitab/kitaab -> किताब). tools/build_hindi_data.py (`dev_keys`) in GemType for iOS builds
 * the same keys for the shipped word list; keep the two tables identical (HindiEngineTest cross-checks them).
 */
object HindiKey {
    private val consonants: Map<Int, String> = table(
        "क" to "k", "ख" to "K", "ग" to "g", "घ" to "G", "ङ" to "n", "च" to "c", "छ" to "C", "ज" to "j",
        "झ" to "J", "ञ" to "n", "ट" to "t", "ठ" to "T", "ड" to "d", "ढ" to "D", "ण" to "n", "त" to "t",
        "थ" to "T", "द" to "d", "ध" to "D", "न" to "n", "प" to "p", "फ" to "f", "ब" to "b", "भ" to "B",
        "म" to "m", "य" to "y", "र" to "r", "ल" to "l", "ळ" to "l", "व" to "v", "श" to "S", "ष" to "S",
        "स" to "s", "ह" to "h",
    )

    private val vowels: Map<Int, String> = table(
        "अ" to "", "आ" to "", "ा" to "", "ऄ" to "",
        "इ" to "i", "ई" to "i", "ि" to "i", "ी" to "i",
        "उ" to "u", "ऊ" to "u", "ु" to "u", "ू" to "u",
        "ए" to "e", "ऐ" to "e", "े" to "e", "ै" to "e", "ऍ" to "e", "ॅ" to "e",
        "ओ" to "o", "औ" to "o", "ो" to "o", "ौ" to "o", "ऑ" to "o", "ॉ" to "o",
        "ऋ" to "ri", "ृ" to "ri", "ं" to "n", "ँ" to "n",
    )

    private fun table(vararg pairs: Pair<String, String>): Map<Int, String> {
        val d = HashMap<Int, String>()
        for ((dev, key) in pairs) d[dev.codePointAt(0)] = key
        return d
    }

    /** The key of one word (primary variant). Expects NFC text (nukta as a separate character). */
    fun key(word: String): String {
        val out = StringBuilder()
        for (scalar in word.nfc().scalars()) {
            val piece = consonants[scalar] ?: vowels[scalar] ?: continue
            for (byte in piece) if (out.isEmpty() || out.last() != byte) out.append(byte)
        }
        return out.toString()
    }
}
