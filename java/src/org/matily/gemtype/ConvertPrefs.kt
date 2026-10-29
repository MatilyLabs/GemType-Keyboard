// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import androidx.core.content.edit
import com.android.inputmethod.compat.PreferenceManagerCompat
import org.matily.gemtype.core.Romanized

/** Settings for the "type in English letters, convert to your language" feature (the keyboard's ordinary prefs: nothing secret). */
internal object ConvertPrefs {
    /** Preference key of the "Show conversion pill" switch (also in prefs_screen_gemtype.xml). */
    const val SHOW_PILL = "gemtype_show_convert_pill"
    private const val CONVERT_TO = "gemtype_convert_to"
    private const val LAST_LANGUAGE = "gemtype_convert_last_language"

    private fun prefs(context: Context) = PreferenceManagerCompat.getDeviceSharedPreferences(context)

    fun showPill(context: Context): Boolean = prefs(context).getBoolean(SHOW_PILL, true)

    /** The languages to convert to: the user's choice, or until they choose, the enabled non-Latin keyboard languages (see [Romanized.targets]). */
    fun convertTo(context: Context): List<String> =
        Romanized.targets(prefs(context).getString(CONVERT_TO, null), RomanizedLists.enabledNonLatinLanguages(context))

    fun setConvertTo(context: Context, languages: Collection<String>) =
        prefs(context).edit { putString(CONVERT_TO, Romanized.formatTargets(languages)) }

    /** The language converted to last time; it is listed first when several match. */
    fun lastLanguage(context: Context): String? = prefs(context).getString(LAST_LANGUAGE, null)

    fun setLastLanguage(context: Context, language: String) = prefs(context).edit { putString(LAST_LANGUAGE, language) }
}
