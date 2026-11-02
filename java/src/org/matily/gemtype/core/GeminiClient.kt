// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import kotlinx.coroutines.delay

/**
 * Gemini calls for GemType. Same bring-your-own-key model as every other GemType
 * surface: requests go straight to Google's Gemini API, there is no GemType server.
 */
class GeminiClient(private val transport: Transport) {

    /**
     * Rewrite [text] with [action]. [target] is the language's English name: [Action.NATIVE] writes the text in it,
     * and for the other actions it says the text is romanized [target], so the answer also says how it was read.
     * [avoid] are earlier results: ask for another version. [thinkingLevel] overrides the app's choice (null: none).
     */
    suspend fun refine(
        text: String, action: Action, config: GemtypeConfig, target: String? = null,
        thinkingLevel: String? = Choices.rewriteThinkingLevel(config.model), avoid: List<String> = emptyList(),
    ): Rewrite {
        requireKey(config)
        return withFallbacks(thinkingLevel) { level ->
            val body = GeminiProtocol.requestBody(text, action, config.language, level, target, avoid).toString()
            val result = transport.post(config.model, config.apiKey, body)
            GeminiProtocol.parseRewrite(result.status, result.body)
        }
    }

    /**
     * Proofread [text]: the mistakes Gemini found, located in [text] and in text order (empty when it
     * is correct). [keyboardLanguage] is the English name of the language being typed, a hint for auto language.
     */
    suspend fun check(
        text: String, config: GemtypeConfig, keyboardLanguage: String? = null,
        thinkingLevel: String? = Choices.checkThinkingLevel(config.model),
    ): List<Correction> {
        requireKey(config)
        if (text.isBlank()) return emptyList()
        return withFallbacks(thinkingLevel) { level ->
            val body = CheckProtocol.requestBody(text, config.language, keyboardLanguage, level).toString()
            val result = transport.post(config.model, config.apiKey, body)
            CheckProtocol.parseResponse(result.status, result.body, text)
        }
    }

    /**
     * One tiny real request with the chosen model, for the "Test key" button.
     * Checks the key, the model id and our request shape together. Returns the
     * round-trip time in milliseconds.
     */
    suspend fun test(config: GemtypeConfig): Long {
        val start = System.nanoTime()
        refine("Ths is a test.", Action.FIX, config, thinkingLevel = Choices.thinkingLevel(config.model))
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun requireKey(config: GemtypeConfig) {
        if (config.apiKey.isBlank()) throw GeminiException(GeminiException.Kind.NO_API_KEY)
    }

    /**
     * Runs [call] with thinking [level]. Google returns 5xx when a model is overloaded; one quick
     * retry usually lands. A custom model id may not accept our thinking level; retry once without it.
     */
    private suspend fun <T> withFallbacks(level: String?, call: suspend (String?) -> T): T {
        suspend fun retryingBusy(l: String?): T =
            try {
                call(l)
            } catch (e: GeminiException) {
                if (e.kind != GeminiException.Kind.BUSY) throw e
                delay(1_500)
                call(l)
            }
        return try {
            retryingBusy(level)
        } catch (e: GeminiException) {
            if (level != null && e.kind == GeminiException.Kind.API_KEY_ERROR &&
                e.message.orEmpty().contains("thinking", ignoreCase = true)
            ) call(null) else throw e
        }
    }
}
