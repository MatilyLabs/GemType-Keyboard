// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.content.SharedPreferences
import org.smc.inputmethod.indic.settings.Settings
import org.smc.inputmethod.indic.varnam.VarnamIndicKeyboard

/**
 * The language whose words the English keyboard's strip offers in their own script ("companion"
 * suggestions). The user's choice in a language's settings wins, "none" included; until they choose,
 * it is the first language they convert to (their enabled keyboards, see [ConvertPrefs.convertTo])
 * that has a phonetic engine, so someone who set up Bangla gets Bangla words without looking for a switch.
 */
object CompanionLanguage {
    @JvmStatic
    fun read(context: Context, prefs: SharedPreferences): String {
        if (prefs.contains(Settings.PREF_COMPANION_LANGUAGE)) return Settings.readCompanionLanguage(prefs)
        return ConvertPrefs.convertTo(context).firstOrNull { VarnamIndicKeyboard.schemes.containsKey("varnam-$it") } ?: ""
    }
}
