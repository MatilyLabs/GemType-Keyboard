// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from GemType for iOS (GemTypeCore/Phonetic/SoundKey.swift).

package org.matily.gemtype.core.phonetic

/**
 * A spelling-free key for Bangla words: the consonant sounds only, with the letters people swap merged
 * (ক/খ, ত/ট/থ/ঠ, দ/ড/ধ/ঢ, স/শ/ষ, ব/ভ, জ/ঝ/য, ন/ণ, র/ড়...). The roman input gets the same key, whatever its
 * case and whether it has the "h" ("ta" and "Ta" -> t, finding তা and টা; "kabo" and "khabo" -> kb, finding
 * খাবো). Vowels are dropped, except that a word starting with one starts with "a". The dictionary index
 * (avro-soundkeys.bin) was built with the same rules; keep the two in step.
 */
object SoundKey {
    // ---- Roman

    /** Two-letter roman sounds, checked first. */
    private val pairs = mapOf(
        "kh" to "k", "gh" to "g", "ch" to "c", "jh" to "j", "th" to "t", "dh" to "d", "ph" to "p", "bh" to "b",
        "sh" to "s", "rh" to "r", "ng" to "ng",
    )
    private val singles = mapOf(
        'k' to "k", 'q' to "k", 'g' to "g", 'c' to "c", 'j' to "j", 'z' to "j", 't' to "t", 'd' to "d", 'r' to "r",
        'n' to "n", 'p' to "p", 'f' to "p", 'b' to "b", 'v' to "b", 'm' to "m", 'l' to "l", 's' to "s", 'h' to "h",
        'x' to "ks",
    )

    /** The keys to look a roman Hindi word up under: its key, and without a final n (main for मैं). */
    fun hindiKeys(word: String): List<String> {
        val key = roman(word, hindi = true)
        if (key.length <= 1 || !key.endsWith("n")) return if (key.isEmpty()) emptyList() else listOf(key)
        return listOf(key, key.dropLast(1))
    }

    /**
     * The keys to look a roman word up under: its key, and for a "c" (চ in Avro) also k and s, since English
     * words typed in Bangla use it that way ("computer" -> কম্পিউটার, "facebook" -> ফেসবুক).
     */
    fun romanKeys(word: String): List<String> {
        val lower = word.lowercase()
        var spellings = listOf(lower)
        val spots = lower.indices.filter { lower[it] == 'c' && (it + 1 >= lower.length || lower[it + 1] != 'h') }
        for (i in spots.take(2)) {
            spellings = spellings.flatMap { s ->
                val k = s.toCharArray().also { it[i] = 'k' }
                val z = s.toCharArray().also { it[i] = 's' }
                listOf(s, String(k), String(z))
            }
        }
        val seen = HashSet<String>()
        return spellings.map { roman(it) }.filter { it.isNotEmpty() && seen.add(it) }
    }

    /** The key of a roman (English-letter) word. [hindi]: "w" is व (in Bangla Avro it is a vowel). */
    fun roman(word: String, hindi: Boolean = false): String {
        val letters = word.lowercase().filter { it in 'a'..'z' }
        val out = StringBuilder()
        if (letters.isNotEmpty() && letters[0] in "aeiou") out.append('a')
        var i = 0
        while (i < letters.length) {
            if (i + 1 < letters.length) {
                val pair = pairs[letters.substring(i, i + 2)]
                if (pair != null) {
                    out.append(pair)
                    i += 2
                    continue
                }
            }
            // Vowels, w and y carry no consonant.
            out.append(if (hindi && letters[i] == 'w') "b" else singles[letters[i]] ?: "")
            i += 1
        }
        return collapse(out)
    }

    // ---- Bangla

    private val consonants = mapOf(
        0x0995 to "k", 0x0996 to "k", 0x0997 to "g", 0x0998 to "g", 0x0999 to "ng", 0x099A to "c", 0x099B to "c",
        0x099C to "j", 0x099D to "j", 0x099E to "n", 0x099F to "t", 0x09A0 to "t", 0x09A1 to "d", 0x09A2 to "d",
        0x09A3 to "n", 0x09A4 to "t", 0x09A5 to "t", 0x09A6 to "d", 0x09A7 to "d", 0x09A8 to "n", 0x09AA to "p",
        0x09AB to "p", 0x09AC to "b", 0x09AD to "b", 0x09AE to "m", 0x09AF to "j", 0x09B0 to "r", 0x09B2 to "l",
        0x09B6 to "s", 0x09B7 to "s", 0x09B8 to "s", 0x09B9 to "h", 0x09CE to "t", 0x09DC to "r", 0x09DD to "r",
        0x0982 to "ng", 0x098B to "r", 0x09C3 to "r",
    )
    private const val HASANTA = 0x09CD
    private const val YA = 0x09AF
    private const val NUKTA = 0x09BC

    /** The key of a Bangla word. */
    fun bangla(word: String): String {
        val s = word.scalars()
        val out = StringBuilder()
        if (s.isNotEmpty() && s[0] in 0x0985..0x0994) out.append('a') // অ ... ঔ
        var i = 0
        while (i < s.size) {
            val c = s[i]
            // ক্ষ is said and typed as kh; জ্ঞ as g(y).
            if (i + 2 < s.size && s[i + 1] == HASANTA) {
                if (c == 0x0995 && s[i + 2] == 0x09B7) { out.append('k'); i += 3; continue }
                if (c == 0x099C && s[i + 2] == 0x099E) { out.append('g'); i += 3; continue }
            }
            if (c == YA) {
                // য-ফলা (after a hasanta) and য় (য + nukta) are vowel-like: no consonant.
                val afterHasanta = i > 0 && s[i - 1] == HASANTA
                val withNukta = i + 1 < s.size && s[i + 1] == NUKTA
                if (afterHasanta || withNukta) { i += 1; continue }
            }
            if (c == 0x09DF) { i += 1; continue } // য়
            out.append(consonants[c] ?: "")
            i += 1
        }
        return collapse(out)
    }

    // ---- Hindi (Devanagari)

    private val devanagariConsonants = mapOf(
        0x0915 to "k", 0x0916 to "k", 0x0958 to "k", 0x0959 to "k", 0x0917 to "g", 0x0918 to "g", 0x095A to "g",
        0x0919 to "ng", 0x091A to "c", 0x091B to "c", 0x091C to "j", 0x091D to "j", 0x095B to "j", 0x091E to "n",
        0x091F to "t", 0x0920 to "t", 0x0924 to "t", 0x0925 to "t", 0x0921 to "d", 0x0922 to "d", 0x095C to "d",
        0x095D to "d", 0x0926 to "d", 0x0927 to "d", 0x0923 to "n", 0x0928 to "n", 0x092A to "p", 0x092B to "p",
        0x095E to "p", 0x092C to "b", 0x092D to "b", 0x0935 to "b", 0x092E to "m", 0x0930 to "r", 0x0932 to "l",
        0x0936 to "s", 0x0937 to "s", 0x0938 to "s", 0x0939 to "h", 0x0902 to "n", 0x0901 to "n", 0x090B to "r",
        0x0943 to "r",
    )

    /**
     * The key of a Hindi (Devanagari) word, to match `roman(word, hindi = true)`: य is a vowel-like y, ं and ँ
     * are n. A final ं or ँ is left out, as people leave it out when they type (nahi for नहीं, hai for हैं).
     */
    fun devanagari(word: String): String {
        var s = word.scalars()
        while (s.isNotEmpty() && (s.last() == 0x0902 || s.last() == 0x0901)) s = s.copyOf(s.size - 1)
        val out = StringBuilder()
        if (s.isNotEmpty() && s[0] in 0x0904..0x0914) out.append('a')
        var i = 0
        while (i < s.size) {
            // ज्ञ is typed gy(a).
            if (i + 2 < s.size && s[i] == 0x091C && s[i + 1] == 0x094D && s[i + 2] == 0x091E) {
                out.append('g'); i += 3; continue
            }
            out.append(devanagariConsonants[s[i]] ?: "")
            i += 1
        }
        return collapse(out)
    }

    /**
     * [scalars] with each consonant replaced by one letter of its sound group (খ -> ক, ট/থ/ঠ -> ত, য -> জ...),
     * vowel signs kept: two spellings that differ only in which of those letters they use come out equal.
     */
    fun merged(scalars: IntArray): List<Int> =
        scalars.filter { it != NUKTA }.map { groupLetter[it] ?: it }

    private val groupLetter: Map<Int, Int> = HashMap<Int, Int>().apply {
        val groups = listOf(
            intArrayOf(0x0995, 0x0996), intArrayOf(0x0997, 0x0998), intArrayOf(0x099A, 0x099B),
            intArrayOf(0x099C, 0x099D, 0x09AF), intArrayOf(0x09A4, 0x099F, 0x09A0, 0x09A5, 0x09CE),
            intArrayOf(0x09A6, 0x09A1, 0x09A2, 0x09A7), intArrayOf(0x09B0, 0x09DC, 0x09DD),
            intArrayOf(0x09A8, 0x09A3), intArrayOf(0x09AA, 0x09AB), intArrayOf(0x09AC, 0x09AD),
            intArrayOf(0x09B8, 0x09B6, 0x09B7),
        )
        for (g in groups) for (c in g) put(c, g[0])
    }

    /** Doubled letters count once ("dhonnobad" and ধন্যবাদ). */
    private fun collapse(s: CharSequence): String {
        val out = StringBuilder()
        for (c in s) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }
}
