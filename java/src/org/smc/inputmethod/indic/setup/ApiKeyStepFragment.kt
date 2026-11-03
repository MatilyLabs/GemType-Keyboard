// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast

import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.android.inputmethod.latin.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.matily.gemtype.CheckPrefs
import org.matily.gemtype.GemTypeSettings
import org.matily.gemtype.KeyTester
import org.matily.gemtype.core.GeminiKey

/**
 * Step 5: get a free Gemini key from Google AI Studio and paste it. A pasted key that looks like
 * a Gemini key is tested straight away and saved when it works; any other value can be tested
 * with the Test button. The key never leaves the encrypted store except in the test request.
 */
internal class ApiKeyStepFragment : Fragment() {

    private lateinit var host: ApiKeyHost
    private lateinit var field: TextInputEditText
    private lateinit var paste: MaterialButton
    private lateinit var test: MaterialButton
    private lateinit var status: TextView
    private lateinit var continueButton: MaterialButton

    /** A key was already saved before this screen opened. */
    private var alreadySaved = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.gemtype_setup_key, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        host = requireActivity() as ApiKeyHost
        field = view.findViewById(R.id.key_field)
        paste = view.findViewById(R.id.key_paste)
        test = view.findViewById(R.id.key_test)
        status = view.findViewById(R.id.key_status)
        continueButton = view.findViewById(R.id.key_continue)
        val skip = view.findViewById<MaterialButton>(R.id.key_skip)
        if (host.standalone) {
            continueButton.setText(R.string.gemtype_setup_done)
            skip.setText(R.string.gemtype_setup_not_now)
        }

        // Check as you type sends what you type, so it stays off until the person switches it on here.
        view.findViewById<MaterialSwitch>(R.id.key_live_check).apply {
            isChecked = CheckPrefs.liveCheck(requireContext())
            setOnCheckedChangeListener { _, on -> CheckPrefs.setLiveCheck(requireContext(), on) }
        }

        view.findViewById<View>(R.id.key_get).setOnClickListener { openAiStudio() }
        paste.setOnClickListener { pasteFromClipboard() }
        test.setOnClickListener { runTest() }
        continueButton.setOnClickListener { host.onKeyStepDone(true) }
        skip.setOnClickListener { host.onKeyStepDone(false) }

        KeyTester.reset()
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                GemTypeSettings.load(requireContext().applicationContext).apiKey
            }
            alreadySaved = saved.isNotBlank()
            render(KeyTester.state.value)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KeyTester.state.collect { render(it) }
            }
        }
        render(KeyTester.state.value)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Attached after the saved text is restored, so a rotation does not start a new test.
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onKeyChanged(s?.toString().orEmpty().trim())
        })
    }

    private fun onKeyChanged(key: String) {
        if (KeyTester.state.value !is KeyTester.State.Running) {
            KeyTester.reset()
        }
        if (GeminiKey.looksLikeKey(key)) {
            runTest()
        } else {
            render(KeyTester.state.value)
        }
    }

    private fun runTest() {
        val key = field.text?.toString().orEmpty().trim()
        if (key.isEmpty() || KeyTester.state.value is KeyTester.State.Running) return
        hideKeyboard()
        KeyTester.run(requireContext(), key)
    }

    private fun pasteFromClipboard() {
        // The clipboard is read here, and only here, because the user tapped Paste.
        val clipboard = requireContext().getSystemService<ClipboardManager>()
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.gemtype_setup_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        field.setText(text)
        field.setSelection(field.length())
    }

    private fun openAiStudio() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_URL)))
        } catch (_: ActivityNotFoundException) {
            // No browser installed; nothing useful to do.
        }
    }

    private fun hideKeyboard() {
        field.clearFocus()
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun render(state: KeyTester.State) {
        if (view == null) return
        val running = state is KeyTester.State.Running
        val hasText = !field.text.isNullOrBlank()
        field.isEnabled = !running
        paste.isEnabled = !running
        test.isEnabled = hasText && !running
        when (state) {
            KeyTester.State.Idle ->
                if (alreadySaved && !hasText) {
                    status.setText(R.string.gemtype_setup_key_already_saved)
                    status.setTextColor(color(OK_LIGHT, OK_DARK))
                } else {
                    status.text = ""
                }
            KeyTester.State.Running -> {
                status.setText(R.string.gemtype_test_running)
                status.setTextColor(color(null, null))
            }
            is KeyTester.State.Ok -> {
                status.setText(R.string.gemtype_setup_key_ok)
                status.setTextColor(color(OK_LIGHT, OK_DARK))
            }
            is KeyTester.State.Failed -> {
                status.text = getString(R.string.gemtype_test_failed, state.message)
                status.setTextColor(color(ERROR_LIGHT, ERROR_DARK))
            }
        }
        continueButton.isEnabled = state is KeyTester.State.Ok ||
            (state == KeyTester.State.Idle && alreadySaved && !hasText)
    }

    /** A status colour readable on the current theme; null means the normal secondary text. */
    private fun color(light: Int?, dark: Int?): Int {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val chosen = if (night) dark else light
        return chosen ?: com.google.android.material.color.MaterialColors.getColor(
            status, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
    }

    companion object {
        private const val KEY_URL = "https://aistudio.google.com/apikey"
        private val OK_LIGHT = Color.rgb(0x1B, 0x8A, 0x4B)
        private val OK_DARK = Color.rgb(0x6F, 0xD3, 0x9A)
        private val ERROR_LIGHT = Color.rgb(0xC0, 0x36, 0x2C)
        private val ERROR_DARK = Color.rgb(0xFF, 0x8A, 0x80)
    }
}
