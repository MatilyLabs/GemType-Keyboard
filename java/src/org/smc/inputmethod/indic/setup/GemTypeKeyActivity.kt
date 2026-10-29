// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.os.Bundle
import android.view.View

import androidx.appcompat.app.AppCompatActivity

import com.android.inputmethod.latin.R

/**
 * The "add your Gemini API key" screen on its own, opened from the keyboard's GemType panel when
 * no key is saved. It is the key step of the first-run setup without the rest, and finishing it
 * returns to the app the user was typing in.
 */
internal class GemTypeKeyActivity : AppCompatActivity(), ApiKeyHost {

    override val standalone: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gemtype_setup_host)
        applySetupWindowInsets(findViewById(R.id.setup_wizard))
        findViewById<View>(R.id.setup_progress_group).visibility = View.INVISIBLE
        findViewById<View>(R.id.setup_back).setOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.setup_content, ApiKeyStepFragment())
                .commit()
        }
    }

    override fun onKeyStepDone(connected: Boolean) {
        finish()
    }
}
