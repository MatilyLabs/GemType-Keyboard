// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Detection of romanized text on 1,409 chat sentences (test/resources/gemtype/romanized-chat.tsv:
 * `<language>\t<sentence>`, "en" = must never be detected), with the bundled lists and, as English, the
 * words of the keyboard's own English dictionary source (dictionaries/en_wordlist.combined.gz, plain
 * text). Prints how often each language is found after three words and at the end of the sentence, and
 * fails if that drops below what the lists alone reached when this test was written.
 */
class RomanizedChatTest {
    private val sentences: List<Pair<String, String>> =
        File("test/resources/gemtype/romanized-chat.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }

    private val lists: Map<String, Set<String>> = Romanized.LISTS.associateWith {
        Romanized.parseList(File("assets/gemtype/romanized", Romanized.listFile(it)).readText())
    }

    // The main dictionary's words as stored (case kept: the keyboard's lookup is exact, so "Ami" the name
    // does not make "ami" English).
    private val englishWords: Set<String> =
        File("../dictionaries/en_wordlist.combined.gz").useLines { lines ->
            lines.mapNotNull { Regex("^ word=([^,]+),").find(it)?.groupValues?.get(1) }.toHashSet()
        }
    private val isEnglish: (String) -> Boolean = { it in englishWords }

    // What the Bangla and Hindi language packs' phonetic dictionaries (Varnam) answered for each word.
    private val phonetic: Map<String, Int> =
        File("test/resources/gemtype/romanized-chat-varnam.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split('\t').let { "${it[0]}|${it[1]}" to it[2].toInt() } }
    private val recognizer = Romanized.Recognizer { language, word ->
        if (!PhoneticStanding.eligible(word)) Romanized.Standing.NONE
        else PhoneticStanding.of(language, phonetic["$language|$word"])
    }

    private class Tally(var total: Int = 0, var early: Int = 0, var full: Int = 0)

    private fun firstWords(text: String, count: Int) = text.split(' ').take(count).joinToString(" ")

    private class Result(val percent: Map<String, Pair<Int, Int>>, val flagged: List<String>, val englishTotal: Int)

    private fun run(name: String, recognizer: Romanized.Recognizer?): Result {
        val found = LinkedHashMap<String, Tally>()
        val flagged = ArrayList<String>()
        var englishTotal = 0
        for ((language, text) in sentences) {
            if (language == "en") {
                englishTotal++
                val hit = Romanized.detect(text, listOf("bn", "hi"), lists::get, isEnglish, recognizer)
                if (hit.isNotEmpty()) flagged += "$text -> ${hit.joinToString(",") { it.language }}"
                continue
            }
            // The user's own languages: the one written, next to Bangla and Hindi.
            val languages = listOf(language) + listOf("bn", "hi").filter { it != language }
            fun hit(sample: String) = Romanized.detect(sample, languages, lists::get, isEnglish, recognizer).any {
                it.language == language || (language == "ur" && it.language == "hi") || (language == "hi" && it.language == "ur")
            }
            val tally = found.getOrPut(language) { Tally() }
            tally.total++
            if (hit(firstWords(text, 3))) tally.early++
            if (hit(text)) tally.full++
        }
        val order = listOf("bn", "hi", "ur", "mr", "gu", "pa", "ta", "te", "kn", "ml", "ne", "or", "as", "si", "ar")
        val percent = order.filter { it in found }.associateWith { found.getValue(it).let { t -> 100 * t.early / t.total to 100 * t.full / t.total } }
        println("ROMANIZED $name: " +
            percent.entries.joinToString(" ") { (l, p) -> "$l ${p.first}/${p.second}" } +
            " | English flagged ${flagged.size}/$englishTotal   (found after 3 words / whole sentence, %)")
        flagged.take(12).forEach { println("  FLAGGED $it") }
        return Result(percent, flagged, englishTotal)
    }

    private fun assertAtLeast(result: Result, floors: Map<String, Pair<Int, Int>>) {
        assertTrue("English taken for another language: ${result.flagged}", result.flagged.size * 100 <= result.englishTotal * 1)
        for ((language, floor) in floors) {
            val (early, full) = result.percent.getValue(language)
            assertTrue("$language after three words: $early < ${floor.first}", early >= floor.first)
            assertTrue("$language whole sentence: $full < ${floor.second}", full >= floor.second)
        }
    }

    @Test fun listsAloneFindRomanizedChatAndNeverEnglish() {
        assertAtLeast(run("lists + English dictionary", null), FLOORS)
    }

    @Test fun phoneticDictionariesFindMoreAndStillNeverEnglish() {
        assertAtLeast(run("lists + English dictionary + Varnam (bn, hi)", recognizer), PHONETIC_FLOORS)
    }

    private companion object {
        /** With the Bangla and Hindi phonetic dictionaries (the companion engine on the device). */
        val PHONETIC_FLOORS = mapOf("bn" to (88 to 95), "hi" to (87 to 95))

        /** What the lists alone reach (after three words, whole sentence, %): a change may only improve on it. */
        val FLOORS = mapOf(
            "bn" to (85 to 94), "hi" to (86 to 94), "ur" to (92 to 95), "mr" to (95 to 97), "gu" to (65 to 75),
            "pa" to (45 to 60), "ta" to (55 to 57), "te" to (52 to 52), "kn" to (40 to 40), "ml" to (57 to 60),
            "ne" to (67 to 72), "or" to (64 to 72), "as" to (75 to 75), "si" to (56 to 58), "ar" to (35 to 35),
        )
    }
}
