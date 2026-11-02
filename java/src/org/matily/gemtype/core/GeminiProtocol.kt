// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.json.JSONArray
import org.json.JSONObject

/** A rewrite, and for romanized text how it was understood: [readAs] is the writer's text in its native script. */
data class Rewrite(val text: String, val readAs: String? = null)

/** Building the generateContent request and reading its answer. No I/O here. */
object GeminiProtocol {
    private val REFINE_SCHEMA by lazy {
        JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("rewritten", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("rewritten"))
    }

    /** Romanized text: `native` (what the writer means, in the language's script) is generated first, then `rewritten`. */
    private val LAYERED_SCHEMA by lazy {
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("native", JSONObject().put("type", "STRING"))
                    .put("rewritten", JSONObject().put("type", "STRING")),
            )
            .put("required", JSONArray().put("native").put("rewritten"))
            .put("propertyOrdering", JSONArray().put("native").put("rewritten"))
    }

    /** Short forms the model tends to read as English, per language name, as examples for the first step. */
    private val SHORT_FORMS = mapOf(
        "Bangla" to "\"tmr\" is তোমার, \"k\" is কে, \"vai\" is ভাই",
        "Bengali" to "\"tmr\" is তোমার, \"k\" is কে, \"vai\" is ভাই",
        "Hindi" to "\"mjhe\" is मुझे, \"nhi\" is नहीं, \"kr\" is कर",
    )

    /**
     * The request is "layered" when the text is romanized [target] (the language's English name) and the
     * action is not [Action.NATIVE]: the model first writes what the writer means in [target]'s script, then does [action].
     */
    private fun layered(action: Action, target: String?) = target != null && action != Action.NATIVE

    /**
     * [target] is the language's English name: [Action.NATIVE] writes the text in it; every other action
     * reads the text as romanized [target] first (see [layered]). [avoid] are earlier results to write something different from.
     */
    fun systemPrompt(action: Action, language: String, target: String? = null, avoid: List<String> = emptyList()): String {
        val lang = when {
            action.changesLanguage -> ""
            language == Choices.AUTO_LANGUAGE -> " Preserve the language and script of the original text."
            else -> " The text is in $language; keep it in that language and script."
        }
        val different = when (avoid.size) {
            0 -> ""
            1 -> " Write a different version from this one: ${avoid[0]}"
            else -> " Write a different version from each of these:\n" + avoid.joinToString("\n") { "- $it" } + "\n"
        }
        if (layered(action, target)) return layeredPrompt(action, target!!) + different
        return "${action.prompt(target)}$lang$different Return only the resulting text — no preamble, no quotes, no markdown."
    }

    private fun layeredPrompt(action: Action, target: String): String {
        val shortForms = SHORT_FORMS[target]?.let { " (for example, $it)" }.orEmpty()
        val output = if (action == Action.ENGLISH) "" else
            " Write it in $target using English (Latin) letters, the way the writer types it, not in the native script, and keep English words as they are. " +
                "Use correct punctuation with English marks: commas, question marks, and a \".\" at the end of each sentence."
        return "The text is $target typed with English (Latin) letters, quickly, with typos, short forms and English words mixed in. " +
            "Fill the two fields in order. " +
            "\"native\": first work out what the writer means, and write it as correct, natural $target in its native script, with proper spelling, grammar and punctuation (its own full stop, such as \"।\"). " +
            "Fix typos and short forms silently and read them as $target, never as English$shortForms. " +
            "Add nothing. Keep names, brands, URLs, numbers, emoji and words normally written in English. " +
            "\"rewritten\": then, based on that understanding, do this with the text: ${action.layeredTask}$output " +
            "Write only the text in each field — no preamble, no quotes, no markdown."
    }

    /** [avoid] non-empty asks for another version of the same action: more randomness, and the instruction not to repeat. */
    fun requestBody(
        text: String, action: Action, language: String, thinkingLevel: String?, target: String? = null,
        avoid: List<String> = emptyList(),
    ): JSONObject {
        val temperature = when {
            avoid.isNotEmpty() -> 0.9
            action == Action.FIX || action == Action.NATIVE -> 0.0
            else -> 0.4
        }
        val gen = JSONObject()
            .put("temperature", temperature)
            .put("responseMimeType", "application/json")
            .put("responseSchema", if (layered(action, target)) LAYERED_SCHEMA else REFINE_SCHEMA)
        if (thinkingLevel != null) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "TEXT:\n$text")))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(action, language, target, avoid)))))
            .put("generationConfig", gen)
    }

    private fun errorMessage(raw: String, fallback: String): String =
        runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull() ?: fallback

    /**
     * Map an HTTP status and body to the answer's text (the JSON string the model wrote), or throw
     * the matching [GeminiException]. Shared by every request kind.
     */
    fun answerText(status: Int, raw: String): String {
        when {
            status == 429 -> throw GeminiException(GeminiException.Kind.RATE_LIMITED)
            // Google answers 400 FAILED_PRECONDITION from regions the API isn't offered in (VPNs get this a lot).
            (status == 400 || status == 403) && errorMessage(raw, raw).contains("location is not supported", ignoreCase = true) ->
                throw GeminiException(GeminiException.Kind.LOCATION_UNSUPPORTED, errorMessage(raw, "HTTP $status"))
            status == 400 || status == 403 ->
                throw GeminiException(GeminiException.Kind.API_KEY_ERROR, errorMessage(raw, "HTTP $status"))
            status == 404 ->
                throw GeminiException(GeminiException.Kind.MODEL_UNAVAILABLE, errorMessage(raw, "HTTP 404"))
            status in 500..599 -> throw GeminiException(GeminiException.Kind.BUSY, "HTTP $status")
            status !in 200..299 -> throw GeminiException(GeminiException.Kind.HTTP, "HTTP $status")
        }
        // First non-thought text part (thoughts are off, but be tolerant).
        return runCatching {
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).map { parts.getJSONObject(it) }
                .first { it.has("text") && !it.optBoolean("thought") }.getString("text")
        }.getOrNull() ?: throw GeminiException(GeminiException.Kind.EMPTY_RESPONSE)
    }

    /** Map an HTTP status and body to the rewrite (and, for romanized text, how it was read), or throw the matching [GeminiException]. */
    fun parseRewrite(status: Int, raw: String): Rewrite {
        val text = answerText(status, raw)
        val json = runCatching { JSONObject(text) }.getOrNull()
        val rewritten = json?.optString("rewritten")
        if (rewritten.isNullOrBlank()) throw GeminiException(GeminiException.Kind.EMPTY_RESPONSE)
        // A missing `native` is fine: the rewrite is still good.
        return Rewrite(rewritten, json.optString("native").trim().ifBlank { null })
    }

    /** Like [parseRewrite], for callers that only want the text. */
    fun parseResponse(status: Int, raw: String): String = parseRewrite(status, raw).text
}
