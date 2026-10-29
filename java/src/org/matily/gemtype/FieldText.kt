// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.view.inputmethod.InputConnection

/**
 * The text GemType works on: the selection if there is one, else the whole field, read straight from
 * the InputConnection (not the keyboard's 1024-char cache). Shared by the panel and the conversion pill.
 */
internal class FieldText private constructor(
    val scope: Scope,
    val text: String,
    val status: Status,
) {
    enum class Scope { FIELD, SELECTION }
    enum class Status { READY, EMPTY, TOO_LONG }

    /**
     * Replace the text with [result] through [host], unless the field changed since it was read
     * (then nothing happens and the result is false).
     */
    fun replace(host: GemTypePanelController.Host, result: CharSequence): Boolean {
        val ic = host.inputConnection() ?: return false
        if (scope == Scope.SELECTION) {
            if (ic.getSelectedText(0)?.toString() != text) return false
            host.replaceSelection(result)
        } else {
            // Delete around wherever the cursor is now, so the whole field is replaced.
            val b = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
            val a = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
            if (b + a != text) return false
            host.replaceField(result, b.length, a.length)
        }
        return true
    }

    companion object {
        /** Upper bound for text read around the cursor (each side), and for a selection. */
        const val MAX_CHARS = 10_000

        fun read(ic: InputConnection): FieldText {
            val selected = ic.getSelectedText(0)?.toString()
            if (!selected.isNullOrEmpty()) {
                return FieldText(Scope.SELECTION, selected, statusOf(selected, selected.length > MAX_CHARS))
            }
            val b = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
            val a = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
            // Replacing only part of a longer field would leave the rest behind.
            return FieldText(Scope.FIELD, b + a, statusOf(b + a, b.length >= MAX_CHARS || a.length >= MAX_CHARS))
        }

        private fun statusOf(text: String, tooLong: Boolean) = when {
            text.isBlank() -> Status.EMPTY
            tooLong -> Status.TOO_LONG
            else -> Status.READY
        }
    }
}
