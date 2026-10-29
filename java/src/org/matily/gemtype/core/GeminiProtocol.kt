// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.json.JSONArray
import org.json.JSONObject

/** Building the generateContent request and reading its answer. No I/O here. */
object GeminiProtocol {
    private val REFINE_SCHEMA by lazy {
        JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("rewritten", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("rewritten"))
    }

    fun systemPrompt(action: Action, language: String): String {
        val lang = if (language == Choices.AUTO_LANGUAGE) {
            "Preserve the language of the original text."
        } else {
            "The text is in $language; keep it in that language."
        }
        return "${action.prompt} $lang Return only the resulting text — no preamble, no quotes, no markdown."
    }

    fun requestBody(text: String, action: Action, language: String, thinkingLevel: String?): JSONObject {
        val gen = JSONObject()
            .put("temperature", if (action == Action.FIX) 0 else 0.4)
            .put("responseMimeType", "application/json")
            .put("responseSchema", REFINE_SCHEMA)
        if (thinkingLevel != null) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "TEXT:\n$text")))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(action, language)))))
            .put("generationConfig", gen)
    }

    private fun errorMessage(raw: String, fallback: String): String =
        runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull() ?: fallback

    /** Map an HTTP status and body to the `rewritten` text, or throw the matching [GeminiException]. */
    fun parseResponse(status: Int, raw: String): String {
        when {
            status == 429 -> throw GeminiException(GeminiException.Kind.RATE_LIMITED)
            status == 400 || status == 403 ->
                throw GeminiException(GeminiException.Kind.API_KEY_ERROR, errorMessage(raw, "HTTP $status"))
            status == 404 ->
                throw GeminiException(GeminiException.Kind.MODEL_UNAVAILABLE, errorMessage(raw, "HTTP 404"))
            status in 500..599 -> throw GeminiException(GeminiException.Kind.BUSY, "HTTP $status")
            status !in 200..299 -> throw GeminiException(GeminiException.Kind.HTTP, "HTTP $status")
        }
        // First non-thought text part (thoughts are off, but be tolerant).
        val text = runCatching {
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).map { parts.getJSONObject(it) }
                .first { it.has("text") && !it.optBoolean("thought") }.getString("text")
        }.getOrNull() ?: throw GeminiException(GeminiException.Kind.EMPTY_RESPONSE)
        val rewritten = runCatching { JSONObject(text).optString("rewritten") }.getOrNull()
        if (rewritten.isNullOrBlank()) throw GeminiException(GeminiException.Kind.EMPTY_RESPONSE)
        return rewritten
    }
}
