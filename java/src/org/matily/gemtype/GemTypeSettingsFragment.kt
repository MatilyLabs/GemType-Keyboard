// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.inputmethod.latin.R
import com.android.inputmethod.latin.utils.KeyboardLanguages
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matily.gemtype.core.GemtypeConfig
import org.matily.gemtype.core.Romanized
import org.smc.inputmethod.indic.setup.LanguageOrder
import org.smc.inputmethod.indic.settings.SubScreenFragment
import java.util.Locale

/**
 * The GemType screen in the keyboard's settings. The values live in the encrypted
 * [GemTypeSettings] store, never in the keyboard's ordinary SharedPreferences, so every
 * preference here is non-persistent and wired up by hand.
 */
class GemTypeSettingsFragment : SubScreenFragment() {

    private lateinit var apiKey: EditTextPreference
    private lateinit var testKey: Preference
    private lateinit var model: ListPreference
    private lateinit var customModel: EditTextPreference
    private lateinit var qualityHint: Preference
    private lateinit var language: ListPreference
    private lateinit var convertTo: Preference
    private var convertToDialog: AlertDialog? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        addPreferencesFromResource(R.xml.prefs_screen_gemtype)

        apiKey = requirePreference("gemtype_api_key")
        testKey = requirePreference("gemtype_test_key")
        model = requirePreference("gemtype_model")
        customModel = requirePreference("gemtype_custom_model")
        qualityHint = requirePreference("gemtype_quality_hint")
        language = requirePreference("gemtype_language")

        // The key is masked while typed, and kept out of autofill.
        apiKey.setOnBindEditTextListener { edit ->
            // setSingleLine() replaces the transformation method, so it must run before the
            // password input type, or the key shows in clear text.
            edit.setSingleLine()
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            edit.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            edit.setSelection(edit.text.length)
        }
        apiKey.setOnPreferenceChangeListener { _, value ->
            val key = (value as? String).orEmpty().trim()
            val ctx = requireContext().applicationContext
            saveAsync { GemTypeSettings.setApiKey(ctx, key) }
            showKey(key)
            KeyTester.reset()
            // The dialog text is never stored: the key must not sit in any preference object.
            false
        }
        testKey.setOnPreferenceClickListener {
            KeyTester.run(requireContext())
            true
        }
        requirePreference<Preference>("gemtype_get_key").setOnPreferenceClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_URL)))
            } catch (_: ActivityNotFoundException) {
                // No browser installed; nothing useful to do.
            }
            true
        }

        model.entries = (GemTypeSettings.MODELS.map { it.second } +
            getString(R.string.gemtype_model_custom)).toTypedArray()
        model.entryValues = (GemTypeSettings.MODELS.map { it.first } + CUSTOM).toTypedArray()
        model.setOnPreferenceChangeListener { _, value ->
            KeyTester.reset()
            if (value == CUSTOM) {
                showModelChoice(CUSTOM, customModel.text.orEmpty())
                if (!customModel.text.isNullOrBlank()) saveModel(customModel.text.orEmpty())
            } else {
                showModelChoice(value as String, "")
                saveModel(value)
            }
            true
        }
        customModel.setOnBindEditTextListener { edit ->
            edit.setSingleLine()
            edit.hint = getString(R.string.gemtype_model_custom_hint)
            edit.setSelection(edit.text.length)
        }
        customModel.setOnPreferenceChangeListener { _, value ->
            val id = (value as? String).orEmpty().trim()
            KeyTester.reset()
            customModel.text = id
            saveModel(id)
            true
        }

        language.entries = GemTypeSettings.LANGUAGES.map {
            if (it == GemTypeSettings.AUTO_LANGUAGE) getString(R.string.gemtype_language_auto) else it
        }.toTypedArray()
        language.entryValues = GemTypeSettings.LANGUAGES.toTypedArray()
        language.setOnPreferenceChangeListener { _, value ->
            val ctx = requireContext().applicationContext
            saveAsync { GemTypeSettings.setLanguage(ctx, value as String) }
            true
        }
        convertTo = requirePreference("gemtype_convert_to")
        convertTo.setOnPreferenceClickListener {
            showConvertToDialog()
            true
        }
        showModelChoice(GemTypeSettings.DEFAULT_MODEL, "")
        language.value = GemTypeSettings.AUTO_LANGUAGE
        showKey("")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Opening the encrypted store touches the Keystore: do it off the main thread.
        viewLifecycleOwner.lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { GemTypeSettings.load(requireContext().applicationContext) }
            showConfig(config)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KeyTester.state.collect { showTestState(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setActionBarTitle(getString(R.string.gemtype_settings_title))
        // The default follows the enabled keyboard languages, which may have changed since.
        showConvertTo()
    }

    override fun onDestroyView() {
        convertToDialog?.dismiss()
        convertToDialog = null
        super.onDestroyView()
    }

    // ---- showing values ------------------------------------------------------------------

    private fun showConfig(config: GemtypeConfig) {
        showKey(config.apiKey)
        val known = GemTypeSettings.MODELS.any { it.first == config.model }
        if (known) showModelChoice(config.model, "") else showModelChoice(CUSTOM, config.model)
        language.value = config.language.takeIf { it in GemTypeSettings.LANGUAGES } ?: GemTypeSettings.AUTO_LANGUAGE
    }

    private fun showConvertTo() {
        convertTo.summary = ConvertPrefs.convertTo(requireContext()).joinToString { Romanized.nativeName(it) }
            .ifEmpty { getString(R.string.gemtype_convert_to_none) }
    }

    private fun showKey(key: String) {
        apiKey.summary = if (key.isBlank()) getString(R.string.gemtype_api_key_not_set)
        else getString(R.string.gemtype_api_key_set, key.takeLast(4))
    }

    private fun showModelChoice(choice: String, custom: String) {
        model.value = choice
        customModel.text = custom
        customModel.isVisible = choice == CUSTOM
        qualityHint.isVisible = choice == GemTypeSettings.QUALITY_MODEL
    }

    private fun showTestState(state: KeyTester.State) {
        when (state) {
            KeyTester.State.Idle -> testKey.setSummary(R.string.gemtype_test_key_summary)
            KeyTester.State.Running -> testKey.setSummary(R.string.gemtype_test_running)
            is KeyTester.State.Ok -> testKey.summary = colored(
                getString(R.string.gemtype_test_ok, state.model, String.format(Locale.US, "%.1f", state.seconds)),
                OK_COLOR,
            )
            is KeyTester.State.Failed -> testKey.summary = colored(
                getString(R.string.gemtype_test_failed, state.message), ERROR_COLOR,
            )
        }
        testKey.isEnabled = state != KeyTester.State.Running
    }

    private fun colored(text: String, color: Int): CharSequence =
        SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** Every non-Latin-script language the keyboard types, in the order of the language lists: Bangla, Hindi, then A to Z. */
    private fun showConvertToDialog() {
        val ctx = requireContext()
        val languages = KeyboardLanguages.getLanguages(ctx).sortedWith(LanguageOrder.comparator)
            .filter { it.mLocale.substringBefore('_') in Romanized.NON_LATIN }
        val codes = languages.map { it.mLocale.substringBefore('_') }
        val current = ConvertPrefs.convertTo(ctx)
        val checked = BooleanArray(codes.size) { codes[it] in current }
        // The isolates keep a right-to-left name (Arabic, Urdu) from reordering the row.
        val names = languages.map { "\u2068${it.mAutonym}\u2069  ·  ${it.mEnglishName}" }.toTypedArray()
        convertToDialog?.dismiss()
        convertToDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.gemtype_convert_to)
            // [checked] is already updated when the listener runs; a fourth language is taken back at once.
            .setMultiChoiceItems(names, checked) { dialog, which, isChecked ->
                if (isChecked && checked.count { it } > Romanized.MAX_TARGETS) {
                    checked[which] = false
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(ctx, getString(R.string.gemtype_convert_to_max, Romanized.MAX_TARGETS), Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ConvertPrefs.setConvertTo(ctx, codes.filterIndexed { i, _ -> checked[i] })
                showConvertTo()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- saving --------------------------------------------------------------------------

    private fun saveModel(id: String) {
        val ctx = requireContext().applicationContext
        saveAsync { GemTypeSettings.setModel(ctx, id) }
    }

    /** Writes outlive this screen (the user may leave right after typing). */
    private fun saveAsync(write: () -> Unit) {
        GemType.appScope.launch(Dispatchers.IO) { write() }
    }

    companion object {
        private const val KEY_URL = "https://aistudio.google.com/apikey"
        private const val CUSTOM = "custom"
        private val OK_COLOR = Color.rgb(0x1B, 0x8A, 0x4B)
        private val ERROR_COLOR = Color.rgb(0xC0, 0x36, 0x2C)
    }
}
