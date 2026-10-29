// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
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
}
