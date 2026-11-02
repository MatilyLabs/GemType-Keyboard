// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CheckProtocolTest {
    private fun systemOf(body: JSONObject) =
        body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")

    private fun prompt(language: String = "auto", keyboard: String? = null) = CheckProtocol.systemPrompt(language, keyboard)

    private fun item(original: String, replacement: String, why: String = "Because.", type: String = "grammar") =
        JSONObject().put("original", original).put("replacement", replacement).put("explanation", why).put("type", type)

    private fun answer(vararg items: JSONObject): String = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put(
                "content",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", JSONObject().put("corrections", JSONArray(items.toList())).toString())),
                ),
            ),
        ),
    ).toString()

    private fun parse(text: String, vararg items: JSONObject) = CheckProtocol.parseResponse(200, answer(*items), text)

    // ---- prompt -------------------------------------------------------------------------

    @Test fun promptKeepsTheExtensionsRules() {
        val p = prompt()
        assertTrue(p.contains("Find EVERY error"))
        for (category in listOf("- grammar:", "- spelling:", "- punctuation:", "- style:")) assertTrue(category, p.contains(category))
        assertTrue(p.contains("RUN-ON SENTENCES"))
        assertTrue(p.contains("MINIMAL edit"))
        assertTrue(p.contains("EXACT substring copied verbatim"))
        assertTrue(p.contains("must not overlap"))
        assertTrue(p.contains("empty corrections array"))
        assertTrue(p.contains("Do NOT flag"))
    }

    @Test fun promptCoversPhoneTypingAndMixedText() {
        val p = prompt()
        for (word in listOf("lowercase \"i\"", "dont, im, cant, wont", "doubled words", "double spaces", "missing space after punctuation")) {
            assertTrue(word, p.contains(word))
        }
        assertTrue(p.contains("never translate English words"))
        assertTrue(p.contains("never flag a word only because it is English"))
        assertTrue(p.contains("Banglish, Hinglish"))
        assertTrue(p.contains("never convert the script"))
    }

    @Test fun explanationsAreShortPlainEnglish() {
        for (language in listOf("auto", "Bangla", "Hindi")) {
            val p = prompt(language)
            assertTrue(p.contains("in English, under 12 words, in plain words"))
        }
    }

    @Test fun autoLanguageWithAKeyboardHintsAtTheKeyboardAndAddsItsRules() {
        val p = prompt("auto", "Bangla")
        assertTrue(p.contains("Detect the language of the text"))
        assertTrue(p.contains("The user is typing on a Bangla keyboard; the text is probably Bangla, English, or a mix."))
        assertTrue(p.contains("Bangla Academy"))
        assertTrue(p.contains("guruchandali"))
        assertTrue(p.contains("ি/ী"))
        assertTrue(p.contains("\"।\", not \".\""))
        assertTrue(p.contains("English: keep"))
        // Only the likely languages, to keep the prompt small.
        assertFalse(p.contains("Hindi:"))
        assertFalse(p.contains("Urdu:"))
        assertFalse(p.contains("Nepali:"))
    }

    @Test fun anEnglishKeyboardAddsNoSecondLanguageBlock() {
        val p = prompt("auto", "English")
        assertTrue(p.contains("The user is typing on an English keyboard; the text is probably English or a mix."))
        assertTrue(p.contains("English: keep"))
        assertFalse(p.contains("Bangla Academy"))
    }

    @Test fun unknownKeyboardOrLanguageGetsEveryLikelyBlock() {
        val p = prompt("auto", null)
        assertFalse(p.contains("is typing on a"))
        for (name in listOf("English:", "Bangla:", "Hindi:", "Urdu:", "Nepali:")) assertTrue(name, p.contains(name))
        assertTrue(prompt("auto", "  ").contains("Bangla:"))
    }

    @Test fun aKnownLanguageGetsOnlyItsOwnBlockAndNoKeyboardHint() {
        val hindi = prompt("Hindi", "Bangla")
        assertTrue(hindi.contains("The text is in Hindi."))
        assertTrue(hindi.contains("gender and number agreement"))
        assertTrue(hindi.contains("chandrabindu vs anusvara"))
        assertFalse(hindi.contains("Detect the language"))
        assertFalse(hindi.contains("typing on"))
        assertFalse(hindi.contains("Bangla Academy"))
        // The picker calls it Bengali.
        assertTrue(prompt("Bengali").contains("Bangla Academy"))
        assertTrue(prompt("Urdu").contains("۔ ، ؟"))
        assertTrue(prompt("Nepali").contains("Nepali: a sentence ends with"))
    }

    @Test fun otherLanguagesGetTheGenericBlock() {
        val p = prompt("Tamil")
        assertTrue(p.contains("Tamil: apply that language's own punctuation and standard spelling."))
        assertTrue(p.contains("The text is in Tamil."))
        assertTrue(prompt("auto", "Tamil").contains("Tamil: apply that language's own punctuation"))
    }

    @Test fun promptStaysSmall() {
        assertTrue(prompt("auto", null).length < 5_000)
    }

    // ---- request ------------------------------------------------------------------------

    @Test fun requestBodyAsksForJsonCorrectionsAtTemperatureZero() {
        val body = CheckProtocol.requestBody("i dont know", "auto", "English", "LOW")
        val text = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("TEXT TO CHECK:\ni dont know", text)
        assertEquals(CheckProtocol.systemPrompt("auto", "English"), systemOf(body))
        val gen = body.getJSONObject("generationConfig")
        assertEquals(0.0, gen.getDouble("temperature"), 0.0)
        assertEquals("application/json", gen.getString("responseMimeType"))
        assertEquals("LOW", gen.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val schema = gen.getJSONObject("responseSchema")
        assertEquals("corrections", schema.getJSONArray("required").getString(0))
        val items = schema.getJSONObject("properties").getJSONObject("corrections").getJSONObject("items")
        assertEquals(
            setOf("original", "replacement", "explanation", "type"),
            items.getJSONObject("properties").keys().asSequence().toSet(),
        )
        assertEquals(4, items.getJSONArray("required").length())
        val types = items.getJSONObject("properties").getJSONObject("type").getJSONArray("enum")
        assertEquals(listOf("grammar", "spelling", "punctuation", "style"), (0 until types.length()).map { types.getString(it) })
    }

    @Test fun requestBodyWithoutAThinkingLevelHasNoThinkingConfig() {
        assertFalse(CheckProtocol.requestBody("x", "auto", null, null).getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun checkThinkingLevelIsLowOnGemini3AndNothingBefore() {
        assertEquals("LOW", Choices.checkThinkingLevel(Choices.DEFAULT_MODEL))
        assertEquals("LOW", Choices.checkThinkingLevel(Choices.QUALITY_MODEL))
        assertEquals("LOW", Choices.checkThinkingLevel("gemini-3.8-pro"))
        assertNull(Choices.checkThinkingLevel("gemini-2.0-flash"))
        assertNull(Choices.checkThinkingLevel("gemma-4"))
        // Rewrites keep their own level.
        assertEquals("MINIMAL", Choices.thinkingLevel(Choices.DEFAULT_MODEL))
    }

    // ---- parse, filter, locate ----------------------------------------------------------

    @Test fun parsesAndLocatesCorrectionsInTextOrder() {
        val text = "i dont think we can make it friday sorry"
        val found = parse(
            text,
            item("friday sorry", "Friday, sorry.", "Missing comma.", "punctuation"),
            item("dont", "don't", "Missing apostrophe.", "spelling"),
            item("i", "I", "Capital I.", "grammar"),
        )
        assertEquals(listOf("i", "dont", "friday sorry"), found.map { it.original })
        assertEquals(listOf(0, 2, 28), found.map { it.start })
        assertEquals(listOf(1, 6, 40), found.map { it.end })
        assertEquals(
            listOf(Correction.Type.GRAMMAR, Correction.Type.SPELLING, Correction.Type.PUNCTUATION),
            found.map { it.type },
        )
        assertEquals("Missing apostrophe.", found[1].explanation)
        assertEquals("I don't think we can make it Friday, sorry.", Corrections.apply(text, found))
    }

    @Test fun anEmptyListMeansNoMistakes() {
        assertEquals(emptyList<Correction>(), parse("All good here."))
    }

    @Test fun filtersWhatTheExtensionFilters() {
        val text = "Ths is a test"
        val raw = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "{\"corrections\":[null, 5, {\"original\":\"Ths\"}, {\"original\":3,\"replacement\":\"x\"}," +
                                    "{\"original\":\"is\",\"replacement\":\"is\"}, {\"original\":\"missing\",\"replacement\":\"x\"}," +
                                    "{\"original\":\"\",\"replacement\":\"x\"}, {\"original\":\"Ths\",\"replacement\":\"This\"}]}",
                            ),
                        ),
                    ),
                ),
            ),
        ).toString()
        val found = CheckProtocol.parseResponse(200, raw, text)
        assertEquals(listOf("Ths"), found.map { it.original })
        // Missing explanation and type are tolerated.
        assertEquals("", found[0].explanation)
        assertEquals(Correction.Type.STYLE, found[0].type)
    }

    @Test fun unknownTypeNamesCountAsStyleAndCaseIsIgnored() {
        assertEquals(Correction.Type.SPELLING, Correction.Type.parse("Spelling"))
        assertEquals(Correction.Type.STYLE, Correction.Type.parse("vibes"))
        assertEquals(Correction.Type.STYLE, Correction.Type.parse(null))
    }

    @Test fun repeatedOriginalsMapToSuccessiveOccurrences() {
        val text = "teh cat and teh dog"
        val found = parse(text, item("teh", "the"), item("teh", "the"), item("teh", "the"))
        assertEquals(listOf(0, 12), found.map { it.start })
        assertEquals("the cat and the dog", Corrections.apply(text, found))
    }

    @Test fun overlappingCorrectionsKeepTheFirstAndSkipTheLoser() {
        val text = "we goed there"
        val found = parse(text, item("goed there", "went there"), item("goed", "went"))
        assertEquals(listOf("goed there"), found.map { it.original })
        assertEquals("we went there", Corrections.apply(text, found))
    }

    @Test fun anOverlappingMatchMovesOnToALaterOccurrence() {
        val text = "ab ab"
        val found = CheckProtocol.locate(text, listOf(Correction("ab ab", "X", "", Correction.Type.STYLE), Correction("ab", "Y", "", Correction.Type.STYLE)))
        assertEquals(listOf("ab ab"), found.map { it.original })
        val two = CheckProtocol.locate("ab ab c", listOf(Correction("ab", "Y", "", Correction.Type.STYLE), Correction("ab", "Z", "", Correction.Type.STYLE)))
        assertEquals(listOf(0, 3), two.map { it.start })
    }

    @Test fun aShortOriginalLandsOnTheWholeWordNotInsideAnother() {
        // The eval caught "i" -> "I" turning "invite" into "Invite".
        val text = "Thanks for the invite, i will try my best to come."
        val found = parse(text, item("i", "I"))
        assertEquals(1, found.size)
        assertEquals(text.indexOf(", i ") + 2, found[0].start)
        assertEquals("Thanks for the invite, I will try my best to come.", Corrections.apply(text, found))
    }

    @Test fun aFragmentWithNoWholeWordMatchIsStillLocated() {
        val found = parse("I recieved it.", item("recieved", "received"))
        assertEquals(1, found.size)
        assertEquals(2, found[0].start)
    }

    @Test fun locatesBanglaAndEmojiByUtf16Position() {
        val text = "আমি 😀 কাল আসবো না."
        val found = parse(text, item("না.", "না।", "Sentence ends with ।", "punctuation"))
        assertEquals(text.indexOf("না."), found.single().start)
        assertEquals("আমি 😀 কাল আসবো না।", Corrections.apply(text, found))
    }

    // ---- apply --------------------------------------------------------------------------

    @Test fun applyChangesOnlyTheAcceptedCorrections() {
        val text = "i dont think we can make it friday sorry"
        val all = parse(text, item("i", "I"), item("dont", "don't"), item("it friday", "it on Friday"))
        assertEquals("I don't think we can make it on Friday sorry", Corrections.apply(text, all))
        assertEquals("i don't think we can make it friday sorry", Corrections.apply(text, listOf(all[1])))
        assertEquals(text, Corrections.apply(text, emptyList()))
    }

    @Test fun applyKeepsLaterPositionsValidWhenLengthsChange() {
        val text = "aa bb cc"
        val c = CheckProtocol.locate(text, listOf(
            Correction("aa", "a", "", Correction.Type.STYLE),
            Correction("bb", "bbbbbb", "", Correction.Type.STYLE),
            Correction("cc", "c", "", Correction.Type.STYLE),
        ))
        assertEquals("a bbbbbb c", Corrections.apply(text, c))
        // Order of the list does not matter.
        assertEquals("a bbbbbb c", Corrections.apply(text, c.reversed()))
    }

    @Test fun applySkipsCorrectionsThatNoLongerFitTheText() {
        val text = "teh cat"
        val c = parse(text, item("teh", "the"))
        assertEquals("the cat", Corrections.apply(text, c))
        // The user kept typing: the span moved, so nothing is changed instead of the wrong characters.
        assertEquals("xx teh cat", Corrections.apply("xx teh cat", c))
        assertEquals("te", Corrections.apply("te", c))
        assertEquals("teh cat", Corrections.apply(text, listOf(Correction("teh", "the", "", Correction.Type.SPELLING))))
    }

    // ---- errors and client --------------------------------------------------------------

    private fun kindOf(status: Int, raw: String): GeminiException.Kind =
        try {
            CheckProtocol.parseResponse(status, raw, "text")
            fail("expected an exception")
            throw AssertionError()
        } catch (e: GeminiException) {
            e.kind
        }

    @Test fun mapsFailuresLikeRewrites() {
        val location = "{\"error\":{\"message\":\"User location is not supported for the API use.\"}}"
        assertEquals(GeminiException.Kind.RATE_LIMITED, kindOf(429, ""))
        assertEquals(GeminiException.Kind.BUSY, kindOf(503, ""))
        assertEquals(GeminiException.Kind.MODEL_UNAVAILABLE, kindOf(404, "{\"error\":{\"message\":\"nope\"}}"))
        assertEquals(GeminiException.Kind.API_KEY_ERROR, kindOf(400, "{\"error\":{\"message\":\"API key not valid.\"}}"))
        assertEquals(GeminiException.Kind.LOCATION_UNSUPPORTED, kindOf(400, location))
        assertEquals(GeminiException.Kind.HTTP, kindOf(418, ""))
        // Identical to the rewrite path for the same bodies.
        for ((status, raw) in listOf(429 to "", 503 to "", 400 to location, 404 to "{}", 418 to "")) {
            val rewrite = try { GeminiProtocol.parseResponse(status, raw); null } catch (e: GeminiException) { e.kind to e.message }
            val check = try { CheckProtocol.parseResponse(status, raw, "t"); null } catch (e: GeminiException) { e.kind to e.message }
            assertEquals(rewrite, check)
        }
    }

    @Test fun answersWithoutACorrectionsListAreAnEmptyResponse() {
        assertEquals(GeminiException.Kind.EMPTY_RESPONSE, kindOf(200, "{}"))
        assertEquals(GeminiException.Kind.EMPTY_RESPONSE, kindOf(200, "<html>not json {{{"))
        val rewriteShaped = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", "{\"rewritten\":\"x\"}")))))).toString()
        assertEquals(GeminiException.Kind.EMPTY_RESPONSE, kindOf(200, rewriteShaped))
    }

    private val config = GemtypeConfig("AIzaMOCKkey0000000000000000000000000000", Choices.DEFAULT_MODEL, Choices.AUTO_LANGUAGE)

    @Test fun clientCheckSendsTheProofreaderRequestAndReturnsLocatedCorrections() {
        val bodies = ArrayList<JSONObject>()
        val client = GeminiClient { _, _, body ->
            bodies.add(JSONObject(body))
            HttpResult(200, answer(item("dont", "don't", "Missing apostrophe.", "spelling")))
        }
        val found = runBlocking { client.check("we dont know", config, "English") }
        assertEquals(listOf(3), found.map { it.start })
        val gen = bodies.single().getJSONObject("generationConfig")
        assertEquals("LOW", gen.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertTrue(gen.getJSONObject("responseSchema").getJSONObject("properties").has("corrections"))
        assertTrue(systemOf(bodies.single()).contains("typing on an English keyboard"))
    }

    @Test fun clientCheckNeedsAKeyAndSkipsBlankText() {
        val client = GeminiClient { _, _, _ -> error("must not be called") }
        try {
            runBlocking { client.check("x", config.copy(apiKey = " ")) }
            fail("expected an exception")
        } catch (e: GeminiException) {
            assertEquals(GeminiException.Kind.NO_API_KEY, e.kind)
        }
        assertEquals(emptyList<Correction>(), runBlocking { client.check("  \n", config) })
    }

    @Test fun clientCheckRetriesOnceWhenBusyAndWithoutThinkingWhenRejected() {
        var calls = 0
        val busy = GeminiClient { _, _, _ -> if (++calls == 1) HttpResult(503, "") else HttpResult(200, answer()) }
        assertEquals(emptyList<Correction>(), runBlocking { busy.check("some text here", config) })
        assertEquals(2, calls)

        val bodies = ArrayList<JSONObject>()
        val picky = GeminiClient { _, _, body ->
            bodies.add(JSONObject(body))
            if (bodies.size == 1) HttpResult(400, "{\"error\":{\"message\":\"Thinking level is not supported\"}}") else HttpResult(200, answer())
        }
        runBlocking { picky.check("some text here", config) }
        assertTrue(bodies[0].getJSONObject("generationConfig").has("thinkingConfig"))
        assertFalse(bodies[1].getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun clientCheckSurfacesRateLimitsAndKeyProblems() {
        for ((status, kind) in listOf(429 to GeminiException.Kind.RATE_LIMITED, 400 to GeminiException.Kind.API_KEY_ERROR)) {
            val client = GeminiClient { _, _, _ -> HttpResult(status, "{\"error\":{\"message\":\"x\"}}") }
            try {
                runBlocking { client.check("some text here", config) }
                fail("expected an exception")
            } catch (e: GeminiException) {
                assertEquals(kind, e.kind)
            }
        }
    }
}
