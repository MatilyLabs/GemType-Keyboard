// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Detection of romanized text: runs against the real bundled word lists in assets/gemtype/romanized. */
class RomanizedTest {
    private val dir = File("assets/gemtype/romanized")
    private val lists: Map<String, Set<String>> =
        Romanized.LISTS.associateWith { Romanized.parseList(File(dir, Romanized.listFile(it)).readText()) }

    // Stand-in for the keyboard's English dictionary.
    private val englishWords = setOf(
        "i", "will", "come", "tomorrow", "if", "you", "are", "free", "today", "main", "to", "the", "is", "a", "and",
        "go", "home", "in", "time", "this", "that", "we", "can", "make", "it", "on", "friday", "sorry", "hello", "how",
        "what", "please", "thanks", "ok", "me", "my", "hi", "so", "not", "do", "have", "be", "for", "of", "at", "with",
        "reason", "now", "meeting", "later", "see", "dinner", "tonight", "want", "eat", "send", "message", "when", "get", "back",
    )
    private val english: (String) -> Boolean = { it in englishWords }

    private fun detect(text: String, vararg languages: String, isEnglish: ((String) -> Boolean)? = english) =
        Romanized.detect(text, languages.toList(), lists::get, isEnglish).map { it.language }

    private val positives = mapOf(
        "bn" to "ami kal ashbo na",
        "hi" to "kya kar rahe ho aaj",
        "ur" to "kia kr rha hai",
        "ne" to "ma bholi aauchu hajur",
        "ta" to "naan romba busy iruken",
        "te" to "nenu repu vastanu chala baagundi",
        "mr" to "mi udya yein tu kay karto",
        "gu" to "hu kale aavish tame shu karo cho",
        "pa" to "tusi kithe ho assi ajj aaye",
        "ml" to "njan nale varam ninte veedu evide",
        "kn" to "naanu nale baruve neenu yenu madtidiya",
        "or" to "mu kali asibi tume kemiti",
        "as" to "moi kali nuwaro tumi ki koribo",
        "si" to "mama heta enawa oya kohomada",
        "ar" to "ana 3ayez a7ki ma3ak ya habibi",
        "sa" to "aham gacchami tvam kim karoshi",
        "mai" to "ahan kehene chhi hamar bhai",
        "bho" to "kaisan ba raua kahan jaib",
        "my" to "mingalaba kyayzu tinbade",
    )

    private val englishNegatives = listOf(
        "I will come tomorrow if you are free today",
        "This is the main reason we can make it on Friday",
        "Hello, how are you? Please send the message when you get back",
        "Sorry, I want to eat dinner tonight and see you later",
    )

    @Test fun everyListedLanguageHasAList() {
        assertEquals(Romanized.LISTS, positives.keys)
        for ((language, list) in lists) assertTrue("$language list is empty", list.size >= 40)
    }

    @Test fun listsKeepOutCommonEnglishWords() {
        val common = setOf("main", "is", "to", "me", "hi", "ok", "the", "so", "do", "go", "no", "he", "she", "we")
        for ((language, list) in lists) assertTrue("$language: ${list intersect common}", (list intersect common).isEmpty())
    }

    @Test fun eachLanguageIsDetectedFromItsOwnWords() {
        for ((language, text) in positives) {
            assertTrue("$language: \"$text\" -> ${detect(text, language)}", language in detect(text, language))
        }
    }

    @Test fun plainEnglishIsNeverDetected() {
        for (text in englishNegatives) {
            for (language in lists.keys) assertEquals("$language: $text", emptyList<String>(), detect(text, language))
            assertEquals(emptyList<String>(), detect(text, *lists.keys.toTypedArray()))
        }
    }

    @Test fun hindiAndUrduShareRomanizationSoBothMatch() {
        assertEquals(listOf("hi", "ur"), detect("main kal aaunga", "hi", "ur"))
        assertEquals(listOf("ur"), detect("main kal aaunga", "ur"))
    }

    @Test fun lastPickedLanguageComesFirst() {
        val found = Romanized.detect("main kal aaunga", listOf("hi", "ur"), lists::get, english)
        assertEquals(listOf("hi", "ur"), Romanized.order(found, null).map { it.language })
        assertEquals(listOf("ur", "hi"), Romanized.order(found, "ur").map { it.language })
        assertEquals(listOf("hi", "ur"), Romanized.order(found, "bn").map { it.language })
    }

    @Test fun higherScoreComesFirstAndAtMostTwoPills() {
        val found = Romanized.detect("ami kal ashbo na kintu tumi ki korbe", listOf("hi", "bn", "as"), lists::get, english)
        assertEquals("bn", found.first().language)
        val three = listOf(Romanized.Match("bn", 3), Romanized.Match("hi", 2), Romanized.Match("ur", 2))
        assertEquals(listOf("bn", "hi"), Romanized.order(three, null).map { it.language })
        assertEquals(listOf("ur", "bn"), Romanized.order(three, "ur").map { it.language })
    }

    @Test fun wordsMustBeAtLeastTwoAndFortyPercent() {
        // 2 of 5 words (40%) is enough; 2 of 6 (33%) is not; 1 of 1 is not.
        assertEquals(listOf("bn"), detect("ami kal go home now", "bn"))
        assertEquals(emptyList<String>(), detect("ami kal go home now please", "bn"))
        assertEquals(emptyList<String>(), detect("ami", "bn"))
        assertEquals(emptyList<String>(), detect("ami go home now", "bn")) // only one list word
    }

    @Test fun oneListWordIsEnoughWhenTheTextIsMostlyNotEnglish() {
        // A real chat message: only "ami" and "ki" are list words, the rest are spellings no list has.
        assertEquals("bn", detect("Ami ki korile paibo tmk he goru", "bn", "ur", "en").first())
        assertEquals(listOf("bn"), detect("ami xyzzy qwrt", "bn")) // 1 list word, 3 of 3 foreign
        assertEquals(emptyList<String>(), detect("ami xyzzy", "bn")) // fewer than 3 words
        assertEquals(emptyList<String>(), detect("I will come tomorrow ami", "bn")) // mostly English
        assertEquals(emptyList<String>(), detect("ami xyzzy qwrt", "bn", isEnglish = null)) // needs a dictionary
    }

    @Test fun listWordsThatAreValidEnglishDoNotCount() {
        val bothEnglish: (String) -> Boolean = { it in setOf("ami", "kal") }
        assertEquals(emptyList<String>(), detect("ami kal ashbo", "bn", isEnglish = bothEnglish))
        // Without a dictionary nothing is dropped.
        assertEquals(listOf("bn"), detect("ami kal ashbo", "bn", isEnglish = null))
    }

    @Test fun languageWithoutAListIsOfferedOnlyWhenItIsTheUsersOnlyOne() {
        assertEquals(listOf("sat"), detect("xyzzy qwrt plmk", "sat"))
        assertEquals(emptyList<String>(), detect("xyzzy qwrt", "sat")) // fewer than 3 words
        assertEquals(listOf("sat"), detect("xyzzy qwrt plmk home", "sat")) // 3 of 4 = 75%
        assertEquals(listOf("sat"), detect("xyzzy qwrt plmk home now", "sat")) // 3 of 5 = 60%
        assertEquals(emptyList<String>(), detect("xyzzy qwrt plmk home now free", "sat")) // 3 of 6 = 50%
        assertEquals(emptyList<String>(), detect("xyzzy qwrt plmk", "sat", "ks")) // two such languages: stay quiet
        assertEquals(emptyList<String>(), detect("xyzzy qwrt plmk", "sat", isEnglish = null))
        assertEquals(emptyList<String>(), detect("I will come tomorrow", "sat"))
    }

    @Test fun fallbackIgnoresTextAlreadyInNativeScript() {
        assertEquals(emptyList<String>(), detect("আমি কাল আসবো না", "sat"))
    }

    @Test fun nativeScriptTextIsNotDetected() {
        assertEquals(emptyList<String>(), detect("আমি কাল আসবো না", "bn"))
        assertEquals(emptyList<String>(), detect("ami কাল আসবো না", "bn")) // 1 list word
    }

    @Test fun currentSentenceStopsAtTheLastSentenceEndOrLineBreak() {
        assertEquals("ami kal ashbo na", Romanized.currentSentence("Hello there. ami kal ashbo na"))
        assertEquals("ami kal ashbo na", Romanized.currentSentence("Hello!\nami kal ashbo na"))
        // Typing the full stop must not make the pill vanish.
        assertEquals("ami kal ashbo na", Romanized.currentSentence("ami kal ashbo na."))
        assertEquals("ami kal ashbo na", Romanized.currentSentence("Ok. ami kal ashbo na?!  "))
        assertEquals("", Romanized.currentSentence(""))
        assertEquals("", Romanized.currentSentence("..."))
    }

    @Test fun currentSentenceLooksAtMostThreeHundredCharsBack() {
        val long = "a ".repeat(400) + "ami kal ashbo"
        assertTrue(Romanized.currentSentence(long).length <= Romanized.TAIL_CHARS)
        assertTrue(Romanized.currentSentence(long).endsWith("ami kal ashbo"))
    }

    @Test fun wordsAreLettersApostrophesAndDigitsInsideWords() {
        assertEquals(listOf("don't", "go", "3ala", "ami"), Romanized.words("Don't go, 3ala 2024 'ami'!"))
        assertEquals(listOf("আমি", "কাল"), Romanized.words("আমি কাল"))
    }

    @Test fun listsAreWordsWithCommentsIgnored() {
        assertEquals(setOf("ami", "tumi", "kal"), Romanized.parseList("# a comment\nAmi tumi\n  kal\n"))
    }

    @Test fun languageNamesCoverEveryListedLanguage() {
        for (language in Romanized.NON_LATIN) {
            assertTrue(language, Romanized.englishName(language).isNotBlank())
            assertTrue(language, Romanized.nativeName(language).isNotBlank())
        }
        assertEquals("বাংলা", Romanized.nativeName("bn"))
        assertEquals("हिन्दी", Romanized.nativeName("hi"))
        assertEquals("اردو", Romanized.nativeName("ur"))
        assertEquals("தமிழ்", Romanized.nativeName("ta"))
    }

    @Test fun neverChosenFallsBackToTheEnabledKeyboardLanguagesCappedAtThree() {
        assertEquals(listOf("bn", "ur"), Romanized.targets(null, listOf("bn", "ur")))
        assertEquals(listOf("bn", "hi", "ta"), Romanized.targets(null, listOf("bn", "hi", "ta", "ur")))
        assertEquals(emptyList<String>(), Romanized.targets(null, emptyList()))
        assertEquals(listOf("bn"), Romanized.targets(null, listOf("bn", "en", "bn"))) // Latin and repeats dropped
    }

    @Test fun aSavedChoiceWinsEvenWhenEmpty() {
        assertEquals(listOf("bn", "hi", "ur"), Romanized.targets("bn,hi,ur", listOf("ta")))
        assertEquals(listOf("ur"), Romanized.targets("ur", listOf("bn", "hi")))
        assertEquals(emptyList<String>(), Romanized.targets("", listOf("bn", "hi"))) // "None"
    }

    @Test fun savedChoiceIsCappedAtThreeAndKeepsOnlyKnownLanguages() {
        assertEquals(listOf("bn", "hi", "ur"), Romanized.parseTargets("bn,hi,ur,ta,te"))
        assertEquals(listOf("bn", "hi"), Romanized.parseTargets("bn, xx,en,hi,bn"))
        assertEquals(null, Romanized.parseTargets(null))
        val saved = Romanized.formatTargets(listOf("ta", "te", "bn", "hi"))
        assertEquals("ta,te,bn", saved)
        assertEquals(listOf("ta", "te", "bn"), Romanized.parseTargets(saved))
        assertEquals("", Romanized.formatTargets(emptyList()))
    }

    @Test fun detectionOffersOnlyTheChosenLanguages() {
        val hindiOrUrdu = "humari iska koi"
        val chosen = { saved: String? -> Romanized.targets(saved, listOf("bn", "ur")) }
        val found = { saved: String?, text: String ->
            Romanized.order(Romanized.detect(text, chosen(saved), lists::get, english), null).map { it.language }
        }
        // Default (enabled Bangla + Urdu): only Urdu can be offered for Hindi/Urdu text.
        assertEquals(listOf("ur"), found(null, hindiOrUrdu))
        // Chosen Bangla, Hindi, Urdu: Hindi and Urdu share romanization, so both show; Bangla text shows only Bangla.
        assertEquals(listOf("hi", "ur"), found("bn,hi,ur", hindiOrUrdu))
        assertEquals(listOf("bn"), found("bn,hi,ur", "ami kal ashbo na"))
        // Not chosen means never offered; Assamese appears next to Bangla only when it is chosen too.
        assertEquals(emptyList<String>(), found("bn", hindiOrUrdu))
        assertEquals(emptyList<String>(), found("", "ami kal ashbo na"))
        assertEquals(listOf("bn", "as"), found("bn,as", "ami kal ashbo na tumi ki koribo").sorted().reversed())
    }
}
