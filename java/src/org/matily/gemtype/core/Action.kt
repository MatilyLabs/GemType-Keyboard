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
}
