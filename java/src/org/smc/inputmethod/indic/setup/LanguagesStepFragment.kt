// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.fragment.app.Fragment

import com.android.inputmethod.latin.R
import com.android.inputmethod.latin.utils.KeyboardLanguages.Language
import com.android.inputmethod.latin.utils.TextDrawable
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton

import kotlin.math.roundToInt

/**
 * Step 3: pick the languages. English and the phone's language start selected. A selected
 * language with more than one layout shows a short list under it to choose how to type it.
 */
internal class LanguagesStepFragment : Fragment() {

    private lateinit var selection: LanguageSelection
    private lateinit var list: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.gemtype_setup_languages, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selection = LanguageSelection(requireContext())
        selection.ensureLoaded()
        savedInstanceState?.let { selection.restore(it) }
        list = view.findViewById(R.id.languages_list)
        for (language in selection.languages) {
            addLanguage(language)
        }
        view.findViewById<View>(R.id.languages_continue).setOnClickListener { onContinue() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::selection.isInitialized) selection.save(outState)
    }

    private fun onContinue() {
        if (selection.selectedLocales.isEmpty()) {
            Toast.makeText(requireContext(), R.string.gemtype_setup_pick_a_language, Toast.LENGTH_SHORT).show()
            return
        }
        selection.commit()
        // Packs download in the background; the next steps do not wait for them.
        LanguagePackPrefetch.start(requireContext(), selection.selectedLanguageCodes())
        (requireActivity() as SetupNavigator).goNext()
    }

    private fun addLanguage(language: Language) {
        val row = layoutInflater.inflate(R.layout.gemtype_setup_language_row, list, false)
        row.findViewById<ImageView>(R.id.language_glyph).setImageDrawable(glyph(language))
        row.findViewById<TextView>(R.id.language_title).text = LanguageSelection.title(language)
        val subtitle = row.findViewById<TextView>(R.id.language_subtitle)
        val english = LanguageSelection.subtitle(language)
        if (english != null) subtitle.text = english else subtitle.visibility = View.GONE
        val check = row.findViewById<MaterialCheckBox>(R.id.language_check)
        // Every row shares these ids, so the framework must not restore their state by id; the
        // picks are restored from the selection instead.
        check.isSaveEnabled = false
        check.isChecked = selection.isSelected(language)
        list.addView(row)

        val choices = selection.choicesFor(language)
        val group = if (choices.size > 1) {
            layoutInflater.inflate(R.layout.gemtype_setup_layout_group, list, false).also {
                fillLayouts(it.findViewById(R.id.layout_options), language)
                it.visibility = if (check.isChecked) View.VISIBLE else View.GONE
                list.addView(it)
            }
        } else {
            null
        }
        row.setOnClickListener {
            val selected = !check.isChecked
            check.isChecked = selected
            selection.setSelected(language, selected)
            group?.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    private fun fillLayouts(options: LinearLayout, language: Language) {
        val radios = ArrayList<Pair<String, MaterialRadioButton>>()
        for (choice in selection.choicesFor(language)) {
            val option = layoutInflater.inflate(R.layout.gemtype_setup_layout_option, options, false)
            option.findViewById<TextView>(R.id.option_name).text = choice.name
            val description = option.findViewById<TextView>(R.id.option_description)
            if (choice.description != null) description.text = choice.description
            else description.visibility = View.GONE
            val radio = option.findViewById<MaterialRadioButton>(R.id.option_radio)
            radio.isSaveEnabled = false
            radio.isChecked = choice.key == selection.chosenLayout(language)
            radios.add(choice.key to radio)
            option.setOnClickListener {
                selection.chooseLayout(language, choice.key)
                for ((key, other) in radios) other.isChecked = key == choice.key
            }
            options.addView(option)
        }
    }

    private fun glyph(language: Language): TextDrawable {
        val color = TypedValue()
        requireContext().theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, color, true)
        val size = (40 * resources.displayMetrics.density).roundToInt()
        return TextDrawable(language.mGlyph, color.data, 0 /* no background */, size)
    }
}
