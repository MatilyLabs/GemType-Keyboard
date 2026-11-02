// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * How Fix works on a text. The proofreader (a list of corrections) is great for English; for Bangla, Hindi,
 * mixed or romanized text it only catches single words, so those get a whole-text rewrite instead.
 * No android.* imports, so it is unit-tested on the JVM.
 */
enum class FixMode {
    PROOFREAD, REWRITE;

    companion object {
        /** Share of the letters that must be Latin for the text to count as English. */
        private const val LATIN_PERCENT = 90

        /**
         * [PROOFREAD] when [text] is English: at least 90% of its letters are Latin (digits, punctuation,
         * emoji and URLs do not count; a text with no letters has nothing to rewrite, so it is English too)
         * and it is not [romanized] (Banglish, Hinglish...: [Romanized.detect] found a language in it).
         * Anything else is [REWRITE].
         */
        fun of(text: String, romanized: Boolean): FixMode {
            if (romanized) return REWRITE
            var letters = 0
            var latin = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                letters++
                if (isLatin(cp)) latin++
            }
            return if (latin * 100 >= LATIN_PERCENT * letters) PROOFREAD else REWRITE
        }

        /** Basic Latin through Latin Extended-B, and Latin Extended Additional (Vietnamese). */
        private fun isLatin(cp: Int) = cp <= 0x24F || cp in 0x1E00..0x1EFF
    }
}
