// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.view.inputmethod.InputConnection
import org.smc.inputmethod.indic.LatinIME
import org.smc.inputmethod.indic.settings.Settings

/** Connects the GemType panel to the keyboard service. */
class LatinImeGemTypeHost(private val ime: LatinIME) : GemTypePanelController.Host {
    override fun inputConnection(): InputConnection? = ime.currentInputConnection

    override fun isPasswordField(): Boolean =
        Settings.getInstance().getCurrent()?.mInputAttributes?.mIsPasswordField ?: false

    override fun replaceSelection(text: CharSequence) = ime.replaceTextFromGemType(false, text, 0, 0)

    override fun replaceField(text: CharSequence, before: Int, after: Int) =
        ime.replaceTextFromGemType(true, text, before, after)

    override fun closePanel() = ime.closeGemTypePanel()

    override fun openSettings() = ime.launchGemTypeKeyScreen()
}
