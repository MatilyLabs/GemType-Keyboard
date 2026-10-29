// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.view.inputmethod.InputConnection
import com.android.inputmethod.latin.RichInputMethodManager
import org.matily.gemtype.core.Romanized
import org.smc.inputmethod.indic.LatinIME
import org.smc.inputmethod.indic.settings.Settings

/** Connects the GemType panel and the conversion pills to the keyboard service. */
class LatinImeGemTypeHost(private val ime: LatinIME) : ConvertController.Host {
    override fun inputConnection(): InputConnection? = ime.currentInputConnection

    override fun isPasswordField(): Boolean =
        Settings.getInstance().getCurrent()?.mInputAttributes?.mIsPasswordField ?: false

    override fun replaceSelection(text: CharSequence) = ime.replaceTextFromGemType(false, text, 0, 0)

    override fun replaceField(text: CharSequence, before: Int, after: Int) =
        ime.replaceTextFromGemType(true, text, before, after)

    override fun closePanel() = ime.closeGemTypePanel()

    override fun openSettings() = ime.launchGemTypeKeyScreen()

    // The keyboard's dictionary knows English words only while an English keyboard is active.
    override fun englishCheck(): ((String) -> Boolean)? =
        if (ime.isEnglishDictionaryActive) ime::isValidSpellingWord else null

    override fun textBeforeCursor(n: Int): CharSequence = ime.getTextBeforeCursorFromCache(n)

    override fun cursor(): Int = ime.expectedCursor

    // A keyboard that types a non-Latin language, or transliterates by itself (Avro...), needs no pill.
    override fun isLatinKeyboard(): Boolean {
        val subtype = RichInputMethodManager.getInstance().currentSubtype
        return subtype.locale.language !in Romanized.NON_LATIN &&
            subtype.getExtraValueOf("TransliterationMethod").isNullOrEmpty()
    }

    override fun clearSuggestions() = ime.setNeutralSuggestionStrip()

    override fun select(start: Int, end: Int) = ime.setSelectionFromGemType(start, end)

    override fun onReplaced(field: FieldText, result: CharSequence) = ime.offerGemTypeUndo(field, result)
}
