// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView

import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.android.inputmethod.latin.R
import com.android.inputmethod.latin.utils.UncachedInputMethodManagerUtils
import com.google.android.material.button.MaterialButton

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Step 2: turn GemType on in Android's keyboard settings, then make it the current keyboard.
 * Both rows tick by themselves: the state is read again whenever the screen resumes or regains
 * window focus (the keyboard picker is a dialog), and twice a second while it is showing.
 */
internal class EnableStepFragment : Fragment() {

    private class Row(val view: View) {
        val indicator: ImageView = view.findViewById(R.id.row_indicator)
        val title: TextView = view.findViewById(R.id.row_title)
        val hint: TextView = view.findViewById(R.id.row_hint)
        val chevron: View = view.findViewById(R.id.row_chevron)
        var done = false
    }

    private lateinit var imm: InputMethodManager
    private lateinit var turnOn: Row
    private lateinit var switchTo: Row
    private lateinit var continueButton: MaterialButton
    private lateinit var skip: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.gemtype_setup_enable, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        imm = requireContext().getSystemService<InputMethodManager>()!!
        val rows = view.findViewById<ViewGroup>(R.id.enable_rows)
        turnOn = addRow(
            rows, R.string.gemtype_setup_turn_on, R.string.gemtype_setup_turn_on_hint,
            ::openInputMethodSettings
        )
        switchTo = addRow(
            rows, R.string.gemtype_setup_switch_to, R.string.gemtype_setup_switch_to_hint,
            ::showInputMethodPicker
        )
        continueButton = view.findViewById(R.id.enable_continue)
        continueButton.setOnClickListener { (requireActivity() as SetupNavigator).goNext() }
        skip = view.findViewById(R.id.enable_skip)
        skip.setOnClickListener { (requireActivity() as SetupNavigator).goNext() }
        refresh()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    /** Called by the activity when its window gains focus, e.g. after the keyboard picker. */
    fun onWindowFocus() {
        if (view != null) refresh()
    }

    private fun addRow(
        parent: ViewGroup, titleRes: Int, hintRes: Int, onClick: () -> Unit
    ): Row {
        val row = Row(layoutInflater.inflate(R.layout.gemtype_setup_check_row, parent, false))
        row.title.setText(titleRes)
        row.hint.setText(hintRes)
        row.view.setOnClickListener { onClick() }
        parent.addView(row.view)
        return row
    }

    private fun refresh() {
        val context = context ?: return
        val enabled = UncachedInputMethodManagerUtils.isThisImeEnabled(context, imm)
        val current = enabled && UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)
        show(turnOn, enabled, available = true)
        // The keyboard list only offers GemType once it is turned on.
        show(switchTo, current, available = enabled)
        continueButton.isEnabled = current
        skip.visibility = if (enabled && !current) View.VISIBLE else View.INVISIBLE
    }

    private fun show(row: Row, done: Boolean, available: Boolean) {
        if (row.done != done) {
            row.done = done
            row.indicator.setImageResource(
                if (done) R.drawable.gemtype_setup_indicator_on else R.drawable.gemtype_setup_indicator_off
            )
            row.indicator.contentDescription = if (done) getString(R.string.gemtype_setup_row_done) else null
        }
        row.view.isEnabled = available
        row.view.alpha = if (available) 1f else 0.5f
        row.chevron.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun openInputMethodSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT))
    }

    private fun showInputMethodPicker() {
        imm.showInputMethodPicker()
    }

    companion object {
        private const val POLL_MS = 500L
    }
}
