// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The proofreader request and its answer: Gemini lists the mistakes, the app finds and applies
 * each one itself, so only the wrong spans change. Port of the browser extension's checker
 * (background.js `checkSystemPrompt` / `CHECK_SCHEMA`, util.js `locateCorrections`). No I/O here.
 */
object CheckProtocol {
    private val SCHEMA by lazy {
        fun str() = JSONObject().put("type", "STRING")
        val item = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("original", str())
                    .put("replacement", str())
                    .put("explanation", str())
                    .put("type", str().put("enum", JSONArray(listOf("grammar", "spelling", "punctuation", "style")))),
            )
            .put("required", JSONArray(listOf("original", "replacement", "explanation", "type")))
        JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("corrections", JSONObject().put("type", "ARRAY").put("items", item)))
            .put("required", JSONArray().put("corrections"))
    }

    // Short per-language rules. Keys are lower case; Bengali is the picker's name for Bangla.
    private val BLOCKS = mapOf(
        "english" to "English: keep the author's US or UK spelling; fix its/it's, their/there/they're, your/you're, then/than.",
        "bangla" to "Bangla: follow Bangla Academy standard spelling. Do not mix sadhu and cholito verb or pronoun forms in one text (guruchandali): in cholito text a sadhu pronoun or verb is an error " +
            "(তাহারা→তারা, তাহার→তার, যাহা→যা, করিয়া→করে, গিয়াছিলাম→গিয়েছিলাম). " +
            "Fix hasanta, ya-phala and reph errors. Confusing ি/ী or ু/ূ is a spelling error. " +
            "A sentence ends with \"।\", not \".\": report every \".\" that ends a Bangla sentence; keep \"?\" and \"!\".",
        "hindi" to "Hindi: check gender and number agreement of verbs, postpositions and adjectives. " +
            "Fix matra errors, missing nukta, and chandrabindu vs anusvara (मै→मैं, है/हैं with plural subjects). " +
            "A sentence ends with \"।\", not \".\": report every \".\" that ends a Hindi sentence.",
        "urdu" to "Urdu: use Urdu punctuation (۔ ، ؟). Check gender and number agreement of verbs and adjectives.",
        "nepali" to "Nepali: a sentence ends with \"।\", not \".\". Check that verbs and adjectives agree with the subject.",
    )
    private val SPECIFIC = listOf("english", "bangla", "hindi", "urdu", "nepali")

    private fun key(language: String) = language.trim().lowercase().let { if (it == "bengali") "bangla" else it }

    private fun generic(language: String) = "$language: apply that language's own punctuation and standard spelling."

    /** The rule block for [language] (an English language name). */
    private fun block(language: String) = BLOCKS[key(language)] ?: generic(language.trim())

    private fun keyboardHint(keyboard: String): String {
        val article = if (keyboard.first().lowercaseChar() in "aeiou") "an" else "a"
        val guess = if (key(keyboard) == "english") "English" else "$keyboard, English,"
        return "The user is typing on $article $keyboard keyboard; the text is probably $guess or a mix."
    }

    /**
     * [language] is a writing language name or [Choices.AUTO_LANGUAGE]; [keyboardLanguage] is the
     * English name of the language the user is typing in (used only when [language] is auto).
     */
    fun systemPrompt(language: String, keyboardLanguage: String?): String {
        val auto = language == Choices.AUTO_LANGUAGE
        val keyboard = keyboardLanguage?.trim()?.takeIf { it.isNotEmpty() }
        val blocks = when {
            !auto -> listOf(block(language))
            // The keyboard is a good guess: English plus that language.
            keyboard != null -> listOf(block("English")) + if (key(keyboard) == "english") emptyList() else listOf(block(keyboard))
            else -> SPECIFIC.map { BLOCKS.getValue(it) }
        }
        val lang = if (auto) {
            "Detect the language of the text and check it in that language." +
                (if (keyboard != null) " " + keyboardHint(keyboard) else "") +
                " For any other language, apply that language's own punctuation and standard spelling."
        } else "The text is in ${language.trim()}."
        return listOf(
            "You are a thorough proofreader built into a phone keyboard. Find EVERY error in the text.",
            "Error categories to report:",
            "- grammar: subject-verb agreement, wrong verb tense or form, pronoun case, wrong or missing articles/prepositions, word order, and RUN-ON SENTENCES or comma splices — two sentences joined without punctuation is always an error (fix by adding the missing period or comma, e.g. \"inviting me I think\" -> \"inviting me. I think\").",
            "- spelling: misspelled words and wrong homophones (their/there, you're/your, its/it's).",
            "- punctuation: missing, extra, or misplaced punctuation; wrong spacing around punctuation (e.g. a space before a period like \"this .\" -> \"this.\"); missing capitalization at the start of EVERY sentence (check each sentence, not only the first).",
            "- style: phrasing that is clearly awkward, confusing, or unidiomatic — something a fluent writer would not write (e.g. \"a good idea how to implement this\" -> \"a good idea; how should we implement this?\"). Suggest a natural rewording.",
            "Phone typing: text is typed fast on a phone, so expect and fix lowercase \"i\" as a pronoun, missing apostrophes (dont, im, cant, wont, its/it's), doubled words, a missing capital at the start of a sentence, double spaces, and a missing space after punctuation.",
            "Do NOT flag: personal names, slang or informal tone that is written correctly, or pure taste preferences.",
            "Judge every SENTENCE as a whole, not just individual words: sentence boundaries, fragments, missing subjects or verbs, and word order all count.",
            "Always prefer the MINIMAL edit that makes the sentence grammatically correct — keep the author's original words, order, and tone; never rephrase beyond what correctness requires.",
            "Mixed text: text that mixes languages (for example Bangla or Hindi with English words) is normal. Fix each part in its own language; never translate English words and never flag a word only because it is English. " +
                "Text written in Latin letters for Bangla, Hindi or another language (Banglish, Hinglish): fix spelling and punctuation in Latin letters only; never convert the script.",
        ).plus(blocks).plus(
            listOf(
                "For each error, \"original\" must be the EXACT substring copied verbatim from the text (the shortest span containing the error, extended with surrounding words only if needed to make it unique), and \"replacement\" the corrected version of that same span.",
                "Errors must not overlap: if problems are adjacent, merge them into one correction spanning both.",
                "Write every \"explanation\" in English, under 12 words, in plain words (avoid grammar jargon where you can).",
                "If the text truly has no errors, return an empty corrections array.",
                lang,
            ),
        ).joinToString("\n")
    }

    fun requestBody(text: String, language: String, keyboardLanguage: String?, thinkingLevel: String?): JSONObject {
        val gen = JSONObject()
            .put("temperature", 0)
            .put("responseMimeType", "application/json")
            .put("responseSchema", SCHEMA)
        if (thinkingLevel != null) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "TEXT TO CHECK:\n$text")))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(language, keyboardLanguage)))))
            .put("generationConfig", gen)
    }

    /**
     * Map an HTTP status and body to the corrections for [text] (located, in text order, none
     * overlapping), or throw the matching [GeminiException]. An empty list means no mistakes.
     */
    fun parseResponse(status: Int, raw: String, text: String): List<Correction> {
        val answer = GeminiProtocol.answerText(status, raw)
        val items = runCatching { JSONObject(answer).getJSONArray("corrections") }.getOrNull()
            ?: throw GeminiException(GeminiException.Kind.EMPTY_RESPONSE)
        val found = (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val original = o.opt("original") as? String ?: return@mapNotNull null
            val replacement = o.opt("replacement") as? String ?: return@mapNotNull null
            if (original.isEmpty() || original == replacement || !text.contains(original)) return@mapNotNull null
            Correction(original, replacement, o.optString("explanation", "").trim(), Correction.Type.parse(o.optString("type")))
        }
        return locate(text, found)
    }

    /**
     * Give each correction its place in [text]: the first occurrence of its original that does not
     * overlap one already placed. Corrections that can't be placed are dropped; the rest come back in text order.
     */
    fun locate(text: String, corrections: List<Correction>): List<Correction> {
        val located = ArrayList<Correction>()
        for (c in corrections) {
            // A whole-word match first: a short original such as "i" must not land inside "invite".
            val start = find(text, c.original, located, wholeWord = true).takeIf { it >= 0 }
                ?: find(text, c.original, located, wholeWord = false)
            if (start != -1) located.add(c.copy(start = start, end = start + c.original.length))
        }
        return located.sortedBy { it.start }
    }

    private fun find(text: String, original: String, placed: List<Correction>, wholeWord: Boolean): Int {
        var from = 0
        while (true) {
            val idx = text.indexOf(original, from)
            if (idx == -1) return -1
            val end = idx + original.length
            if (placed.none { idx < it.end && end > it.start } && (!wholeWord || isWholeWord(text, original, idx))) return idx
            from = idx + 1
        }
    }

    /** [original] at [start] is not cut out of a longer word on either side. */
    internal fun isWholeWord(text: String, original: String, start: Int): Boolean {
        val end = start + original.length
        if (original.isEmpty()) return false
        if (isWordChar(original.first()) && start > 0 && isWordChar(text[start - 1])) return false
        if (isWordChar(original.last()) && end < text.length && isWordChar(text[end])) return false
        return true
    }

    /** A letter, digit or combining mark (an Indic vowel sign). */
    internal fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || Character.getType(c).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        }
}
