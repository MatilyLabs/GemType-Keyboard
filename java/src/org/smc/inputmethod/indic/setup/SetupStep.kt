// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.content.Context

import androidx.core.content.edit

import com.android.inputmethod.compat.PreferenceManagerCompat

import org.smc.inputmethod.indic.settings.Settings as AppSettings

/** The six screens of the first-run setup, in order. */
internal enum class SetupStep {
    WELCOME, ENABLE, LANGUAGES, CONNECT, KEY, READY;

    companion object {
        val COUNT = entries.size

        fun of(name: String?): SetupStep? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}

/**
 * Where the user is in the setup, kept in the keyboard's preferences so that reopening the app,
 * or coming back from Android's settings or the browser, resumes on the same screen.
 */
internal class SetupProgress(context: Context) {
    private val prefs = PreferenceManagerCompat.getDeviceSharedPreferences(context)

    /** The saved step, or null if the user has not moved past the welcome screen. */
    var step: SetupStep?
        get() = SetupStep.of(prefs.getString(AppSettings.PREF_SETUP_STEP, null))
        set(value) = prefs.edit {
            if (value == null) remove(AppSettings.PREF_SETUP_STEP)
            else putString(AppSettings.PREF_SETUP_STEP, value.name)
        }

    /** True when the user skipped the Gemini steps; the last screen then points to Settings. */
    var geminiSkipped: Boolean
        get() = prefs.getBoolean(AppSettings.PREF_SETUP_GEMINI_SKIPPED, false)
        set(value) = prefs.edit { putBoolean(AppSettings.PREF_SETUP_GEMINI_SKIPPED, value) }

    var completed: Boolean
        get() = prefs.getBoolean(AppSettings.PREF_SETUP_COMPLETED, false)
        set(value) = prefs.edit { putBoolean(AppSettings.PREF_SETUP_COMPLETED, value) }

    /** Forget the position, for "Run setup again". */
    fun reset() = prefs.edit {
        remove(AppSettings.PREF_SETUP_STEP)
        remove(AppSettings.PREF_SETUP_GEMINI_SKIPPED)
    }
}

/** What the step screens ask of the activity that shows them. */
internal interface SetupNavigator {
    fun goNext()
    /** Leave the Gemini steps without connecting. */
    fun skipGemini()
    fun openKeyStep()
    fun finishSetup()
}

/** The API key step runs inside the setup and, from the keyboard panel, on its own. */
internal interface ApiKeyHost {
    val standalone: Boolean
    fun onKeyStepDone(connected: Boolean)
}
