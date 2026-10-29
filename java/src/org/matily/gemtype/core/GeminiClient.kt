// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import kotlinx.coroutines.delay

/**
 * Gemini calls for GemType. Same bring-your-own-key model as every other GemType
 * surface: requests go straight to Google's Gemini API, there is no GemType server.
 */
class GeminiClient(private val transport: Transport) {

    /** Rewrite [text] with [action]. Returns the rewritten string. */
    suspend fun refine(text: String, action: Action, config: GemtypeConfig): String {
        if (config.apiKey.isBlank()) throw GeminiException(GeminiException.Kind.NO_API_KEY)
        val level = Choices.thinkingLevel(config.model)
        return try {
            callWithRetry(text, action, config, level)
        } catch (e: GeminiException) {
            // A custom model id may not accept our thinking level; retry once without it.
            if (level != null && e.kind == GeminiException.Kind.API_KEY_ERROR &&
                e.message.orEmpty().contains("thinking", ignoreCase = true)
            ) call(text, action, config, null) else throw e
        }
    }

    /**
     * One tiny real request with the chosen model, for the "Test key" button.
     * Checks the key, the model id and our request shape together. Returns the
     * round-trip time in milliseconds.
     */
    suspend fun test(config: GemtypeConfig): Long {
        val start = System.nanoTime()
        refine("Ths is a test.", Action.FIX, config)
        return (System.nanoTime() - start) / 1_000_000
    }

    /** Google returns 5xx when a model is overloaded; one quick retry usually lands. */
    private suspend fun callWithRetry(text: String, action: Action, config: GemtypeConfig, level: String?): String =
        try {
            call(text, action, config, level)
        } catch (e: GeminiException) {
            if (e.kind != GeminiException.Kind.BUSY) throw e
            delay(1_500)
            call(text, action, config, level)
        }

    private suspend fun call(text: String, action: Action, config: GemtypeConfig, level: String?): String {
        val body = GeminiProtocol.requestBody(text, action, config.language, level).toString()
        val result = transport.post(config.model, config.apiKey, body)
        return GeminiProtocol.parseResponse(result.status, result.body)
    }
}
