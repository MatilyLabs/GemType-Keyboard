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

/** The two-field request for romanized text, "another version" requests, and reading both answers. */
class RewriteProtocolTest {
    private fun body(action: Action, target: String? = "Bangla", avoid: List<String> = emptyList()) =
        GeminiProtocol.requestBody("tmr kotha", action, "auto", "MEDIUM", target, avoid)

    private fun systemOf(body: JSONObject) =
        body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")

    private fun gen(body: JSONObject) = body.getJSONObject("generationConfig")

    private fun answer(text: String) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))),
    ).toString()

    private val rewrites = Action.values().filter { it != Action.NATIVE }

    @Test fun everyActionButNativeGetsTheTwoFieldSchemaForRomanizedText() {
        for (action in rewrites) {
            val schema = gen(body(action)).getJSONObject("responseSchema")
            val ordering = schema.getJSONArray("propertyOrdering")
            assertEquals("$action", listOf("native", "rewritten"), (0 until ordering.length()).map { ordering.getString(it) })
            val required = schema.getJSONArray("required")
            assertEquals("$action", listOf("native", "rewritten"), (0 until required.length()).map { required.getString(it) })
            val props = schema.getJSONObject("properties")
            assertEquals("STRING", props.getJSONObject("native").getString("type"))
            assertEquals("STRING", props.getJSONObject("rewritten").getString("type"))
            assertEquals("application/json", gen(body(action)).getString("responseMimeType"))
            assertEquals("MEDIUM", gen(body(action)).getJSONObject("thinkingConfig").getString("thinkingLevel"))
        }
        assertEquals("TEXT:\ntmr kotha", body(Action.IMPROVE).getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun nativeScriptKeepsItsOwnRequestEvenWithALanguage() {
        val b = body(Action.NATIVE)
        assertFalse(gen(b).getJSONObject("responseSchema").has("propertyOrdering"))
        assertEquals(listOf("rewritten"), gen(b).getJSONObject("responseSchema").getJSONObject("properties").keys().asSequence().toList())
        assertTrue(systemOf(b).startsWith("The text is Bangla typed with English (Latin) letters, quickly and possibly with typos"))
    }

    @Test fun layeredPromptReadsTheTextFirstThenDoesTheAction() {
        val system = systemOf(body(Action.IMPROVE))
        assertTrue(system.startsWith("The text is Bangla typed with English (Latin) letters"))
        val native = system.indexOf("\"native\": first work out what the writer means")
        val rewritten = system.indexOf("\"rewritten\": then, based on that understanding, do this with the text: ")
        assertTrue(native in 0 until rewritten)
        assertTrue(system.contains("write it as correct, natural Bangla in its native script"))
        assertTrue(system.contains("Fix typos and short forms silently and read them as Bangla, never as English"))
        assertTrue(system.contains("\"tmr\" is তোমার, \"k\" is কে, \"vai\" is ভাই"))
        assertTrue(system.contains("Make the text clearer and more natural, at about the same length."))
        assertTrue(system.contains("fluent native speaker"))
        assertTrue(system.endsWith("Write only the text in each field — no preamble, no quotes, no markdown."))
        assertFalse(system.contains("{language}"))
        assertFalse(system.contains("Preserve the language"))
    }

    @Test fun otherActionsAnswerInLatinLettersAndEnglishAnswersInEnglish() {
        val latin = "Write it in Bangla using English (Latin) letters, the way the writer types it, not in the native script, and keep English words as they are."
        for (action in Action.REWRITES) assertTrue("$action", systemOf(body(action)).contains(latin))
        val english = systemOf(body(Action.ENGLISH))
        assertFalse(english.contains("using English (Latin) letters, the way the writer types it"))
        assertTrue(english.contains("do this with the text: Write what the writer means as natural, fluent English"))
        assertTrue(english.contains("Keep the meaning and tone, add nothing, and keep names, URLs, numbers and emoji."))
    }

    @Test fun everyRewriteTaskAppearsInTheLayeredPrompt() {
        for (action in Action.REWRITES) assertTrue("$action", systemOf(body(action)).contains(action.layeredTask))
        assertEquals(Action.REWRITES.map { it.prompt }, Action.REWRITES.map { it.layeredTask })
        assertEquals(listOf(Action.FIX, Action.IMPROVE, Action.SHORTEN, Action.FORMAL, Action.CASUAL), Action.REWRITES)
    }

    @Test fun shortFormExamplesFollowTheLanguage() {
        assertTrue(systemOf(body(Action.ENGLISH, "Hindi")).contains("\"mjhe\" is मुझे"))
        assertFalse(systemOf(body(Action.ENGLISH, "Hindi")).contains("তোমার"))
        // A language without examples gets none, and still reads cleanly.
        val tamil = systemOf(body(Action.ENGLISH, "Tamil"))
        assertTrue(tamil.contains("read them as Tamil, never as English. Add nothing."))
    }

    @Test fun layeredRequestsKeepTheActionsTemperature() {
        assertEquals(0.0, gen(body(Action.FIX)).getDouble("temperature"), 0.0)
        assertEquals(0.4, gen(body(Action.IMPROVE)).getDouble("temperature"), 0.0)
        assertEquals(0.4, gen(body(Action.ENGLISH)).getDouble("temperature"), 0.0)
    }

    @Test fun anotherVersionIsMoreRandomAndAsksForSomethingDifferent() {
        for (target in listOf(null, "Bangla")) for (action in Action.values()) {
            val b = body(action, target, listOf("Previous one."))
            assertEquals("$action $target", 0.9, gen(b).getDouble("temperature"), 0.0)
            val system = systemOf(b)
            assertTrue("$action $target", system.contains("Write a different version from this one: Previous one."))
            // Plain requests still end with the output rule; layered ones with the field rule.
            assertTrue("$action $target", system.contains("no preamble, no quotes, no markdown."))
        }
        assertFalse(systemOf(body(Action.IMPROVE)).contains("different version"))
    }

    @Test fun severalEarlierVersionsAreAllListed() {
        val system = systemOf(body(Action.CASUAL, null, listOf("One.", "Two.")))
        assertTrue(system.contains("Write a different version from each of these:\n- One.\n- Two.\n"))
        assertTrue(system.endsWith("Return only the resulting text — no preamble, no quotes, no markdown."))
    }

    @Test fun parsesBothFieldsAndToleratesAMissingNative() {
        val both = GeminiProtocol.parseRewrite(200, answer("{\"native\":\"তোমার সাথে\",\"rewritten\":\"With you.\"}"))
        assertEquals(Rewrite("With you.", "তোমার সাথে"), both)
        assertEquals(Rewrite("With you."), GeminiProtocol.parseRewrite(200, answer("{\"rewritten\":\"With you.\"}")))
        assertNull(GeminiProtocol.parseRewrite(200, answer("{\"native\":\"  \",\"rewritten\":\"With you.\"}")).readAs)
        assertEquals("With you.", GeminiProtocol.parseResponse(200, answer("{\"native\":\"x\",\"rewritten\":\"With you.\"}")))
    }

    @Test fun aMissingRewrittenIsAnEmptyResponse() {
        for (raw in listOf(answer("{\"native\":\"তোমার\"}"), answer("{\"native\":\"x\",\"rewritten\":\" \"}"), answer("not json"))) {
            try {
                GeminiProtocol.parseRewrite(200, raw)
                fail("expected an exception")
            } catch (e: GeminiException) {
                assertEquals(GeminiException.Kind.EMPTY_RESPONSE, e.kind)
            }
        }
    }

    @Test fun refineSendsTheLayeredRequestAndReturnsHowItWasRead() {
        val bodies = ArrayList<JSONObject>()
        val client = GeminiClient { _, _, raw ->
            bodies.add(JSONObject(raw))
            HttpResult(200, answer("{\"native\":\"তোমার সাথে আমার কোনো কথা নেই\",\"rewritten\":\"I have nothing to say to you.\"}"))
        }
        val config = GemtypeConfig("AIzaMOCK", Choices.DEFAULT_MODEL, Choices.AUTO_LANGUAGE)
        val result = runBlocking { client.refine("Tmr sathe amr kono kotha nai", Action.ENGLISH, config, "Bangla", avoid = listOf("Old.")) }
        assertEquals(Rewrite("I have nothing to say to you.", "তোমার সাথে আমার কোনো কথা নেই"), result)
        val sent = bodies.single()
        assertTrue(gen(sent).getJSONObject("responseSchema").has("propertyOrdering"))
        assertEquals(0.9, gen(sent).getDouble("temperature"), 0.0)
        assertEquals("MEDIUM", gen(sent).getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test fun strayLettersOfAnotherScriptAreSpotted() {
        // From the eval: Telugu "ంగ్" inside a Tamil and a Kannada word.
        assertTrue(GeminiProtocol.hasStrayScript("நீ மீட்டிంగ్ முடிஞ்சதும் கால் பண்ணு."))
        assertTrue(GeminiProtocol.hasStrayScript("ನೀವು ಮೀಟಿంగ್ ನೋಡಿಕೊಳ್ಳಿ."))
        assertFalse(GeminiProtocol.hasStrayScript("நீ மீட்டிங் முடிஞ்சதும் கால் பண்ணு."))
        assertFalse(GeminiProtocol.hasStrayScript("আমি আজ office যাচ্ছি না।"))
        assertFalse(GeminiProtocol.hasStrayScript("I have nothing to say to you."))
    }

    @Test fun aRewriteWithStrayLettersIsAskedForOnceMore() {
        val answers = ArrayDeque(listOf("நீ மீட்டிంగ్ முடிஞ்சதும் கால் பண்ணு.", "நீ மீட்டிங் முடிஞ்சதும் கால் பண்ணு."))
        var calls = 0
        val client = GeminiClient { _, _, _ ->
            calls++
            HttpResult(200, answer(JSONObject().put("rewritten", answers.removeFirst()).toString()))
        }
        val config = GemtypeConfig("AIzaMOCK", Choices.DEFAULT_MODEL, Choices.AUTO_LANGUAGE)
        val result = runBlocking { client.refine("நீ மீட்டிங் முடிஞ்சதும் கால் பண்ணு", Action.IMPROVE, config) }
        assertEquals("நீ மீட்டிங் முடிஞ்சதும் கால் பண்ணு.", result.text)
        assertEquals(2, calls)
    }
}
