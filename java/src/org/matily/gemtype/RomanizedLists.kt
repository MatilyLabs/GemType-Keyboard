// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import com.android.inputmethod.compat.PreferenceManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matily.gemtype.core.Romanized
import org.smc.inputmethod.indic.settings.Settings

/** The bundled romanized word lists (assets/gemtype/romanized), read once off the main thread and kept in memory. */
internal object RomanizedLists {
    private const val DIR = "gemtype/romanized"

    @Volatile private var lists: Map<String, Set<String>>? = null

    /** The lists if [load] has finished, else null. */
    fun peek(): Map<String, Set<String>>? = lists

    suspend fun load(context: Context): Map<String, Set<String>> =
        lists ?: withContext(Dispatchers.IO) {
            val byFile = HashMap<String, Set<String>>()
            Romanized.LISTS.associateWith { language ->
                val file = Romanized.listFile(language)
                byFile.getOrPut(file) {
                    context.assets.open("$DIR/$file").use { Romanized.parseList(it.reader().readText()) }
                }
            }.also { lists = it }
        }

    /** The languages of the enabled keyboard layouts that are written in a non-Latin script, in a stable order. */
    fun enabledNonLatinLanguages(context: Context): List<String> =
        Settings.readEnabledSubtypeKeys(PreferenceManagerCompat.getDeviceSharedPreferences(context))
            // A subtype key is "<locale>|<extra values>", e.g. "bn_IN|KeyboardLayoutSet=bengali".
            .map { it.substringBefore('|').substringBefore('_') }
            .filter { it in Romanized.NON_LATIN }.distinct().sorted()
}
