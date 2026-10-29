// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.content.Context

import org.smc.inputmethod.indic.languagepack.LanguagePackDownloadManager
import org.smc.inputmethod.indic.languagepack.LanguagePackDownloadManager.Pack

/**
 * Starts the language-pack downloads for the languages picked in the setup and leaves them to
 * finish in the background: the setup no longer waits on them. The download manager keeps
 * polling and installs each pack on its own; nothing here holds an activity.
 */
internal object LanguagePackPrefetch : LanguagePackDownloadManager.Listener {
    private var manager: LanguagePackDownloadManager? = null
    private var wanted: Set<String> = emptySet()

    fun start(context: Context, languageCodes: List<String>) {
        if (languageCodes.isEmpty()) {
            return
        }
        wanted = languageCodes.toSet()
        val downloads = manager ?: LanguagePackDownloadManager(context.applicationContext)
            .also { it.setListener(this); manager = it }
        downloads.loadIndex()  // the packs are requested in onIndexLoaded
    }

    override fun onIndexLoaded(schemes: List<Pack>) {
        val downloads = manager ?: return
        for (pack in schemes) {
            if (pack.lang in wanted) {
                downloads.ensureDownloaded(pack)
            }
        }
    }

    override fun onProgress(lang: String, percent: Int) = Unit
    override fun onInstalled(lang: String) = Unit
    override fun onError(lang: String?, message: String?) = Unit
}
