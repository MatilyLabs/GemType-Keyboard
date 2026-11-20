// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from GemType for iOS (GemTypeCore/Phonetic/HindiEngine.swift).

package org.matily.gemtype.core.phonetic

/**
 * Hindi phonetic typing: ITRANS-style rules tuned for casual Hinglish ("namaste" -> नमस्ते,
 * "kaise ho" -> कैसे हो) plus word-list suggestions ("ram" -> राम). See HindiConverter.kt.
 */
class HindiEngine(private val data: PhoneticData) : PhoneticEngine {
    override val language = "hi"

    /** The closest dictionary word: its frequency band and edit distance from the rule result. */
    data class Closest(val word: String, val band: Int, val distance: Int)

    // Loaded on first use, once (a missing or invalid file stays null).
    private val dictionary: HindiDictionary? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { HindiDictionary.load(data) }
    private val starters: StarterWords? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { StarterWords.load(data, "hi") }

    override fun convert(roman: String): String = HindiConverter.convert(roman)

    override fun suggestions(roman: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val dictionary = dictionary ?: return emptyList()
        val word = lastLetterRun(roman) ?: roman
        if (word.isEmpty()) return emptyList()
        // English words and names typed the English way come first ("computer" -> कंप्यूटर, "delhi" -> दिल्ली).
        val out = ArrayList(starters?.words(word) ?: emptyList())
        for (w in dictionarySuggestions(word, dictionary, limit)) if (w !in out) out.add(w)
        return out.take(limit)
    }

    /**
     * The closest dictionary word for [roman] typed in Latin letters: its frequency band (0 = the most common
     * words ... 7 = the rarest) and how many letters it differs from what the rules write. Null when no word
     * has these sounds. Used to spot Hindi typed with English letters (Romanized).
     */
    fun closestWord(roman: String): Closest? {
        val dictionary = dictionary ?: return null
        val target = HindiConverter.convert(roman)
        val key = HindiKey.key(target)
        if (key.isEmpty()) return null
        val scalars = target.scalars()
        var best: Closest? = null
        for (id in dictionary.exact(key).take(60)) {
            val word = dictionary.word(id)
            val d = EditDistance.of(scalars, word.scalars())
            val band = dictionary.band(id)
            if (band * 2 + d * 2 < (best?.let { it.band * 2 + it.distance * 2 } ?: Int.MAX_VALUE)) best = Closest(word, band, d)
        }
        return best
    }

    private class Scored(val cost: Int, val word: String, val id: Int)

    private val byCostThenId = compareBy<Scored>({ it.cost }, { it.id })

    private fun dictionarySuggestions(word: String, dictionary: HindiDictionary, limit: Int): List<String> {
        val target = HindiConverter.convert(word)
        val key = HindiKey.key(target)
        if (key.isEmpty()) return emptyList()
        val targetScalars = target.scalars()

        // Frequency first (bands 0...7), each spelling difference from what the rules produce costs
        // about one band. Words that merely start with the typed letters come after exact matches.
        // Null = too far from what was typed to offer (keeps names from turning into unrelated words:
        // the rarest band is only accepted when it is within one edit of the rule result).
        fun cost(id: Int, extra: Int): Scored? {
            val w = dictionary.word(id)
            val d = EditDistance.of(targetScalars, w.scalars())
            val band = dictionary.band(id)
            if (d > 4 || (band >= 7 && d > 1)) return null
            return Scored(band * 2 + d * 2 + extra, w, id)
        }
        val scored = dictionary.exact(key).take(60).mapNotNull { cost(it, extra = 0) }.sortedWith(byCostThenId)
        val result = scored.mapTo(ArrayList()) { it.word }
        if (result.size < limit && key.length >= 3) {
            val more = dictionary.completions(key, maxKeys = 300).filter { dictionary.band(it) <= 4 }.take(200)
                .mapNotNull { cost(it, extra = 8) }.sortedWith(byCostThenId)
            for (m in more) if (m.word !in result) result.add(m.word)
        }
        return result.take(limit)
    }

    /**
     * The last run of letters in [text] (Swift: `split(whereSeparator: { !$0.isLetter }).last`), or null when
     * there is none. A character is a letter when its first code point is alphabetic; combining marks and
     * joiners stay with the character before them.
     */
    private fun lastLetterRun(text: String): String? {
        var last: String? = null
        val run = StringBuilder()
        for (cp in text.scalars()) {
            val mark = continuesCharacter(cp)
            // A mark goes with the character before it: kept after a letter, dropped after a separator.
            if ((mark && run.isNotEmpty()) || (!mark && Character.isAlphabetic(cp))) {
                run.appendCodePoint(cp)
            } else if (!mark) {
                if (run.isNotEmpty()) last = run.toString()
                run.setLength(0)
            }
        }
        if (run.isNotEmpty()) last = run.toString()
        return last
    }

    private fun continuesCharacter(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> cp == 0x200C || cp == 0x200D || cp in 0xFE00..0xFE0F
    }
}
