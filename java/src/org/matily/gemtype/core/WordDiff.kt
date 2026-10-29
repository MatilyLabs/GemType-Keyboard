// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * Word-level diff for the Fix view. [diff] returns one run of text per change:
 * words kept, words removed from the original, words added by the fix.
 * Falls back to the plain fixed text for very long inputs (LCS is O(n*m)).
 */
object WordDiff {
    enum class Kind { KEPT, REMOVED, ADDED }

    data class Segment(val kind: Kind, val text: String)

    private const val MAX_WORDS = 800

    /** A word and the whitespace that follows it. */
    private class Token(val word: String, val space: String)

    private fun tokenize(s: String): Pair<String, List<Token>> {
        val lead = s.takeWhile { it.isWhitespace() }
        val tokens = ArrayList<Token>()
        var i = lead.length
        while (i < s.length) {
            var j = i
            while (j < s.length && !s[j].isWhitespace()) j++
            var k = j
            while (k < s.length && s[k].isWhitespace()) k++
            tokens.add(Token(s.substring(i, j), s.substring(j, k)))
            i = k
        }
        return lead to tokens
    }

    /**
     * Inline diff of [original] against [fixed]. The KEPT and ADDED segments, joined,
     * are exactly [fixed]; REMOVED segments are the original words that went away.
     */
    fun diff(original: String, fixed: String): List<Segment> {
        val (_, a) = tokenize(original)
        val (lead, b) = tokenize(fixed)
        if (a.size > MAX_WORDS || b.size > MAX_WORDS) return listOf(Segment(Kind.KEPT, fixed))

        // lcs[i][j] = LCS length of a[i..] and b[j..]
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i].word == b[j].word) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Segment>()
        fun add(kind: Kind, text: String) {
            if (text.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.size - 1] = Segment(kind, last.text + text)
            else out.add(Segment(kind, text))
        }
        add(Kind.KEPT, lead)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i].word == b[j].word -> {
                    add(Kind.KEPT, b[j].word + b[j].space); i++; j++
                }
                j >= b.size || (i < a.size && lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                    add(Kind.REMOVED, a[i].word + a[i].space.ifEmpty { " " }); i++
                }
                else -> {
                    add(Kind.ADDED, b[j].word + b[j].space); j++
                }
            }
        }
        return out
    }
}
