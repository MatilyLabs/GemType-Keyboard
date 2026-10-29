// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import java.util.Locale

/**
 * Spots text that is really another language typed with English letters ("ami kal ashbo na").
 * Entirely offline: a small bundled list of common romanized words per language, minus anything
 * that is also a valid English word. No android.* imports, so it is unit-tested on the JVM.
 */
object Romanized {
    /** A language the text may be written in, and how many of its words were found. */
    class Match(val language: String, val score: Int)

    /** Languages written in a non-Latin script (every such language this keyboard types). */
    val NON_LATIN = setOf(
        "ar", "as", "bn", "bho", "gom", "gu", "hi", "hoc", "kn", "ks", "mai", "ml", "mni", "mnw", "mr", "my",
        "ne", "or", "pa", "rhg", "sa", "sat", "saz", "sd", "si", "syl", "ta", "tcy", "te", "ur",
    )

    /** Languages with a bundled word list (assets/gemtype/romanized/<listFile>); the rest use the fallback rule. */
    val LISTS = setOf(
        "ar", "as", "bn", "bho", "gu", "hi", "kn", "mai", "ml", "mr", "my", "ne", "or", "pa", "sa", "si", "ta", "te", "ur",
    )

    /** Urdu is romanized the way Hindi is, so the two share one list. */
    fun listFile(language: String): String = (if (language == "ur") "hi" else language) + ".txt"

    const val TAIL_CHARS = 300
    const val MAX_PILLS = 2
    private const val MIN_LIST_WORDS = 2
    private const val LIST_SHARE_PERCENT = 40
    private const val FALLBACK_MIN_WORDS = 3
    private const val FALLBACK_SHARE_PERCENT = 60
    private const val SENTENCE_END = ".!?\n\r।॥؟۔"
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}'’]+")

    /** The sentence being typed: the last [TAIL_CHARS] before the cursor, back to the previous sentence end or line break. */
    fun currentSentence(textBeforeCursor: CharSequence): String {
        val tail = textBeforeCursor.takeLast(TAIL_CHARS).trimEnd { it.isWhitespace() || it in SENTENCE_END }
        return tail.substring(tail.indexOfLast { it in SENTENCE_END } + 1).trimStart()
    }

    /** Lower-case words (letters, apostrophes, and digits inside words such as the Arabizi "3ala"). Bare numbers are not words. */
    fun words(text: String): List<String> =
        WORD.findAll(text).map { it.value.replace('’', '\'').trim('\'').lowercase() }
            .filter { word -> word.any { it.isLetter() } }.toList()

    /** One word per list entry, whitespace separated; `#` starts a comment line. */
    fun parseList(text: String): Set<String> =
        text.lineSequence().filterNot { it.trimStart().startsWith("#") }
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Which of [languages] [text] seems to be written in, best first.
     *
     * A language with a list ([lists] returns it) matches when at least 2 of the words, and at least
     * 40% of all words, are in the list and are not valid English ([isEnglish]; null means "unknown",
     * so no word is dropped). It also matches on a single listed word when the text is mostly not
     * English (at least 3 words, 60% of them Latin-letter words that are not valid English): chat
     * spellings like "tmk" or "korile" are endless, so one known word anchors the language. A
     * language without a list matches only when it is the user's only one, on that same "mostly not
     * English" test. Both "mostly not English" rules need [isEnglish].
     */
    fun detect(
        text: String,
        languages: Collection<String>,
        lists: (String) -> Set<String>?,
        isEnglish: ((String) -> Boolean)?,
    ): List<Match> {
        val words = words(text)
        if (words.isEmpty()) return emptyList()
        val english = HashMap<String, Boolean>()
        fun notEnglish(word: String) = isEnglish == null || !english.getOrPut(word) { isEnglish(word) }
        val matches = ArrayList<Match>()
        val distinct = languages.distinct()
        val foreign = if (isEnglish == null) 0 else words.count { w -> w.all { it.code < 128 } && notEnglish(w) }
        val mostlyForeign = isEnglish != null && words.size >= FALLBACK_MIN_WORDS &&
            foreign * 100 >= FALLBACK_SHARE_PERCENT * words.size
        for (language in distinct) {
            val list = lists(language)
            if (list != null) {
                val score = words.count { it in list && notEnglish(it) }
                val listed = score >= MIN_LIST_WORDS && score * 100 >= LIST_SHARE_PERCENT * words.size
                if (listed || (score >= 1 && mostlyForeign)) matches += Match(language, score)
            } else if (distinct.size == 1 && mostlyForeign) {
                matches += Match(language, foreign)
            }
        }
        return matches.sortedByDescending { it.score }
    }

    /** The pills to show: the language picked last time first (if it matched), then by score; at most [MAX_PILLS]. */
    fun order(matches: List<Match>, lastPicked: String?): List<Match> =
        matches.sortedByDescending { it.language == lastPicked }.take(MAX_PILLS)

    /** The language's name in English, for the prompt ("Bangla"). */
    fun englishName(language: String): String = Locale(language).getDisplayLanguage(Locale.ENGLISH)

    /** The language's name in its own script, for the pill and chip ("বাংলা"). */
    fun nativeName(language: String): String {
        val locale = Locale(language)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
