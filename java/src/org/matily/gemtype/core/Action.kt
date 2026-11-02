// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/** Style rules shared by the five rewrites. */
/** Which full stop each language uses; Gemini otherwise puts "।" into Telugu or Kannada. */
internal const val FULL_STOPS =
    "\"।\" in Bangla, Assamese, Hindi, Nepali, Punjabi, Odia, Sanskrit and Maithili; \"۔\" in Urdu; " +
        "\".\" in English, Marathi, Gujarati, Tamil, Telugu, Kannada, Malayalam, Sinhala, Arabic and most others"

/** Keep how the person talks: spoken forms and the familiar or polite "you" they chose. */
internal const val KEEP_REGISTER =
    "Keep the writer's spoken style and level of formality: casual spoken forms stay spoken (do not turn them into formal written forms), " +
        "and the familiar or polite \"you\" the writer chose stays the same."

private const val NATURAL =
    "Write it the way a fluent native speaker would write this kind of message (chat or email): natural, conversational where the text is casual, never stiff. " +
        "$KEEP_REGISTER " +
        "Natural is not careless: always use correct punctuation — commas, question marks, and each sentence ending with the " +
        "language's own full stop ($FULL_STOPS). " +
        "Add nothing new. Keep names, URLs, numbers, emoji and line breaks, and keep English words in text that mixes languages."

/**
 * What GemType can do to a piece of text. [prompt] is the system prompt for text in its own script;
 * [layeredTask] is the same job as one step of the romanized-text request (see [GeminiProtocol]).
 */
enum class Action(val label: String, val resultLabel: String, val prompt: String, val layeredTask: String = prompt) {
    FIX(
        "Fix", "Suggested fix",
        "Fix the grammar, spelling and punctuation, and any clearly awkward wording. " +
            "Change as little as you can: keep the writer's own words, tone and length where they are already fine. $NATURAL",
    ),
    IMPROVE(
        "Improve", "Improved",
        "Make the text clearer and more natural, at about the same length. Keep the writer's tone and intent. $NATURAL",
    ),
    SHORTEN(
        "Shorten", "Shorter",
        "Make the text much shorter while keeping every key point and the writer's tone. $NATURAL",
    ),
    FORMAL(
        "Formal", "More formal",
        "Make the text polite and professional, suited to a work email, keeping the writer's intent. $NATURAL",
    ),
    CASUAL(
        "Casual", "More casual",
        "Make the text friendly and relaxed, the way people chat with friends, keeping the writer's intent. $NATURAL",
    ),

    /** Text typed with English letters -> its own language in its native script; `{language}` is filled in by [prompt]. */
    NATIVE(
        "Native script", "In native script",
        "The text is {language} typed with English (Latin) letters, quickly and possibly with typos, missing letters, wrong spellings, " +
            "broken grammar and English words mixed in. Work out what the writer means, then write it as correct, natural {language} " +
            "in its native script, with proper spelling, grammar and punctuation (sentence ends: $FULL_STOPS). " +
            "Fix mistakes silently. $KEEP_REGISTER Do not add new content, change the meaning or tone, or translate it into another language. " +
            "Write everyday English words the way people write them in that script (meeting, office, laptop, call → the native-script spelling); keep only names, brands, URLs, emails, numbers and emoji as they are.",
    ),
    ENGLISH(
        "English", "In English",
        "The text may be in any language or a mix, in any script including English letters (romanized), with typos and short chat forms. " +
            "Work out what the writer means, reading short forms in the writer's own language (a Banglish \"tmr\" is \"your\", not \"tomorrow\"), " +
            "then write it as natural, fluent English, the way a native speaker would say it in that kind of message. " +
            "Keep the meaning and tone, add nothing, and keep names, URLs, numbers and emoji.",
        "Write what the writer means as natural, fluent English, the way a native speaker would say it in that kind of message. " +
            "Keep the meaning and tone, add nothing, and keep names, URLs, numbers and emoji.",
    );

    /** These two change the language on purpose, so the "keep the text's language" line is left out of their request. */
    val changesLanguage: Boolean get() = this == NATIVE || this == ENGLISH

    /** The prompt, with [target] (the language's English name) filled in for [NATIVE]. */
    fun prompt(target: String?): String = if (target == null) prompt else prompt.replace("{language}", target)

    companion object {
        /** The five rewrites that keep the text's language, in chip order. */
        val REWRITES = listOf(FIX, IMPROVE, SHORTEN, FORMAL, CASUAL)
    }
}
