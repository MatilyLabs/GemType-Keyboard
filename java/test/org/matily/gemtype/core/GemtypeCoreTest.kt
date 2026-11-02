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

    @Test fun rewritesThinkAtMediumOnGemini3Only() {
        assertEquals("MEDIUM", Choices.rewriteThinkingLevel(Choices.DEFAULT_MODEL))
        assertEquals("MEDIUM", Choices.rewriteThinkingLevel(Choices.QUALITY_MODEL))
        assertNull(Choices.rewriteThinkingLevel("gemini-2.0-flash"))
        assertNull(Choices.rewriteThinkingLevel("gemma-4"))
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
        assertTrue(system.startsWith("Fix the grammar, spelling and punctuation"))
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
        assertTrue(system.startsWith("The text is Bangla typed with English (Latin) letters, quickly and possibly with typos"))
        assertTrue(system.contains("Work out what the writer means, then write it as correct, natural Bangla in its native script"))
        assertTrue(system.contains("Fix mistakes silently."))
        assertTrue(system.contains("Do not add new content, change the meaning or tone, or translate it into another language."))
        assertTrue(system.contains("keep only names, brands, URLs, emails, numbers and emoji as they are."))
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
        val system = systemOf(GeminiProtocol.requestBody("ami kal ashbo na", Action.ENGLISH, "Hindi", "LOW"))
        assertEquals(
            "The text may be in any language or a mix, in any script including English letters (romanized), with typos and short chat forms. " +
                "Work out what the writer means, reading short forms in the writer's own language (a Banglish \"tmr\" is \"your\", not \"tomorrow\"), " +
                "then write it as natural, fluent English, the way a native speaker would say it in that kind of message. " +
                "Keep the meaning and tone, add nothing, and keep names, URLs, numbers and emoji. " +
                "Return only the resulting text — no preamble, no quotes, no markdown.",
            system,
        )
        assertEquals(0.4, GeminiProtocol.requestBody("x", Action.ENGLISH, "auto", null)
            .getJSONObject("generationConfig").getDouble("temperature"), 0.0)
    }

    @Test fun rewritePromptsSoundNaturalAndKeepTheLanguage() {
        for (action in Action.REWRITES) {
            val system = GeminiProtocol.systemPrompt(action, "auto")
            assertTrue("$action", system.contains("fluent native speaker"))
            assertTrue("$action", system.contains("Add nothing new"))
            assertTrue("$action", system.contains("Keep names, URLs, numbers, emoji and line breaks"))
            assertTrue("$action", system.contains("keep English words in text that mixes languages"))
            assertTrue("$action", system.contains("Preserve the language and script of the original text."))
            assertTrue("$action", system.endsWith("Return only the resulting text — no preamble, no quotes, no markdown."))
        }
        assertTrue(GeminiProtocol.systemPrompt(Action.IMPROVE, "auto").startsWith("Make the text clearer and more natural, at about the same length."))
        assertTrue(GeminiProtocol.systemPrompt(Action.FORMAL, "Hindi").contains("The text is in Hindi; keep it in that language and script."))
    }

    @Test fun withoutATargetEveryActionUsesTheSingleFieldSchema() {
        for (action in Action.values()) {
            val schema = GeminiProtocol.requestBody("x", action, "auto", null).getJSONObject("generationConfig").getJSONObject("responseSchema")
            assertEquals(listOf("rewritten"), schema.getJSONObject("properties").keys().asSequence().toList())
            assertFalse(schema.has("propertyOrdering"))
        }
    }

    @Test fun refinePassesTheTargetLanguageToGemini() {
        val bodies = ArrayList<JSONObject>()
        val client = GeminiClient { _, _, body ->
            bodies.add(JSONObject(body))
            HttpResult(200, ok("{\"rewritten\":\"আমি কাল আসবো না\"}"))
        }
        assertEquals("আমি কাল আসবো না", runBlocking { client.refine("ami kal ashbo na", Action.NATIVE, config, "Bangla") }.text)
        assertTrue(systemOf(bodies.single()).contains("natural Bangla in its native script"))
    }

    private fun ok(text: String) = JSONObject().put(
        "candidates",
        org.json.JSONArray().put(
            JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))),
        ),
    ).toString()

    @Test fun refineThinksAtTheRewriteLevelUnlessOverridden() {
        val bodies = ArrayList<JSONObject>()
        val client = GeminiClient { _, _, body ->
            bodies.add(JSONObject(body))
            HttpResult(200, ok("{\"rewritten\":\"Done.\"}"))
        }
        runBlocking {
            client.refine("x", Action.IMPROVE, config)
            client.refine("x", Action.IMPROVE, config, thinkingLevel = "HIGH")
            client.refine("x", Action.IMPROVE, config, thinkingLevel = null)
        }
        fun level(i: Int) = bodies[i].getJSONObject("generationConfig").optJSONObject("thinkingConfig")?.getString("thinkingLevel")
        assertEquals(listOf("MEDIUM", "HIGH", null), listOf(level(0), level(1), level(2)))
    }

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

    @Test fun garbageAnswersAreAnEmptyResponseNotACrash() {
        assertEquals(GeminiException.Kind.EMPTY_RESPONSE, kindOf(200, "<html>not json {{{"))
        assertEquals(GeminiException.Kind.EMPTY_RESPONSE, kindOf(200, ""))
        assertEquals(GeminiException.Kind.API_KEY_ERROR, kindOf(400, "<html>bad gateway</html>"))
        assertEquals(GeminiException.Kind.RATE_LIMITED, kindOf(429, "<html>slow down</html>"))
    }

    private val locationBody = "{\"error\":{\"code\":400,\"status\":\"FAILED_PRECONDITION\"," +
        "\"message\":\"User location is not supported for the API use.\"}}"

    @Test fun unsupportedLocationIsNotABadKey() {
        assertEquals(GeminiException.Kind.LOCATION_UNSUPPORTED, kindOf(400, locationBody))
        assertEquals(GeminiException.Kind.LOCATION_UNSUPPORTED, kindOf(403, locationBody.replace("location is", "LOCATION IS")))
        // Any other 400 stays a key problem.
        assertEquals(GeminiException.Kind.API_KEY_ERROR, kindOf(400, "{\"error\":{\"message\":\"API key not valid.\"}}"))
    }

    @Test fun locationMessageDependsOnTheVpn() {
        val e = GeminiException(GeminiException.Kind.LOCATION_UNSUPPORTED, "User location is not supported for the API use.")
        assertEquals("Gemini isn't available in your current location yet.", e.friendly(false))
        assertEquals(
            "Gemini isn't available through your VPN's location. Turn off the VPN (or pick another country) and try again.",
            e.friendly(true),
        )
        assertFalse(e.friendly(false).contains("key", ignoreCase = true))
        assertFalse(e.friendly(true).contains("key", ignoreCase = true))
        assertTrue(e.brief(true).contains("VPN"))
        assertFalse(e.brief(false).contains("VPN"))
    }

    @Test fun networkAndTimeoutErrorsHintAtTheVpnOnlyWhenOneIsActive() {
        val hint = " If you're using a VPN, try turning it off."
        for (kind in listOf(GeminiException.Kind.NETWORK, GeminiException.Kind.TIMEOUT)) {
            val e = GeminiException(kind)
            assertFalse(e.friendly(false).contains("VPN"))
            assertEquals(e.friendly(false) + hint, e.friendly(true))
            assertTrue(e.brief(true).contains("VPN"))
            assertFalse(e.brief(false).contains("VPN"))
        }
        // Other failures never blame the VPN.
        assertFalse(GeminiException(GeminiException.Kind.RATE_LIMITED).friendly(true).contains("VPN"))
        assertFalse(GeminiException(GeminiException.Kind.BUSY).friendly(true).contains("VPN"))
    }

    @Test fun theVpnCheckIsInjectable() {
        val saved = VpnState.isActive
        try {
            VpnState.isActive = { true }
            assertTrue(GeminiException(GeminiException.Kind.NETWORK).friendly.contains("VPN"))
            VpnState.isActive = { false }
            assertFalse(GeminiException(GeminiException.Kind.NETWORK).friendly.contains("VPN"))
        } finally {
            VpnState.isActive = saved
        }
    }

    @Test fun everyFailureHasAShortMessageForTheStrip() {
        for (vpn in listOf(false, true)) for (kind in GeminiException.Kind.values()) {
            val brief = GeminiException(kind, "detail").brief(vpn)
            assertTrue("$kind: '$brief'", brief.isNotBlank() && brief.length <= 46)
        }
        assertEquals("That API key isn't valid.",
            GeminiException(GeminiException.Kind.API_KEY_ERROR, "API key not valid. Please pass a valid API key.").brief)
        assertTrue(GeminiException(GeminiException.Kind.RATE_LIMITED).brief.contains("Rate limit"))
        assertTrue(GeminiException(GeminiException.Kind.NETWORK).brief.contains("Can't reach"))
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
        assertEquals("Done.", runBlocking { client.refine("x", Action.FIX, config) }.text)
        assertEquals(2, calls)
    }

    @Test fun refineRetriesWithoutThinkingWhenTheModelRejectsIt() {
        val bodies = ArrayList<JSONObject>()
        val client = GeminiClient { _, _, body ->
            bodies.add(JSONObject(body))
            if (bodies.size == 1) HttpResult(400, "{\"error\":{\"message\":\"Thinking level is not supported\"}}")
            else HttpResult(200, ok("{\"rewritten\":\"Done.\"}"))
        }
        assertEquals("Done.", runBlocking { client.refine("x", Action.FIX, config) }.text)
        assertTrue(bodies[0].getJSONObject("generationConfig").has("thinkingConfig"))
        assertFalse(bodies[1].getJSONObject("generationConfig").has("thinkingConfig"))
    }
}
