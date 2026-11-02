// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/** The rules of "check as you type", without any Android: when to ask Gemini and what is still valid. */
object LiveCheck {
    /** Typing pause before a check. */
    const val DEBOUNCE_MS = 1_500L
    const val MIN_WORDS = 3

    /** After any error (a 429 included) no check is sent for this long. */
    const val ERROR_PAUSE_MS = 60_000L

    fun wordCount(text: String): Int = Romanized.words(text).size

    /** True when the cursor is inside a word: the character before it is a letter, digit or combining mark (an Indic vowel sign). */
    fun isMidWord(textBeforeCursor: CharSequence): Boolean = textBeforeCursor.lastOrNull()?.let(::isWordChar) == true

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || Character.getType(c).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        }

    /**
     * The corrections that still apply to [text], which may have been edited since they were found: each keeps
     * its place if its original is still there, else moves to the next whole-word match that overlaps none; the
     * rest are dropped. (A short original such as "i" must not be found inside another word.)
     */
    fun relocate(text: String, corrections: List<Correction>): List<Correction> {
        val placed = ArrayList<Correction>()
        for (c in corrections) {
            val at = if (c.start >= 0 && fits(text, c.original, c.start, placed)) c.start else findFrom(text, c, placed)
            if (at >= 0) placed.add(c.copy(start = at, end = at + c.original.length))
        }
        return placed.sortedBy { it.start }
    }

    private fun findFrom(text: String, c: Correction, placed: List<Correction>): Int {
        var from = 0
        while (true) {
            val idx = text.indexOf(c.original, from)
            if (idx == -1) return -1
            if (fits(text, c.original, idx, placed)) return idx
            from = idx + 1
        }
    }

    /** [original] is at [start] in [text], is not cut out of a longer word and overlaps none of [placed]. */
    private fun fits(text: String, original: String, start: Int, placed: List<Correction>): Boolean {
        val end = start + original.length
        if (original.isEmpty() || end > text.length || !text.startsWith(original, start)) return false
        if (isWordChar(original.first()) && start > 0 && isWordChar(text[start - 1])) return false
        if (isWordChar(original.last()) && end < text.length && isWordChar(text[end])) return false
        return placed.none { start < it.end && end > it.start }
    }

    /** "tmk → tomake": a correction as one short line (each side isolated so an RTL word cannot reorder the arrow). */
    fun chipLabel(c: Correction): String = "⁨${c.original}⁩ → ⁨${c.replacement}⁩"

    /** What the strip should do next about [text]. */
    sealed class Decision {
        object Skip : Decision()
        object Check : Decision()

        /** Wanted, but paused: look again after [afterMs]. */
        class Later(val afterMs: Long) : Decision()
    }
}

/**
 * What "check as you type" knows: the last answer, whether a request is out, and the error pause. All time
 * comes in as an argument. A request is identified by the number [start] gave it, so an answer that
 * arrives after [reset] (another field) or after a newer request is dropped.
 */
class LiveCheckState {
    private var seq = 0
    private var inFlight = false
    private var lastText: String? = null
    private var pausedUntil = 0L

    /** The text the last answer was for, and what it found. */
    private var checkedText: String? = null
    private var answer: List<Correction> = emptyList()

    /** The answer as it applies to the text [refresh] last saw. */
    var corrections: List<Correction> = emptyList()
        private set

    /** Should a request for [text] go out now? Not when it is short, one is out, it was already asked, or an error pause is on. */
    fun decide(text: String, now: Long): LiveCheck.Decision = when {
        LiveCheck.wordCount(text) < LiveCheck.MIN_WORDS || inFlight || text == lastText -> LiveCheck.Decision.Skip
        now < pausedUntil -> LiveCheck.Decision.Later(pausedUntil - now)
        else -> LiveCheck.Decision.Check
    }

    /** A request for [text] is going out: its number. */
    fun start(text: String): Int {
        inFlight = true
        lastText = text
        return ++seq
    }

    /** Request [id] answered: false when the answer is stale and was dropped. */
    fun succeed(id: Int, text: String, found: List<Correction>): Boolean {
        if (id != seq) return false
        inFlight = false
        checkedText = text
        answer = found
        corrections = found
        return true
    }

    /** Request [id] failed (any reason): silence for [LiveCheck.ERROR_PAUSE_MS]. The same text is not asked again. */
    fun fail(id: Int, now: Long) {
        if (id != seq) return
        inFlight = false
        pausedUntil = now + LiveCheck.ERROR_PAUSE_MS
    }

    /** Request [id] was not sent (nothing to ask with, e.g. no key); the same text is not asked again. */
    fun drop(id: Int) {
        if (id == seq) inFlight = false
    }

    /** [text] is what the field holds now: keep the corrections that still apply. */
    fun refresh(text: String): List<Correction> {
        corrections = if (text == checkedText) answer else LiveCheck.relocate(text, answer)
        return corrections
    }

    /** The answer for exactly [text], if there is one (an empty list means "no mistakes"). */
    fun cached(text: String): List<Correction>? = if (text == checkedText) answer else null

    /** The field changed or input ended: forget everything but the error pause. */
    fun reset() {
        seq++
        inFlight = false
        lastText = null
        checkedText = null
        answer = emptyList()
        corrections = emptyList()
    }
}
