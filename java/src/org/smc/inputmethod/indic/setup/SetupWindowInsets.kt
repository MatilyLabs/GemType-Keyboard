// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.view.View

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps a setup screen clear of the status and navigation bars, the display cutout and the
 * keyboard, so the practice box and its buttons stay visible above the keyboard.
 */
internal fun applySetupWindowInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                or WindowInsetsCompat.Type.ime()
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
