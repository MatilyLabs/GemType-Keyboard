// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import com.android.inputmethod.compat.PreferenceManagerCompat

/** Settings for "check as you type" (the keyboard's ordinary prefs: nothing secret). */
internal object CheckPrefs {
    /** Preference key of the "Check as you type" switch (also in prefs_screen_gemtype.xml). */
    const val LIVE_CHECK = "gemtype_live_check"

    fun liveCheck(context: Context): Boolean =
        PreferenceManagerCompat.getDeviceSharedPreferences(context).getBoolean(LIVE_CHECK, true)
}
