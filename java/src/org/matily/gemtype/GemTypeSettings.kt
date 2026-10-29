// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

// GemType settings, stored only on this device. The API key lives in
// EncryptedSharedPreferences (AES-256, key held by the Android Keystore), never in
// the keyboard's ordinary SharedPreferences, and is excluded from backups
// (see gemtype_backup_rules.xml). androidx.security-crypto is deprecated upstream
// but still the standard, working wrapper for this; swapping it for a hand-rolled
// Keystore cipher later only needs this file to change.

package org.matily.gemtype

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.matily.gemtype.core.Choices
import org.matily.gemtype.core.GemtypeConfig
import java.io.IOException
import java.security.GeneralSecurityException

object GemTypeSettings {
    const val DEFAULT_MODEL = Choices.DEFAULT_MODEL
    const val QUALITY_MODEL = Choices.QUALITY_MODEL
    const val AUTO_LANGUAGE = Choices.AUTO_LANGUAGE
    val MODELS = Choices.MODELS
    val LANGUAGES = Choices.LANGUAGES

    private const val FILE = "gemtype_secure_prefs"
    private const val K_API_KEY = "apiKey"
    private const val K_MODEL = "model"
    private const val K_LANGUAGE = "language"

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: open(context.applicationContext).also { prefs = it }
        }

    @Suppress("DEPRECATION")
    private fun open(context: Context): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: GeneralSecurityException) {
            recreate(context, ::create)
        } catch (e: IOException) {
            recreate(context, ::create)
        }
    }

    /** Keystore key lost (e.g. data restored onto a new device): start fresh rather than crash. */
    private fun recreate(context: Context, create: () -> SharedPreferences): SharedPreferences {
        context.deleteSharedPreferences(FILE)
        return create()
    }

    fun load(context: Context): GemtypeConfig {
        val p = prefs(context)
        return GemtypeConfig(
            apiKey = p.getString(K_API_KEY, "").orEmpty(),
            model = p.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            language = p.getString(K_LANGUAGE, null) ?: AUTO_LANGUAGE,
        )
    }

    fun setApiKey(context: Context, apiKey: String) =
        prefs(context).edit { putString(K_API_KEY, apiKey.trim()) }

    fun setModel(context: Context, model: String) =
        prefs(context).edit { putString(K_MODEL, model.trim().ifBlank { DEFAULT_MODEL }) }

    fun setLanguage(context: Context, language: String) =
        prefs(context).edit { putString(K_LANGUAGE, language) }
}
