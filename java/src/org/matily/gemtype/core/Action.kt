// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * What GemType can do to a piece of text. The prompts mirror the other GemType
 * surfaces (browser extension, Android app) so results are consistent.
 */
enum class Action(val label: String, val resultLabel: String, val prompt: String) {
    FIX(
        "Fix", "Suggested fix",
        "Correct the grammar, spelling and punctuation of the text, and fix clearly awkward or unnatural phrasing so it reads naturally to a native speaker. " +
            "Keep the meaning, tone and length, and keep the writer's own words where they are already fine. " +
            "In text that mixes languages, keep the English words as they are. Keep the script and formatting of the text.",
    ),
    IMPROVE(
        "Improve", "Improved",
        "Rewrite the text to be clearer and better written. Keep the meaning, tone, and approximate length.",
    ),
    SHORTEN(
        "Shorten", "Shorter",
        "Rewrite the text to be significantly more concise while keeping all key information.",
    ),
    FORMAL(
        "Formal", "More formal",
        "Rewrite the text in a professional, formal tone suitable for business communication.",
    ),
    CASUAL(
        "Casual", "More casual",
        "Rewrite the text in a friendly, casual, conversational tone.",
    ),

    /** Text typed with English letters -> its own language in its native script; `{language}` is filled in by [prompt]. */
    NATIVE(
        "Native script", "In native script",
        "The text is {language} typed with English (Latin) letters, quickly and possibly with typos, missing letters, wrong spellings, " +
            "broken grammar and English words mixed in. Work out what the writer means, then write it as correct, natural {language} " +
            "in its native script, with proper spelling, grammar and punctuation (its own sentence end, such as \"।\", where the language uses one). " +
            "Fix mistakes silently. Do not add new content, change the meaning or tone, or translate it into another language. " +
            "Keep names, brands, URLs, numbers, emoji and words normally written in English as they are.",
    ),
    ENGLISH(
        "English", "In English",
        "The text may be in any language or a mix, in any script including English letters (romanized), with typos and broken grammar. " +
            "Work out what the writer means, then write it as correct, natural, fluent English with the same meaning and tone. " +
            "Do not add anything. Keep names, URLs, numbers and emoji.",
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
