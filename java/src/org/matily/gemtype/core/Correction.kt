// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * One mistake Gemini found: [original] (a verbatim piece of the checked text) should become
 * [replacement], and [explanation] says why in a few plain words. [start]..[end] is where
 * [original] sits in the checked text, or -1 until [CheckProtocol] has located it.
 */
data class Correction(
    val original: String,
    val replacement: String,
    val explanation: String,
    val type: Type,
    val start: Int = -1,
    val end: Int = -1,
) {
    enum class Type {
        GRAMMAR, SPELLING, PUNCTUATION, STYLE;

        companion object {
            /** Gemini answers in lower case; anything unexpected counts as style. */
            fun parse(name: String?): Type = values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: STYLE
        }
    }
}

object Corrections {
    /**
     * [text] with [corrections] applied, last first so earlier positions stay valid. A correction
     * that no longer matches its place in [text] (the text changed meanwhile) or overlaps one
     * already applied is skipped rather than corrupting the text.
     */
    fun apply(text: String, corrections: List<Correction>): String {
        val out = StringBuilder(text)
        var limit = text.length
        for (c in corrections.sortedByDescending { it.start }) {
            if (c.start < 0 || c.end > limit || c.start > c.end || text.substring(c.start, c.end) != c.original) continue
            out.replace(c.start, c.end, c.replacement)
            limit = c.start
        }
        return out.toString()
    }
}
