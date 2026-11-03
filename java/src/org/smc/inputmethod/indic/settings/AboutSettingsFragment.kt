/*
 * Copyright 2026, Jishnu Mohan <jishnu@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smc.inputmethod.indic.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

import androidx.preference.Preference

import com.android.inputmethod.latin.R

/** "About" sub screen: app header, the key-limits notice, links, problem reports and the open-source licenses and credits. */
class AboutSettingsFragment : SubScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        addPreferencesFromResource(R.xml.prefs_screen_about)
        openUrlOnClick("about_website", R.string.about_website_url)
        openUrlOnClick("about_source", R.string.about_source_url)
        openUrlOnClick("about_privacy", R.string.about_privacy_url)
        openUrlOnClick("about_contact", R.string.about_contact_url)
        openUrlOnClick("about_limits", R.string.about_limits_url)
        requirePreference<Preference>("about_report").setOnPreferenceClickListener {
            sendReport()
            true
        }

        requirePreference<Preference>("about_made_by").title =
            getText(R.string.about_made_by)
    }

    /** Open an email to support with the app version and device filled in (never any typed text). */
    private fun sendReport() {
        val version = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull().orEmpty()
        val body = getString(R.string.about_report_body, version, Build.VERSION.RELEASE, "${Build.MANUFACTURER} ${Build.MODEL}")
        val uri = Uri.parse(
            getString(R.string.about_contact_url) +
                "?subject=" + Uri.encode(getString(R.string.about_report_subject)) + "&body=" + Uri.encode(body))
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (e: ActivityNotFoundException) {
            // No mail app; the Contact row above shows the address.
        }
    }

    override fun onResume() {
        super.onResume()
        setActionBarTitle(getString(R.string.settings_screen_about))
    }
}
