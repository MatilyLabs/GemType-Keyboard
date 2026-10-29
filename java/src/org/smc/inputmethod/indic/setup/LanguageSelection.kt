/*
 * Copyright 2026, Jishnu Mohan <jishnu7@gmail.com>
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

import android.content.Context
import android.os.Bundle
import android.os.LocaleList

import com.android.inputmethod.compat.PreferenceManagerCompat
import com.android.inputmethod.latin.R
import com.android.inputmethod.latin.RichInputMethodManager
import com.android.inputmethod.latin.common.LocaleUtils
import com.android.inputmethod.latin.utils.KeyboardLanguages
import com.android.inputmethod.latin.utils.KeyboardLanguages.Language
import com.android.inputmethod.latin.utils.KeyboardLanguages.Layout
import com.android.inputmethod.latin.utils.SubtypeLocaleUtils

import org.smc.inputmethod.indic.settings.Settings as AppSettings

/** The order of every language list: English, then Bangla, then Hindi, then the rest A to Z. */
internal object LanguageOrder {
    private val LEADING = listOf("en", "bn", "hi")

    val comparator: Comparator<Language> = compareBy<Language> {
        val index = LEADING.indexOf(it.mLocale.substringBefore('_'))
        if (index >= 0) index else LEADING.size
    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.mEnglishName }
}

/** One layout as the setup shows it: a name and, where we can say it plainly, what it is. */
internal class LayoutChoice(val layout: Layout, val name: String, val description: String?) {
    val key: String = SubtypeLocaleUtils.getSubtypeKey(layout.mSubtype)
}

/**
 * The language step's state: which languages the user picked, which layout each uses, and how to
 * commit that to the keyboard. A language that already had layouts enabled keeps them unless the
 * user picks a different one.
 */
internal class LanguageSelection(private val context: Context) {

    var loaded = false
        private set
    var languages: List<Language> = emptyList()
        private set
    val selectedLocales = LinkedHashSet<String>()

    private val initiallyEnabled = HashSet<String>()
    private val chosenKey = HashMap<String, String>()   // language locale -> subtype key
    private val changed = HashSet<String>()             // languages whose layout the user chose

    fun ensureLoaded() {
        if (loaded) {
            return
        }
        loaded = true
        RichInputMethodManager.init(context)
        languages = KeyboardLanguages.getLanguages(context).sortedWith(LanguageOrder.comparator)
        initiallyEnabled.addAll(
            AppSettings.readEnabledSubtypeKeys(
                PreferenceManagerCompat.getDeviceSharedPreferences(context)
            )
        )
        val phoneLanguages = phoneLanguageCodes()
        for (language in languages) {
            val choices = choicesFor(language)
            val enabled = choices.firstOrNull { initiallyEnabled.contains(it.key) }
            val code = codeOf(language)
            if (enabled != null || code == "en" || phoneLanguages.contains(code)) {
                selectedLocales.add(language.mLocale)
            }
            chosenKey[language.mLocale] = (enabled ?: choices.firstOrNull())?.key ?: continue
        }
    }

    private fun phoneLanguageCodes(): Set<String> {
        val locales = LocaleList.getDefault()
        return (0 until locales.size()).map { locales[it].language }.toSet()
    }

    private fun codeOf(language: Language): String = language.mLocale.substringBefore('_')

    /** The language's layouts as the setup lists them: the best first guess on top. */
    fun choicesFor(language: Language): List<LayoutChoice> {
        val choices = language.mLayouts.map { describe(language, it) }
        if (codeOf(language) != "bn") {
            return choices
        }
        // Bangla users overwhelmingly type with Avro, so it comes first.
        return choices.sortedBy { BANGLA_ORDER.indexOf(layoutId(it.layout)).let { i -> if (i < 0) BANGLA_ORDER.size else i } }
    }

    /** The transliteration scheme, or the layout set for layouts that are not transliterated. */
    private fun layoutId(layout: Layout): String =
        layout.mSubtype.getExtraValueOf(TRANSLITERATION_METHOD).takeUnless { it.isNullOrEmpty() }
            ?: SubtypeLocaleUtils.getKeyboardLayoutSetName(layout.mSubtype)

    fun chosenLayout(language: Language): String? = chosenKey[language.mLocale]

    fun isSelected(language: Language) = selectedLocales.contains(language.mLocale)

    fun setSelected(language: Language, selected: Boolean) {
        if (selected) selectedLocales.add(language.mLocale) else selectedLocales.remove(language.mLocale)
    }

    fun chooseLayout(language: Language, key: String) {
        chosenKey[language.mLocale] = key
        changed.add(language.mLocale)
    }

    /** Keep the picks across a rotation or the system recreating the screen. */
    fun save(out: Bundle) {
        out.putStringArrayList(STATE_SELECTED, ArrayList(selectedLocales))
        out.putStringArrayList(STATE_CHOSEN_LOCALES, ArrayList(chosenKey.keys))
        out.putStringArrayList(STATE_CHOSEN_KEYS, ArrayList(chosenKey.values))
        out.putStringArrayList(STATE_CHANGED, ArrayList(changed))
    }

    fun restore(state: Bundle) {
        val selected = state.getStringArrayList(STATE_SELECTED) ?: return
        selectedLocales.clear()
        selectedLocales.addAll(selected)
        val locales = state.getStringArrayList(STATE_CHOSEN_LOCALES).orEmpty()
        val keys = state.getStringArrayList(STATE_CHOSEN_KEYS).orEmpty()
        locales.zip(keys).forEach { (locale, key) -> chosenKey[locale] = key }
        changed.addAll(state.getStringArrayList(STATE_CHANGED).orEmpty())
    }

    /** Enable the chosen layout of every selected language and nothing for the others. */
    fun commit() {
        val keys = HashSet<String>()
        for (language in languages) {
            if (!isSelected(language)) continue
            val choices = choicesFor(language)
            if (!changed.contains(language.mLocale)) {
                val kept = choices.filter { initiallyEnabled.contains(it.key) }
                if (kept.isNotEmpty()) {
                    kept.mapTo(keys) { it.key }
                    continue
                }
            }
            (chosenKey[language.mLocale] ?: choices.firstOrNull()?.key)?.let { keys.add(it) }
        }
        RichInputMethodManager.init(context)
        RichInputMethodManager.getInstance().setEnabledSubtypeKeys(keys)
    }

    /** The language codes that need a downloadable pack; English ships with the app. */
    fun selectedLanguageCodes(): List<String> =
        languages.filter { isSelected(it) }
            .map { LocaleUtils.constructLocaleFromString(it.mLocale).language }
            .filter { it != "en" }
            .distinct()

    private fun describe(language: Language, layout: Layout): LayoutChoice {
        val subtype = layout.mSubtype
        val translit = subtype.getExtraValueOf(TRANSLITERATION_METHOD)
        val layoutSet = SubtypeLocaleUtils.getKeyboardLayoutSetName(subtype)
        val isBangla = codeOf(language) == "bn"
        return when {
            isBangla && layoutSet == "bengali_probhat" ->
                LayoutChoice(layout, layout.mName, context.getString(R.string.gemtype_layout_bn_probhat))
            // The plain "Standard" Bangla layout is the compact, small-keyboard one.
            isBangla && layoutSet == "bengali" && translit.isNullOrEmpty() ->
                LayoutChoice(layout, context.getString(R.string.gemtype_layout_compact), null)
            translit != null && translit.startsWith("varnam-") ->
                LayoutChoice(layout, layout.mName, context.getString(R.string.gemtype_layout_translit_predictive))
            !translit.isNullOrEmpty() ->
                LayoutChoice(layout, layout.mName, context.getString(R.string.gemtype_layout_translit))
            else -> LayoutChoice(layout, layout.mName, null)
        }
    }

    companion object {
        private const val TRANSLITERATION_METHOD = "TransliterationMethod"
        private const val STATE_SELECTED = "selected"
        private const val STATE_CHOSEN_LOCALES = "chosen_locales"
        private const val STATE_CHOSEN_KEYS = "chosen_keys"
        private const val STATE_CHANGED = "changed"
        private val BANGLA_ORDER =
            listOf("bn-avro", "varnam-bn", "bengali_probhat", "bengali_inscript", "bengali")

        fun title(language: Language): String = language.mAutonym

        /** The English name, shown under the native one; null when they are the same word. */
        fun subtitle(language: Language): String? =
            language.mEnglishName.takeUnless { it.equals(language.mAutonym, ignoreCase = true) }
    }
}
