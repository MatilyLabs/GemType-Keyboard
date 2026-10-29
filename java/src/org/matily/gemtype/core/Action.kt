// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * What GemType can do to a piece of text. The prompts mirror the other GemType
 * surfaces (browser extension, Android app) so results are consistent.
 */
enum class Action(val label: String, val resultLabel: String, val prompt: String) {
    FIX(
        "Fix", "Suggested fix",
        "Fix all grammar, spelling, and punctuation errors. Change nothing else — keep the wording, tone, and formatting.",
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
        "The text is {language} written with English (Latin) letters, possibly mixed with English words. " +
            "Rewrite it in {language} using its native script with correct, natural spelling and punctuation. " +
            "Do not translate it into another language or change its meaning. " +
            "Keep names, brands, URLs, numbers, emoji and words normally written in English as they are.",
    ),
    ENGLISH(
        "English", "In English",
        "Translate the text into natural, fluent English. Keep names, URLs, numbers and emoji.",
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
