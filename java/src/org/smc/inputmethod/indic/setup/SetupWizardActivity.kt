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

package org.smc.inputmethod.indic.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction

import com.android.inputmethod.latin.R
import com.android.inputmethod.latin.utils.UncachedInputMethodManagerUtils
import com.google.android.material.color.MaterialColors

import org.matily.gemtype.GemType
import org.smc.inputmethod.indic.settings.SettingsActivity

/**
 * First-run setup, one screen per step: welcome, turn on the keyboard, languages, connect Gemini,
 * API key, ready. This activity owns the step state and the header (Back and the step
 * indicator); the screens themselves are the `*StepFragment` classes. The current step is saved,
 * so reopening the app, or returning from Android's settings or the browser, resumes where the
 * user was. Once setup has been completed the launcher goes straight to the keyboard settings.
 */
internal class SetupWizardActivity : AppCompatActivity(), SetupNavigator, ApiKeyHost {

    private lateinit var progress: SetupProgress
    private lateinit var back: View
    private lateinit var progressLabel: TextView
    private lateinit var backToPreviousStep: OnBackPressedCallback
    private val segments = ArrayList<View>()

    private var step = SetupStep.WELCOME

    override val standalone: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = SetupProgress(this)

        if (savedInstanceState != null) {
            step = SetupStep.of(savedInstanceState.getString(STATE_STEP)) ?: SetupStep.WELCOME
        } else {
            val start = firstStep()
            if (start == null) {
                openKeyboardSettings()
                finish()
                return
            }
            step = start
        }

        setContentView(R.layout.gemtype_setup_host)
        applySetupWindowInsets(findViewById(R.id.setup_wizard))
        back = findViewById(R.id.setup_back)
        back.setOnClickListener { goBack() }
        progressLabel = findViewById(R.id.setup_progress_label)
        buildProgressBar(findViewById(R.id.setup_progress_bar))

        backToPreviousStep = object : OnBackPressedCallback(step != SetupStep.WELCOME) {
            override fun handleOnBackPressed() = goBack()
        }
        onBackPressedDispatcher.addCallback(this, backToPreviousStep)

        showHeader()
        if (savedInstanceState == null) {
            show(step, animate = false)
        }
    }

    /**
     * Where to open: the saved step, the welcome screen on a fresh install, or null when setup
     * is already done and the keyboard settings should open instead.
     */
    private fun firstStep(): SetupStep? {
        if (intent.getBooleanExtra(EXTRA_FORCE_SETUP, false)) {
            progress.reset()
            return SetupStep.WELCOME
        }
        if (progress.completed) {
            // Users who finished setup once fix a switched-away keyboard from the settings
            // status card instead of re-running it.
            return null
        }
        progress.step?.let { return it }
        val imm = getSystemService<InputMethodManager>()!!
        if (UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm) &&
            UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm)
        ) {
            // The keyboard is on and current from before setup tracked its progress: de facto done.
            progress.completed = true
            return null
        }
        return SetupStep.WELCOME
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STEP, step.name)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            (supportFragmentManager.findFragmentById(R.id.setup_content) as? EnableStepFragment)
                ?.onWindowFocus()
        }
    }

    // ---- navigation -----------------------------------------------------------------------

    override fun goNext() {
        when (step) {
            SetupStep.WELCOME -> goTo(SetupStep.ENABLE)
            SetupStep.ENABLE -> goTo(SetupStep.LANGUAGES)
            // The offline edition has no internet, so it has no Gemini steps.
            SetupStep.LANGUAGES -> goTo(if (GemType.isEnabled) SetupStep.CONNECT else SetupStep.READY)
            SetupStep.CONNECT -> goTo(SetupStep.KEY)
            SetupStep.KEY -> goTo(SetupStep.READY)
            SetupStep.READY -> finishSetup()
        }
    }

    override fun skipGemini() {
        progress.geminiSkipped = true
        goTo(SetupStep.READY)
    }

    override fun openKeyStep() = goTo(SetupStep.KEY)

    override fun onKeyStepDone(connected: Boolean) {
        progress.geminiSkipped = !connected
        goTo(SetupStep.READY)
    }

    private fun goBack() {
        when (step) {
            SetupStep.WELCOME -> Unit
            SetupStep.ENABLE -> goTo(SetupStep.WELCOME)
            SetupStep.LANGUAGES -> goTo(SetupStep.ENABLE)
            SetupStep.CONNECT -> goTo(SetupStep.LANGUAGES)
            SetupStep.KEY -> goTo(SetupStep.CONNECT)
            SetupStep.READY -> goTo(
                when {
                    !GemType.isEnabled -> SetupStep.LANGUAGES
                    progress.geminiSkipped -> SetupStep.CONNECT
                    else -> SetupStep.KEY
                }
            )
        }
    }

    private fun goTo(next: SetupStep) {
        hideKeyboard()
        step = next
        progress.step = next
        backToPreviousStep.isEnabled = step != SetupStep.WELCOME
        showHeader()
        show(next, animate = true)
    }

    override fun finishSetup() {
        progress.completed = true
        hideKeyboard()
        openKeyboardSettings()
        finish()
    }

    private fun show(step: SetupStep, animate: Boolean) {
        val transaction = supportFragmentManager.beginTransaction()
        if (animate) transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        transaction.replace(R.id.setup_content, fragmentFor(step)).commit()
    }

    private fun fragmentFor(step: SetupStep): Fragment = when (step) {
        SetupStep.WELCOME -> WelcomeStepFragment()
        SetupStep.ENABLE -> EnableStepFragment()
        SetupStep.LANGUAGES -> LanguagesStepFragment()
        SetupStep.CONNECT -> ConnectStepFragment()
        SetupStep.KEY -> ApiKeyStepFragment()
        SetupStep.READY -> ReadyStepFragment()
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun openKeyboardSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SettingsActivity.EXTRA_ENTRY_KEY, SettingsActivity.EXTRA_ENTRY_VALUE_APP_ICON)
        })
    }

    // ---- header: Back and the step indicator -----------------------------------------------

    private fun buildProgressBar(bar: LinearLayout) {
        val gap = (2 * resources.displayMetrics.density).toInt()
        repeat(SetupStep.entries.size) { index ->
            val segment = View(this).apply {
                setBackgroundResource(R.drawable.gemtype_setup_segment)
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginStart = if (index == 0) 0 else gap
            params.marginEnd = if (index == SetupStep.entries.size - 1) 0 else gap
            bar.addView(segment, params)
            segments.add(segment)
        }
    }

    private fun showHeader() {
        back.visibility = if (step == SetupStep.WELCOME) View.INVISIBLE else View.VISIBLE
        progressLabel.text = getString(
            R.string.gemtype_setup_step_progress, step.ordinal + 1, SetupStep.entries.size
        )
        val on = MaterialColors.getColor(progressLabel, androidx.appcompat.R.attr.colorPrimary)
        val off = MaterialColors.getColor(
            progressLabel, com.google.android.material.R.attr.colorOutlineVariant
        )
        segments.forEachIndexed { index, segment ->
            segment.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (index <= step.ordinal) on else off
            )
        }
    }

    companion object {
        const val EXTRA_FORCE_SETUP = "force_setup"

        private const val STATE_STEP = "step"
    }
}
