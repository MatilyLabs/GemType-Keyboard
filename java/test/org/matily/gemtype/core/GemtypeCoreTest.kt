// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GemtypeCoreTest {
    @Test fun thinkingLevelIsLowestEachModelSupports() {
        assertEquals("MINIMAL", Choices.thinkingLevel(Choices.DEFAULT_MODEL))
        assertEquals("LOW", Choices.thinkingLevel(Choices.QUALITY_MODEL))
        assertEquals("LOW", Choices.thinkingLevel("gemini-3.8-pro"))
        // Pre-3 models reject thinkingLevel outright.
        assertNull(Choices.thinkingLevel("gemini-2.0-flash"))
        assertNull(Choices.thinkingLevel("gemma-4"))
    }

    @Test fun geminiKeysAreRecognisedByShape() {
        assertTrue(GeminiKey.looksLikeKey("AIzaMOCKkey0000000000000000000000000000"))
        assertFalse(GeminiKey.looksLikeKey("AIza"))
        assertFalse(GeminiKey.looksLikeKey("bad-key"))
        assertFalse(GeminiKey.looksLikeKey("XIzaMOCKkey0000000000000000000000000000"))
        assertFalse(GeminiKey.looksLikeKey("AIzaMOCKkey00000000000000000000000000000"))
    }

    @Test fun offeredModelsExcludeRetiringOnes() {
        val ids = Choices.MODELS.map { it.first }
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"), ids)
        assertTrue(ids.none { it.startsWith("gemini-2.5") || it == "gemini-3.1-flash-lite" })
    }

    @Test fun languagesCoverEveryKeyboardLanguage() {
        val keyboard = listOf(
            "Assamese", "Bengali", "Gujarati", "Hindi", "Kannada", "Kashmiri", "Malayalam", "Manipuri",
            "Maithili", "Marathi", "Nepali", "Odia", "Punjabi", "Sanskrit", "Santali", "Sinhala",
            "Tamil", "Telugu", "Urdu", "Arabic", "Burmese",
        )
        assertTrue(Choices.LANGUAGES.containsAll(keyboard))
        assertEquals(Choices.AUTO_LANGUAGE, Choices.LANGUAGES.first())
        assertEquals(Choices.LANGUAGES.size, Choices.LANGUAGES.toSet().size)
    }

    // ---- word diff ----------------------------------------------------------------------

    private fun WordDiff.Kind.pick(segments: List<WordDiff.Segment>) =
        segments.filter { it.kind == this }.map { it.text.trim() }

    @Test fun wordDiffMarksOnlyChangedWords() {
        val segments = WordDiff.diff("i dont think so", "I don't think so")
        assertEquals(listOf("i dont"), WordDiff.Kind.REMOVED.pick(segments))
        assertEquals(listOf("I don't"), WordDiff.Kind.ADDED.pick(segments))
        assertEquals(listOf("think so"), WordDiff.Kind.KEPT.pick(segments))
    }

    @Test fun wordDiffKeptAndAddedRebuildTheFixedText() {
        val original = "Ths is  a tst\nof the  diff."
        val fixed = "This is a test\nof the diff."
        val segments = WordDiff.diff(original, fixed)
        val rebuilt = segments.filter { it.kind != WordDiff.Kind.REMOVED }.joinToString("") { it.text }
        assertEquals(fixed, rebuilt)
        assertEquals(listOf("Ths", "tst"), WordDiff.Kind.REMOVED.pick(segments).flatMap { it.split(" ") }.filter { it.isNotEmpty() })
    }

    @Test fun wordDiffOfIdenticalTextIsAllKept() {
        val segments = WordDiff.diff("Nothing to fix here.", "Nothing to fix here.")
        assertEquals(1, segments.size)
        assertEquals(WordDiff.Kind.KEPT, segments[0].kind)
    }

    @Test fun wordDiffFallsBackToPlainTextForHugeInput() {
        val huge = (1..2000).joinToString(" ") { "w$it" }
        val segments = WordDiff.diff(huge, "$huge more")
        assertEquals(listOf(WordDiff.Segment(WordDiff.Kind.KEPT, "$huge more")), segments)
    }

    // ---- request and response -----------------------------------------------------------

    @Test fun requestBodyUsesTheActionPromptAndThinkingLevel() {
        val body = GeminiProtocol.requestBody("hello", Action.FIX, "auto", "MINIMAL")
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.startsWith("Fix all grammar"))
        assertTrue(system.contains("Preserve the language"))
        val gen = body.getJSONObject("generationConfig")
        assertEquals("MINIMAL", gen.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertEquals(0.0, gen.getDouble("temperature"), 0.0)
        assertEquals("application/json", gen.getString("responseMimeType"))
        val text = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("TEXT:\nhello", text)
    }

    @Test fun requestBodyWithoutThinkingAndWithLanguage() {
        val body = GeminiProtocol.requestBody("x", Action.SHORTEN, "Hindi", null)
        assertFalse(body.getJSONObject("generationConfig").has("thinkingConfig"))
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.contains("The text is in Hindi"))
    }

    // ---- native script / English actions ---------------------------------------------------

    private fun systemOf(body: JSONObject) =
        body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")

    @Test fun nativeScriptPromptNamesTheTargetLanguage() {
        val body = GeminiProtocol.requestBody("ami kal ashbo na", Action.NATIVE, "auto", "MINIMAL", "Bangla")
        val system = systemOf(body)
        assertTrue(system.startsWith("The text is Bangla written with English (Latin) letters, possibly mixed with English words."))
        assertTrue(system.contains("Rewrite it in Bangla using its native script with correct, natural spelling and punctuation."))
        assertTrue(system.contains("Do not translate it into another language or change its meaning."))
        assertTrue(system.contains("Keep names, brands, URLs, numbers, emoji and words normally written in English as they are."))
        assertTrue(system.endsWith("Return only the resulting text — no preamble, no quotes, no markdown."))
        assertFalse(system.contains("{language}"))
        // It changes the script on purpose: no "keep the language" line.
        assertFalse(system.contains("Preserve the language"))
        assertFalse(system.contains("keep it in that language"))
        assertFalse(GeminiProtocol.systemPrompt(Action.NATIVE, "Hindi", "Urdu").contains("The text is in Hindi"))
        // Same schema, low thinking level and plain-text input as the other actions.
        val gen = body.getJSONObject("generationConfig")
        assertEquals("MINIMAL", gen.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertEquals("application/json", gen.getString("responseMimeType"))
        assertEquals("rewritten", gen.getJSONObject("responseSchema").getJSONArray("required").getString(0))
        assertEquals(0.0, gen.getDouble("temperature"), 0.0)
        val text = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("TEXT:\nami kal ashbo na", text)
    }

    @Test fun translateToEnglishPrompt() {
        val system = systemOf(GeminiProtocol.requestBody("ami kal ashbo na", Action.ENGLISH, "Hindi", "LOW", "ignored"))
        assertEquals(
            "Translate the text into natural, fluent English. Keep names, URLs, numbers and emoji. " +
                "Return only the resulting text — no preamble, no quotes, no markdown.",
            system,
        )
        assertEquals(0.4, GeminiProtocol.requestBody("x", Action.ENGLISH, "auto", null)
            .getJSONObject("generationConfig").getDouble("temperature"), 0.0)
    }

    @Test fun otherActionsAreUnchangedByTheTargetParameter() {
        assertEquals(
            GeminiProtocol.systemPrompt(Action.FIX, "auto"),
            GeminiProtocol.systemPrompt(Action.FIX, "auto", "Bangla"),
        )
        assertEquals(listOf(Action.FIX, Action.IMPROVE, Action.SHORTEN, Action.FORMAL, Action.CASUAL), Action.REWRITES)
    }

    @Test fun refinePassesTheTargetLanguageToGemini() {
        val bodies = ArrayList<JSONObject>()
        val client = GeminiClient { _, _, body ->
            bodies.add(JSONObject(body))
            HttpResult(200, ok("{\"rewritten\":\"আমি কাল আসবো না\"}"))
        }
        assertEquals("আমি কাল আসবো না", runBlocking { client.refine("ami kal ashbo na", Action.NATIVE, config, "Bangla") })
        assertTrue(systemOf(bodies.single()).contains("Rewrite it in Bangla"))
    }

    private fun ok(text: String) = JSONObject().put(
        "candidates",
        org.json.JSONArray().put(
            JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))),
        ),
    ).toString()

    @Test fun parsesTheRewrittenText() {
        val raw = ok(JSONObject().put("rewritten", "Fixed.").toString())
        assertEquals("Fixed.", GeminiProtocol.parseResponse(200, raw))
    }

    private fun kindOf(status: Int, raw: String): GeminiException.Kind =
        try {
            GeminiProtocol.parseResponse(status, raw)
            fail("expected an exception")
            throw AssertionError()
        } catch (e: GeminiException) {
            e.kind
        }

    @Test fun mapsFailuresToFriendlyKinds() {
        assertEquals(GeminiException.Kind.RATE_LIMITED, kindOf(429, ""))
        assertEquals(GeminiException.Kind.BUSY, kindOf(503, ""))
        assertEquals(GeminiException.Kind.MODEL_UNAVAILABLE, kindOf(404, "{\"error\":{\"message\":\"nope\"}}"))
        assertEquals(GeminiException.Kind.API_KEY_ERROR, kindOf(400, "{\"error\":{\"message\":\"API key not valid.\"}}"))
        assertEquals(GeminiException.Kind.HTTP, kindOf(418, ""))
        assertEquals(GeminiException.Kind.EMPTY_RESPONSE, kindOf(200, "{}"))
        assertEquals(GeminiException.Kind.EMPTY_RESPONSE, kindOf(200, ok("{\"rewritten\":\"  \"}")))
        val invalid = GeminiException(GeminiException.Kind.API_KEY_ERROR, "API key not valid. Please pass a valid API key.")
        assertTrue(invalid.friendly.contains("aistudio.google.com"))
    }

    // ---- client -------------------------------------------------------------------------

    private val config = GemtypeConfig("k", Choices.DEFAULT_MODEL, Choices.AUTO_LANGUAGE)

    @Test fun refineRequiresAKey() {
        val client = GeminiClient { _, _, _ -> error("must not be called") }
        try {
            runBlocking { client.refine("x", Action.FIX, config.copy(apiKey = " ")) }
            fail("expected an exception")
        } catch (e: GeminiException) {
            assertEquals(GeminiException.Kind.NO_API_KEY, e.kind)
        }
    }

    @Test fun refineRetriesOnceWhenBusy() {
        var calls = 0
        val client = GeminiClient { _, _, _ ->
            calls++
            if (calls == 1) HttpResult(503, "") else HttpResult(200, ok("{\"rewritten\":\"Done.\"}"))
        }
        assertEquals("Done.", runBlocking { client.refine("x", Action.FIX, config) })
        assertEquals(2, calls)
    }

    @Test fun refineRetriesWithoutThinkingWhenTheModelRejectsIt() {
        val bodies = ArrayList<JSONObject>()
        val client = GeminiClient { _, _, body ->
            bodies.add(JSONObject(body))
            if (bodies.size == 1) HttpResult(400, "{\"error\":{\"message\":\"Thinking level is not supported\"}}")
            else HttpResult(200, ok("{\"rewritten\":\"Done.\"}"))
        }
        assertEquals("Done.", runBlocking { client.refine("x", Action.FIX, config) })
        assertTrue(bodies[0].getJSONObject("generationConfig").has("thinkingConfig"))
        assertFalse(bodies[1].getJSONObject("generationConfig").has("thinkingConfig"))
    }
}
