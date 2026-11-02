// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import org.matily.gemtype.core.Correction
import org.matily.gemtype.core.WordDiff

/** Turns a [WordDiff] into styled text: removed words struck through in red, added words in green. */
object DiffSpans {
    class Colors(val removed: Int, val added: Int, val addedBackground: Int)

    fun build(original: String, fixed: String, colors: Colors): CharSequence {
        val out = SpannableStringBuilder()
        for (segment in WordDiff.diff(original, fixed)) {
            val start = out.length
            out.append(segment.text)
            val end = out.length
            when (segment.kind) {
                WordDiff.Kind.KEPT -> Unit
                WordDiff.Kind.REMOVED -> {
                    out.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(colors.removed), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                WordDiff.Kind.ADDED -> {
                    out.setSpan(ForegroundColorSpan(colors.added), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(colors.addedBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return out
    }

    /**
     * [text] with each correction marked in place: the wrong text struck through, the fix after it. The
     * ones at [skipped] (indexes into [corrections]) are left as written.
     */
    fun corrections(text: String, corrections: List<Correction>, skipped: Set<Int>, colors: Colors): CharSequence {
        val out = SpannableStringBuilder()
        var at = 0
        corrections.forEachIndexed { index, c ->
            if (c.start < at || c.end > text.length) return@forEachIndexed
            out.append(text, at, c.start)
            if (index in skipped) {
                out.append(c.original)
            } else {
                appendFix(out, c.original, c.replacement, colors)
            }
            at = c.end
        }
        return out.append(text, at, text.length)
    }

    /** "old → new" as one line, the old struck through in red and the new in green ([colors] null: plain). */
    fun pair(original: String, replacement: String, colors: Colors?): CharSequence {
        val out = SpannableStringBuilder()
        // Each side isolated, so an RTL word cannot pull the arrow to the wrong side.
        out.append(FSI)
        if (colors != null) appendRemoved(out, original, colors) else out.append(original)
        out.append(PDI).append(" \u2192 ").append(FSI)
        if (colors != null) appendAdded(out, replacement, colors) else out.append(replacement)
        return out.append(PDI)
    }

    private fun appendFix(out: SpannableStringBuilder, original: String, replacement: String, colors: Colors) {
        appendRemoved(out, original, colors)
        appendAdded(out, replacement, colors)
    }

    private fun appendRemoved(out: SpannableStringBuilder, text: String, colors: Colors) {
        val start = out.length
        out.append(text)
        out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(colors.removed), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendAdded(out: SpannableStringBuilder, text: String, colors: Colors) {
        val start = out.length
        out.append(text)
        out.setSpan(ForegroundColorSpan(colors.added), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(colors.addedBackground), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private const val FSI = "\u2068"
    private const val PDI = "\u2069"
}
