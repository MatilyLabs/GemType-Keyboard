// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCheckTest {
    private val text = "i dont think we can make it friday"

    private fun fix(original: String, replacement: String, start: Int = -1) =
        Correction(original, replacement, "Because.", Correction.Type.GRAMMAR, start, if (start < 0) -1 else start + original.length)

    private fun found() = CheckProtocol.locate(text, listOf(fix("i", "I"), fix("dont", "don't"), fix("friday", "Friday")))

    // ---- gating -------------------------------------------------------------------------

    @Test fun `counts words in any script and ignores bare numbers`() {
        assertEquals(3, LiveCheck.wordCount("one two three"))
        assertEquals(3, LiveCheck.wordCount("আমি ভাত খাই"))
        assertEquals(2, LiveCheck.wordCount("one 12 two  "))
        assertEquals(0, LiveCheck.wordCount("  ... "))
    }

    @Test fun `checks text of three words`() {
        val state = LiveCheckState()
        assertSame(LiveCheck.Decision.Skip, state.decide("two words", 0))
        assertSame(LiveCheck.Decision.Check, state.decide("now three words", 0))
    }

    @Test fun `does not ask again for the same text, but does for changed text`() {
        val state = LiveCheckState()
        state.succeed(state.start("a b c"), "a b c", emptyList())
        assertSame(LiveCheck.Decision.Skip, state.decide("a b c", 10))
        assertSame(LiveCheck.Decision.Check, state.decide("a b c d", 10))
    }

    @Test fun `one request in flight at a time`() {
        val state = LiveCheckState()
        val id = state.start("a b c")
        assertSame(LiveCheck.Decision.Skip, state.decide("a b c d", 0))
        state.succeed(id, "a b c", emptyList())
        assertSame(LiveCheck.Decision.Check, state.decide("a b c d", 0))
    }

    @Test fun `an error pauses checks for a minute and the same text is not retried`() {
        val state = LiveCheckState()
        state.fail(state.start("a b c"), 1_000)
        assertSame(LiveCheck.Decision.Skip, state.decide("a b c", 2_000))
        val later = state.decide("a b c d", 2_000) as LiveCheck.Decision.Later
        assertEquals(LiveCheck.ERROR_PAUSE_MS - 1_000, later.afterMs)
        assertSame(LiveCheck.Decision.Check, state.decide("a b c d", 1_000 + LiveCheck.ERROR_PAUSE_MS))
    }

    @Test fun `the error pause outlives a field change`() {
        val state = LiveCheckState()
        state.fail(state.start("a b c"), 0)
        state.reset()
        assertTrue(state.decide("x y z", 5_000) is LiveCheck.Decision.Later)
    }

    // ---- stale answers and the cache ----------------------------------------------------

    @Test fun `an answer after a reset is dropped and does not free the new request`() {
        val state = LiveCheckState()
        val old = state.start("a b c")
        state.reset()
        val fresh = state.start("x y z")
        assertFalse(state.succeed(old, "a b c", found()))
        assertTrue(state.corrections.isEmpty())
        assertSame(LiveCheck.Decision.Skip, state.decide("x y z w", 0)) // the new request is still out
        assertTrue(state.succeed(fresh, "x y z", emptyList()))
    }

    @Test fun `a late failure from an old request changes nothing`() {
        val state = LiveCheckState()
        val old = state.start("a b c")
        state.reset()
        state.fail(old, 0)
        assertSame(LiveCheck.Decision.Check, state.decide("x y z", 1))
    }

    @Test fun `caches the answer for exactly the text that was checked`() {
        val state = LiveCheckState()
        assertNull(state.cached(text))
        state.succeed(state.start(text), text, found())
        assertEquals(3, state.cached(text)!!.size)
        assertNull(state.cached("$text!"))
        state.succeed(state.start("a b c"), "a b c", emptyList())
        assertEquals(emptyList<Correction>(), state.cached("a b c")) // "no mistakes" is an answer too
    }

    @Test fun `reset forgets the answer`() {
        val state = LiveCheckState()
        state.succeed(state.start(text), text, found())
        state.reset()
        assertNull(state.cached(text))
        assertTrue(state.corrections.isEmpty())
    }

    @Test fun `the badge follows the text as it is edited`() {
        val state = LiveCheckState()
        state.succeed(state.start(text), text, found())
        assertEquals(3, state.refresh(text).size)
        // The user fixes "dont" by hand and adds more text at the end.
        assertEquals(listOf("i", "friday"), state.refresh("i don't think we can make it friday ok").map { it.original })
        // Back to the checked text: all three again.
        assertEquals(3, state.refresh(text).size)
    }

    // ---- relocating ---------------------------------------------------------------------

    @Test fun `corrections move with text inserted before them`() {
        val moved = LiveCheck.relocate("well, $text", found())
        assertEquals(listOf("i", "dont", "friday"), moved.map { it.original })
        val c = moved.first { it.original == "friday" }
        assertEquals("friday", "well, $text".substring(c.start, c.end))
    }

    @Test fun `a short original is not found inside another word`() {
        // "i" occurs in "think" and "it", but only the standalone one counts.
        assertEquals(emptyList<Correction>(), LiveCheck.relocate("we think it is fine", listOf(fix("i", "I"))))
        val moved = LiveCheck.relocate("then i think", listOf(fix("i", "I", start = 0)))
        assertEquals(5, moved.single().start)
    }

    @Test fun `relocated corrections never overlap`() {
        val moved = LiveCheck.relocate("dont dont", listOf(fix("dont", "don't"), fix("dont", "do not")))
        assertEquals(listOf(0, 5), moved.map { it.start })
    }

    @Test fun `relocated corrections apply cleanly`() {
        val edited = "well, $text"
        assertEquals("well, I don't think we can make it Friday", Corrections.apply(edited, LiveCheck.relocate(edited, found())))
    }

    @Test fun `punctuation edges need no word boundary`() {
        val moved = LiveCheck.relocate("wait.. what", listOf(fix("..", "...")))
        assertEquals(4, moved.single().start)
    }

    // ---- chip ---------------------------------------------------------------------------

    @Test fun `knows when the cursor is inside a word`() {
        assertTrue(LiveCheck.isMidWord("hello wor"))
        assertTrue(LiveCheck.isMidWord("আমি ভা"))
        assertTrue(LiveCheck.isMidWord("আমি ভাতি")) // ends with a vowel sign (a combining mark)
        assertTrue(LiveCheck.isMidWord("room 12"))
        assertFalse(LiveCheck.isMidWord("hello "))
        assertFalse(LiveCheck.isMidWord("hello."))
        assertFalse(LiveCheck.isMidWord("hello\n"))
        assertFalse(LiveCheck.isMidWord(""))
    }

    @Test fun `chip label shows old arrow new`() {
        val label = LiveCheck.chipLabel(fix("tmk", "to make"))
        assertTrue(label.contains("tmk"))
        assertTrue(label.contains("→"))
        assertTrue(label.contains("to make"))
        assertTrue(label.indexOf("tmk") < label.indexOf("to make"))
    }

    private fun assertSame(expected: Any, actual: Any) = org.junit.Assert.assertSame(expected, actual)
}
