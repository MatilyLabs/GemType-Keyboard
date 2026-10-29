// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.fragment.app.Fragment

import com.android.inputmethod.latin.R

/** Step 1: the GemType mark and a greeting that cycles through languages. */
internal class WelcomeStepFragment : Fragment() {

    private lateinit var greeting: GreetingAnimator

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.gemtype_setup_welcome, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        greeting = GreetingAnimator(
            view.findViewById<TextView>(R.id.welcome_word),
            resources.getStringArray(R.array.gemtype_setup_greetings)
        )
        view.findViewById<View>(R.id.welcome_get_started).setOnClickListener {
            (requireActivity() as SetupNavigator).goNext()
        }
    }

    override fun onResume() {
        super.onResume()
        greeting.start()
    }

    override fun onPause() {
        greeting.stop()
        super.onPause()
    }
}
