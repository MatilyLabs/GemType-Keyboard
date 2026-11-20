// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiSuggesterTest {
    private fun s(text: String) = EmojiSuggester.suggest(text)

    @Test fun feelings() {
        assertEquals("😂", s("haha"))
        assertEquals("😂", s("Hahahaha"))
        assertEquals("😂", s("lmaooo"))
        assertEquals("😂", s("that was funny"))
        assertEquals("😢", s("I am crying"))
        assertEquals("❤️", s("loveeee"))
        assertEquals("🙏", s("Thank you"))
    }

    @Test fun longestPhraseWins() {
        assertEquals("😄", s("we had lots of fun"))
        assertEquals("🥰", s("I'm in love"))
        assertEquals("🌙", s("good night"))
        assertEquals("🎂", s("happy birthday"))
    }

    @Test fun finishedWordAndPunctuation() {
        assertEquals("😂", s("haha "))
        assertEquals("😂", s("haha!!"))
        assertNull(s("haha 😂"))
        assertNull(s("haha 😂 "))
    }

    @Test fun banglaAndHindi() {
        assertEquals("❤️", s("ami tomake valobashi"))
        assertEquals("🙏", s("dhonnobad"))
        assertEquals("😞", s("aj mon kharap"))
        assertEquals("❤️", s("আমি তোমাকে ভালোবাসি"))
        assertEquals("😄", s("bahut maza"))
        assertEquals("🙏", s("धन्यवाद"))
    }

    @Test fun everydayWordsHaveNone() {
        assertNull(s(""))
        assertNull(s("the"))
        assertNull(s("I will call you"))
        assertNull(s("ha"))
        assertNull(s("123"))
    }
}
