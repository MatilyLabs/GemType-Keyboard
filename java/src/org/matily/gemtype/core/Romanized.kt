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
    data class Match(val language: String, val score: Int)

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
    /** Most languages the user can choose to convert to. */
    const val MAX_TARGETS = 3
    private const val MIN_LIST_WORDS = 2
    /** With this many list words, a fifth of the Latin-letter words being not English is enough ("ami kal ashbo but I have a meeting"). */
    private const val SURE_LIST_WORDS = 3
    private const val SURE_SHARE_PERCENT = 20
    /** With [MIN_LIST_WORDS], a third of the Latin-letter words must be not English (the rest is English mixed in). */
    private const val ROMANIZED_SHARE_PERCENT = 33
    private const val FALLBACK_MIN_WORDS = 3
    private const val FALLBACK_SHARE_PERCENT = 60
    /** Words found only by the [Recognizer] count when this share of the Latin-letter words is not English ... */
    private const val WIDER_SHARE_PERCENT = 50
    /** ... and, without any list word, when there are this many of them. */
    private const val WIDER_ALONE_WORDS = 3
    /** A sentence of at least this many words, all valid English, ends a carried offer. */
    private const val ENGLISH_SENTENCE_WORDS = 4
    private const val SENTENCE_END = ".!?\n\r।॥؟۔"
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}'’]+")

    /** The sentence being typed: the last [TAIL_CHARS] before the cursor, back to the previous sentence end or line break. */
    fun currentSentence(textBeforeCursor: CharSequence): String {
        val tail = textBeforeCursor.takeLast(TAIL_CHARS).trimEnd { it.isWhitespace() || it in SENTENCE_END }
        return tail.substring(tail.indexOfLast { it in SENTENCE_END } + 1).trimStart()
    }

    /** The recent text: the last [TAIL_CHARS] before the cursor, all sentences. */
    fun recentText(textBeforeCursor: CharSequence): String = textBeforeCursor.takeLast(TAIL_CHARS).toString()

    /** Lower-case words (letters, apostrophes, and digits inside words such as the Arabizi "3ala"). Bare numbers are not words. */
    fun words(text: String): List<String> =
        WORD.findAll(text).map { it.value.replace('’', '\'').trim('\'').lowercase() }
            .filter { word -> word.any { it.isLetter() } }.toList()

    /** One word per list entry, whitespace separated; `#` starts a comment line. */
    fun parseList(text: String): Set<String> =
        text.lineSequence().filterNot { it.trimStart().startsWith("#") }
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** What a dictionary says about a word typed in Latin letters. */
    enum class Standing {
        /** Not a word of the language. */
        NONE,
        /** A word of the language (often a noun or a name, which other languages borrow). */
        WORD,
        /** One of its most common words (pronouns, verbs, particles): shows the language like a list word. */
        COMMON,
    }

    /** What a word (lower case, Latin letters) is in a language, whatever the list says. */
    fun interface Recognizer {
        fun standing(language: String, word: String): Standing
    }

    /**
     * Which of [languages] [text] seems to be written in, best first.
     *
     * People mix English into romanized text ("ami office e jacchi, meeting ache"), so English words
     * do not hide a language that is clearly there. A language with a list ([lists] returns it) matches when
     * - 3 or more words are in its list, and at least a fifth of the Latin-letter words are not valid
     *   English (list words and chat spellings like "tmk" or "korile" both count), or
     * - 2 list words, and at least a third of the Latin-letter words are not valid English, or
     * - 1 list word, when the text is mostly not English (at least 3 words, 60% of them not valid
     *   English): chat spellings are endless, so one known word anchors the language.
     * A list word that is also valid English ([isEnglish]; null means "unknown", so no word is dropped)
     * counts only next to a list word that is not.
     * A language without a list matches only when it is the user's only one, on that same "mostly not
     * English" test. The "not English" shares need [isEnglish]; without it only list words count.
     * A match scoring half the best one or less is dropped (a few words shared with a much better match,
     * "kal", "na" in Bangla text); equal scores (Hindi and Urdu) both stay, in the order of [languages].
     * [recognizer] says what a word is in a language beyond its list (the phonetic dictionaries), so a
     * sentence need not use the few hundred listed words: the dictionary's most common words count like
     * list words, its other words only in text that is at least half not English.
     */
    fun detect(
        text: String,
        languages: Collection<String>,
        lists: (String) -> Set<String>?,
        isEnglish: ((String) -> Boolean)?,
        recognizer: Recognizer? = null,
    ): List<Match> {
        val words = words(text)
        if (words.isEmpty()) return emptyList()
        val english = HashMap<String, Boolean>()
        fun notEnglish(word: String) = isEnglish == null || !english.getOrPut(word) { isEnglish(word) }
        val matches = ArrayList<Match>()
        val distinct = languages.distinct()
        val latin = words.filter { w -> w.all { it.code < 128 } }
        val foreign = if (isEnglish == null) 0 else latin.count { notEnglish(it) }
        fun mostlyForeign(count: Int) = isEnglish != null && words.size >= FALLBACK_MIN_WORDS &&
            count * 100 >= FALLBACK_SHARE_PERCENT * words.size
        for (language in distinct) {
            val list = lists(language)
            if (list != null) {
                // List words the dictionary also knows count once a surely non-English list word shows the language.
                var sure = 0
                var maybe = 0
                var wider = 0 // dictionary words that are not in the list (and not English)
                for (w in words) {
                    val standing = when {
                        w in list -> Standing.COMMON
                        isEnglish == null || recognizer == null -> Standing.NONE
                        else -> recognizer.standing(language, w)
                    }
                    when (standing) {
                        Standing.COMMON -> if (notEnglish(w)) sure++ else maybe++
                        Standing.WORD -> if (notEnglish(w)) wider++
                        Standing.NONE -> {}
                    }
                }
                val anchored = sure >= 1
                val score = if (anchored) sure + maybe else 0
                val romanized = if (isEnglish == null) score else foreign + (if (anchored) maybe else 0)
                val enough = (score >= SURE_LIST_WORDS && romanized * 100 >= SURE_SHARE_PERCENT * latin.size) ||
                    (score >= MIN_LIST_WORDS && romanized * 100 >= ROMANIZED_SHARE_PERCENT * latin.size) ||
                    (score >= 1 && mostlyForeign(romanized))
                // Dictionary words are mostly nouns and names, which English sentences borrow ("paneer tikka was so
                // good"), so they count only in text that is at least half not English, and only next to a list
                // word or three at a time.
                val half = wider > 0 && foreign * 100 >= WIDER_SHARE_PERCENT * latin.size
                // Two dictionary words also vouch for a common word that English shares ("rasta jam ache").
                val vouched = if (anchored || wider >= 2) sure + maybe else 0
                val enoughWider = half && ((vouched >= 1 && vouched + wider >= MIN_LIST_WORDS) || wider >= WIDER_ALONE_WORDS)
                // A list word shows the language better than a dictionary word (Marathi text has Hindi words too).
                if (enough || enoughWider) matches += Match(language, score * 2 + wider)
            } else if (distinct.size == 1 && mostlyForeign(foreign)) {
                matches += Match(language, foreign)
            }
        }
        // A language that matched only through a few words shared with a much better match is not offered ("kal", "na" in Bangla text).
        val best = matches.maxOfOrNull { it.score } ?: 0
        return matches.filter { it.score * 2 > best }.sortedByDescending { it.score } // stable: ties keep [languages] order
    }

    /** What an earlier sentence matched, carried into the next one. */
    data class Carried(val sentence: String, val matches: List<Match>)

    /**
     * [detect] on the sentence being typed, else on the recent text, else what the previous sentence
     * matched. A convert replaces the whole field, so an English sentence typed after a romanized one
     * ("ami kal ashbo. See you") keeps the offer, until a whole English sentence ("I will come tomorrow").
     */
    fun detectRecent(
        textBeforeCursor: CharSequence,
        previous: Carried?,
        languages: Collection<String>,
        lists: (String) -> Set<String>?,
        isEnglish: ((String) -> Boolean)?,
        recognizer: Recognizer? = null,
    ): List<Match> {
        val sentence = currentSentence(textBeforeCursor)
        val found = detect(sentence, languages, lists, isEnglish, recognizer)
        if (found.isNotEmpty()) return found
        val recent = recentText(textBeforeCursor)
        if (recent.length > sentence.length + 1) {
            val wider = detect(recent, languages, lists, isEnglish, recognizer)
            if (wider.isNotEmpty()) return wider
        }
        // Only into a new sentence: the same one edited (words deleted) decides by itself.
        if (previous == null || sentence.isEmpty() ||
            sentence.startsWith(previous.sentence) || previous.sentence.startsWith(sentence)
        ) return emptyList()
        val words = words(sentence)
        val english = isEnglish != null && words.size >= ENGLISH_SENTENCE_WORDS &&
            words.all { w -> w.all { it.code < 128 } && isEnglish(w) }
        return if (english) emptyList() else previous.matches.filter { it.language in languages }
    }

    /** The saved "Convert to" value as stored by [formatTargets]; null when the user never chose (an empty string is a choice: none). */
    fun parseTargets(saved: String?): List<String>? =
        saved?.split(',')?.map { it.trim() }?.filter { it in NON_LATIN }?.distinct()?.take(MAX_TARGETS)

    fun formatTargets(languages: Collection<String>): String = languages.distinct().take(MAX_TARGETS).joinToString(",")

    /** The languages to convert to: what the user saved, else the first [MAX_TARGETS] of the [enabled] non-Latin keyboard languages. */
    fun targets(saved: String?, enabled: List<String>): List<String> =
        parseTargets(saved) ?: enabled.filter { it in NON_LATIN }.distinct().take(MAX_TARGETS)

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
