// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * What a phonetic (Varnam) dictionary says about a word typed in Latin letters, for [Romanized.detect]:
 * the word transliterates to a dictionary word of the language, and how common that word is.
 *
 * The language packs' dictionaries weigh each word by how often it appears in the language's corpus,
 * so the cut-off differs per language: it sits at a fixed rank of each pack's word list
 * (dictionaries-indic/languages/<code>/wordfreq.txt). The [COMMON_RANK] most frequent words count like a
 * list word; every other dictionary word counts as a word (the whole dictionary: [Romanized.detect] only
 * lets such words count in text that is mostly not English, which keeps borrowed words out).
 * Tuned on the 1,409 test chat sentences (RomanizedChatTest): a higher common rank finds a little more
 * Bangla and Hindi but starts to take English sentences ("dosa for breakfast") for them.
 */
object PhoneticStanding {
    const val COMMON_RANK = 300

    /** Weight of the word at [COMMON_RANK] in each Varnam language's pack. */
    private val COMMON_WEIGHT: Map<String, Int> = mapOf(
        "as" to 4235, "bn" to 47199, "gu" to 2716, "hi" to 19391, "kn" to 5817, "ml" to 5720, "mr" to 11757,
        "ne" to 4004, "or" to 2299, "pa" to 6658, "sa" to 861, "ta" to 17150, "te" to 26066,
    )

    /** Languages whose packs are weighed here. */
    val LANGUAGES: Set<String> get() = COMMON_WEIGHT.keys

    /**
     * Whether [word] is worth asking a dictionary about: 3 to 20 lower-case Latin letters with a vowel.
     * Shorter words and words without a vowel are too often accidents ("lol", "ok", "tbh").
     */
    fun eligible(word: String): Boolean =
        word.length in 3..20 && word.all { it in 'a'..'z' } && word.any { it in "aeiou" }

    /** The standing of a word whose best exact dictionary match weighs [weight] (null: no match). */
    fun of(language: String, weight: Int?): Romanized.Standing {
        val common = COMMON_WEIGHT[language] ?: return Romanized.Standing.NONE
        return when {
            weight == null -> Romanized.Standing.NONE
            weight >= common -> Romanized.Standing.COMMON
            else -> Romanized.Standing.WORD
        }
    }
}
