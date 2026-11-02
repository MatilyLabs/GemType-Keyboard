// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The romanized pill for the Indian-subcontinent and Arabic languages beyond Bangla and Hindi: realistic chat
 * sentences typed with English letters must be detected with the bundled word lists in assets/gemtype/romanized,
 * alone and next to another "Convert to" language, and plain English must never be.
 */
class RomanizedLanguagesTest {
    private val dir = File("assets/gemtype/romanized")
    private val lists: Map<String, Set<String>> =
        Romanized.LISTS.associateWith { Romanized.parseList(File(dir, Romanized.listFile(it)).readText()) }

    // Stand-in for the keyboard's English dictionary: everyday English, including the English words that
    // romanized chat is full of ("office", "meeting", "call", "free").
    private val englishWords = setOf(
        "i", "you", "he", "she", "we", "they", "it", "me", "my", "your", "us", "the", "a", "an", "and", "or", "but", "so", "if",
        "is", "are", "am", "was", "were", "be", "been", "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
        "to", "of", "in", "on", "at", "for", "with", "from", "by", "up", "out", "not", "no", "yes", "ok", "okay", "hi", "hey", "hello",
        "this", "that", "there", "here", "what", "when", "where", "who", "why", "how", "now", "then", "today", "tomorrow", "tonight",
        "later", "soon", "come", "go", "get", "see", "let", "know", "want", "need", "send", "call", "wait", "meet", "make", "take",
        "thanks", "thank", "please", "sorry", "bro", "dude", "man", "guys", "free", "busy", "late", "early", "office", "meeting",
        "work", "home", "house", "time", "day", "night", "morning", "evening", "dinner", "lunch", "food", "coffee", "tea", "phone",
        "message", "email", "project", "report", "friday", "monday", "minutes", "minute", "way", "on", "all", "very", "just", "too",
        "good", "great", "fine", "well", "back", "again", "love", "like", "think", "tell", "say", "help", "running", "coming",
        "i'm", "i'll", "don't", "it's", "let's", "you're", "we're", "can't", "won't", "there's", "that's", "what's", "how's",
    )
    private val english: (String) -> Boolean = { it in englishWords }

    private fun detect(text: String, vararg languages: String) =
        Romanized.detect(text, languages.toList(), lists::get, english).map { it.language }

    /** Realistic chat sentences per language, typed with chat spellings and short forms. */
    private val sentences = mapOf(
        "ur" to listOf("kia hal hai? aaj raat khana kahan khayen gy", "mujhe bht bhook lgi hai yaar, jaldi aao"),
        "ne" to listOf("ma bholi ghar jaanchu, timi kta chau? khana khayau", "ma ahile bazar ma chu, timilai k chahinchha bhana"),
        "ta" to listOf("naan inniku office ku varala, udambu sari ila", "nee enga irukka? naan call panren, konjam wait pannu"),
        "te" to listOf("nenu repu office ki ranu, naku baga ledu", "ela unnav? repu evening free ga unnava"),
        "mr" to listOf("mi udya office la yeu shakat nhi, tabyet theek nahi", "kay kartoys? udya sandhyakali free ahes ka"),
        "gu" to listOf("hu kale ofis nahi aavi shaku, tabiyat saari nathi", "kem cho? aaje raate jamva kya jaishu"),
        "pa" to listOf("main kl ghar aunga, tusi vi aa jao", "tusi kithe ho? assi tuhadi udeek kar rahe aan"),
        "ml" to listOf("njn nale office il varilla, sugam illa", "ninakku sugam ano? ippo evideya? njan ivide waiting aanu"),
        "kn" to listOf("nanu nale office ge baralla, mai hushaar illa", "yelli iddiya? naanu ninna jothe matadbeku"),
        "or" to listOf("mu aji office jauni, sarira bhal nahin", "tume kemiti achha? aji ghara ku asiba ki"),
        "as" to listOf("moi aji ghorot asu, tumi kot asa? bhat khali neki", "tumi kenekoi asa? moi bhal asu. ketiya aahibo"),
        "ar" to listOf("ana bokra msh hagi el shoghl, ta3ban shwaya", "ezayak? ana 3ayez a2abelak bokra, enta fady el sa3a kam"),
    )

    /** Another "Convert to" language next to each one, as a user with two or three keyboards would have. */
    private val neighbours = mapOf(
        "ur" to "hi", "ne" to "hi", "ta" to "te", "te" to "ta", "mr" to "hi", "gu" to "hi",
        "pa" to "hi", "ml" to "ta", "kn" to "te", "or" to "bn", "as" to "bn", "ar" to "ur",
    )

    /** Hindi and Urdu share one list, so each is allowed to match next to the other. */
    private val sharesWords = setOf("hi", "ur")

    @Test fun everyLanguageHasAListAndSentences() {
        for (language in sentences.keys) assertTrue("$language has no word list", language in Romanized.LISTS)
        assertEquals(setOf("ur", "ne", "ta", "te", "mr", "gu", "pa", "ml", "kn", "or", "as", "ar"), sentences.keys)
    }

    /** Every failing sentence in one report, instead of stopping at the first. */
    private fun assertNoFailures(failures: List<String>) = assertTrue(failures.joinToString("\n"), failures.isEmpty())

    @Test fun eachLanguageIsDetectedWhenItIsTheOnlyTarget() {
        val failures = ArrayList<String>()
        for ((language, texts) in sentences) for (text in texts) {
            val found = detect(text, language)
            if (found != listOf(language)) failures += "$language alone: \"$text\" -> $found"
        }
        assertNoFailures(failures)
    }

    @Test fun eachLanguageIsDetectedFirstNextToAnotherTarget() {
        val failures = ArrayList<String>()
        for ((language, texts) in sentences) {
            val other = neighbours.getValue(language)
            for (text in texts) for (targets in listOf(arrayOf(language, other), arrayOf(other, language))) {
                val found = detect(text, *targets)
                // Where the neighbour shares the list (Hindi/Urdu) both may come back; otherwise only the right one.
                val ok = if (setOf(language, other) == sharesWords) language in found else found.firstOrNull() == language
                if (!ok) failures += "$language with $other (${targets.joinToString("+")}): \"$text\" -> $found"
            }
        }
        assertNoFailures(failures)
    }

    @Test fun eachLanguageIsDetectedAmongThreeTargets() {
        val others = arrayOf("bn", "hi", "ta")
        val failures = ArrayList<String>()
        for ((language, texts) in sentences) for (text in texts) {
            val targets = arrayOf(language) + others.filter { it != language }.take(2)
            val found = detect(text, *targets)
            if (language !in found) failures += "$language among ${targets.toList()}: \"$text\" -> $found"
        }
        assertNoFailures(failures)
    }

    @Test fun plainEnglishIsNotDetectedAsAnyLanguage() {
        val texts = listOf(
            "Hey, are you free tomorrow? Let's have coffee after the meeting",
            "I'm on my way to the office, will be there in ten minutes",
            "Thanks for the call, let me know when you are free",
            "Sorry, I can't make it tonight. See you on Friday, bro",
        )
        for (text in texts) {
            for (language in sentences.keys) assertEquals("$language: \"$text\"", emptyList<String>(), detect(text, language))
            assertEquals("\"$text\"", emptyList<String>(), detect(text, *sentences.keys.toTypedArray()))
        }
    }
}
