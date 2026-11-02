// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/** Everything a request needs from the user's settings. */
data class GemtypeConfig(val apiKey: String, val model: String, val language: String)

/** Model and writing-language choices. Ids only here; the settings screen shows the labels. */
object Choices {
    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    const val QUALITY_MODEL = "gemini-3.8-flash"
    const val AUTO_LANGUAGE = "auto"

    /** Model choices shown in the picker; anything else is a custom id. */
    val MODELS = listOf(
        DEFAULT_MODEL to "Gemini 3.5 Flash-Lite (fast, default)",
        QUALITY_MODEL to "Gemini 3.8 Flash (higher quality, slower)",
    )

    /** Writing languages: the browser extension's list plus every language this keyboard types. */
    val LANGUAGES = listOf(
        AUTO_LANGUAGE,
        "English", "Spanish", "French", "German", "Portuguese", "Chinese", "Japanese",
        "Assamese", "Bengali", "Gujarati", "Hindi", "Kannada", "Kashmiri", "Malayalam",
        "Manipuri", "Maithili", "Marathi", "Nepali", "Odia", "Punjabi", "Sanskrit",
        "Santali", "Sinhala", "Tamil", "Telugu", "Urdu", "Arabic", "Burmese",
    )

    /**
     * Lowest thinking level the model accepts, for speed. Flash-Lite supports
     * MINIMAL; other Gemini 3 models start at LOW. Pre-3 models reject
     * thinkingLevel entirely, so send nothing for them.
     */
    fun thinkingLevel(model: String): String? = when {
        !model.startsWith("gemini-3") -> null
        model.contains("flash-lite") -> "MINIMAL"
        else -> "LOW"
    }

    /**
     * The proofreader needs reasoning to judge sentences: MEDIUM on every Gemini 3 model (Flash-Lite included).
     * On the 59-case eval MEDIUM caught 58 and changed nothing it shouldn't, in about 1.6 s; LOW caught 54-55
     * in about 0.9 s and sometimes wrote English words in Bangla script.
     */
    fun checkThinkingLevel(model: String): String? = if (model.startsWith("gemini-3")) "MEDIUM" else null
}

/** What a Gemini API key from Google AI Studio looks like. */
object GeminiKey {
    private const val PREFIX = "AIza"
    private const val LENGTH = 39

    /** True for text that is shaped like a Gemini key, so it can be tested without being asked. */
    fun looksLikeKey(text: String): Boolean = text.length == LENGTH && text.startsWith(PREFIX)
}
