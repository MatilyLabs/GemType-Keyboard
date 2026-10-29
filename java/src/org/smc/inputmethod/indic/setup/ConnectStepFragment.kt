// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.fragment.app.Fragment

import com.android.inputmethod.latin.R

/** Step 4: offer the optional Gemini connection. */
internal class ConnectStepFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.gemtype_setup_connect, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val navigator = requireActivity() as SetupNavigator
        view.findViewById<View>(R.id.connect_button).setOnClickListener { navigator.openKeyStep() }
        view.findViewById<View>(R.id.connect_skip).setOnClickListener { navigator.skipGemini() }
    }
}
