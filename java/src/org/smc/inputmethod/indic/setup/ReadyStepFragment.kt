// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

import com.android.inputmethod.latin.R

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.matily.gemtype.GemType
import org.matily.gemtype.GemTypeSettings

/**
 * Step 6: a practice box that opens the keyboard, a short animation of the GemType panel, and
 * two tips. If Gemini was skipped, the first tip points to where it can be connected later.
 */
internal class ReadyStepFragment : Fragment() {

    private lateinit var demo: GemTypeDemoView
    private lateinit var practice: EditText
    private lateinit var tipFix: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.gemtype_setup_ready, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        demo = view.findViewById(R.id.ready_demo)
        practice = view.findViewById(R.id.ready_practice)
        tipFix = view.findViewById(R.id.ready_tip_fix)
        tipFix.setText(R.string.gemtype_setup_tip_fix)
        if (!GemType.isEnabled) {
            demo.visibility = View.GONE
            tipFix.visibility = View.GONE
        }
        view.findViewById<View>(R.id.ready_done).setOnClickListener {
            (requireActivity() as SetupNavigator).finishSetup()
        }
        if (savedInstanceState == null) {
            practice.setSelection(practice.length())
        }
    }

    override fun onResume() {
        super.onResume()
        if (GemType.isEnabled) {
            demo.start()
            showTip()
        }
        // Focus the box so the keyboard comes up; once the window has settled.
        practice.requestFocus()
        practice.postDelayed({
            if (isResumed) {
                requireContext().getSystemService<InputMethodManager>()
                    ?.showSoftInput(practice, InputMethodManager.SHOW_IMPLICIT)
            }
        }, KEYBOARD_DELAY_MS)
    }

    /**
     * The first tip: how to use GemType, or, when Gemini was skipped and still has no key, where
     * to connect it. Checked on every resume, as the key may have been added from the keyboard.
     */
    private fun showTip() {
        if (!SetupProgress(requireContext()).geminiSkipped) return
        viewLifecycleOwner.lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) {
                GemTypeSettings.load(requireContext().applicationContext).apiKey
            }
            tipFix.setText(
                if (key.isBlank()) R.string.gemtype_setup_tip_connect else R.string.gemtype_setup_tip_fix
            )
        }
    }

    override fun onPause() {
        demo.stop()
        super.onPause()
    }

    companion object {
        private const val KEYBOARD_DELAY_MS = 350L
    }
}
