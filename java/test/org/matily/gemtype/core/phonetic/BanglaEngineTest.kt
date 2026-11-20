// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from GemType for iOS (GemTypeCoreTests/PhoneticAvroTests.swift): the same cases, so the two
// keyboards spell Banglish the same way.

package org.matily.gemtype.core.phonetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BanglaEngineTest {
    private val data = PhoneticData.Folder(File("assets/gemtype/phonetic"))
    private val bn = shared
    private val dictionary get() = AvroDictionary.load(data)!!

    // Swift compares strings by canonical equivalence (ড় as one letter or as ড + nukta); compare NFC here.
    private fun sug(roman: String, limit: Int) = sugOf(bn, roman, limit)
    private fun sugOf(engine: BanglaEngine, roman: String, limit: Int) = engine.suggestions(roman, limit).map { it.nfc() }
    private fun conv(roman: String) = bn.convert(roman).nfc()

    private fun convertCases(vararg cases: Pair<String, String>) {
        for ((roman, bangla) in cases) assertEquals(roman, bangla, conv(roman))
    }

    private fun firsts(vararg cases: Pair<String, String>) {
        for ((roman, first) in cases) {
            val s = sug(roman, 5)
            assertEquals("$roman -> $s", first, s.firstOrNull())
        }
    }

    @Test fun upstreamRuleCases() = convertCases(
        "a" to "আ", "ia" to "ইয়া", "(a" to "(আ", "(a`" to "(া", "aa" to "আআ", "aa`" to "আা", "amar" to "আমার",
        "." to "।", ".." to "।।", "..." to "...", "R" to "ড়", "Rh" to "ঢ়",
        "kobja" to "কব্জা", "bhladimir" to "ভ্লাদিমির", "vladimir" to "ভ্লাদিমির", "lobdho" to "লব্ধ",
        "jobdo" to "জব্দ", "accha" to "আচ্ছা", "bacca" to "বাচ্চা", "boDDo" to "বড্ড", "bou" to "বউ", "bOU" to "বৌ",
    )

    @Test fun classicAvroWords() = convertCases(
        "ami" to "আমি", "tumi" to "তুমি", "tui" to "তুই", "bhalo" to "ভাল", "bhalO" to "ভালো",
        "bhalobasha" to "ভালবাশা", "bhalObasha" to "ভালোবাশা", "amar" to "আমার", "bangla" to "বাংলা",
        "bangladesh" to "বাংলাদেশ", "kemon" to "কেমন", "kemon acho" to "কেমন আছ", "achO" to "আছো",
        "amar sOnar bangla" to "আমার সোনার বাংলা", "ei" to "এই", "ki" to "কি", "kaj" to "কাজ",
        "dhonyobad" to "ধন্যবাদ", "Dhaka" to "ঢাকা", "khabar" to "খাবার", "baba" to "বাবা", "ma" to "মা",
        "bondhu" to "বন্ধু", "nobanno" to "নবান্ন", "hoy" to "হয়", "hOy" to "হোয়", "bishwo" to "বিশ্ব",
        "gram" to "গ্রাম", "jwol" to "জ্বল", "oporadh" to "অপরাধ", "rrishi" to "ঋশি", "kShOmA" to "ক্ষোমা",
        "sari" to "সারি", "1" to "১", "ami, tumi!" to "আমি, তুমি!",
    )

    @Test fun convertNeverEmptyForNonEmptyInput() {
        for (s in listOf("a", "x", "Q", "kh", "zzz", "!", "ক", "😀")) assertFalse(s, conv(s).isEmpty())
        assertEquals("", conv(""))
    }

    @Test fun upstreamDictionaryCases() {
        assertEquals(178_452, dictionary.wordCount)
        val cases = listOf(
            "sari" to listOf("শারি", "সারি", "শাড়ি", "শাড়ী"), "sar" to listOf("সার", "সাড়", "ষাঁড়"),
            "amra" to listOf("আমরা", "আমড়া"), "lalshak" to listOf("লালশাক"), "lalrong" to listOf("লালরং", "লালরঙ"),
            "ongshochched" to listOf("অংশচ্ছেদ"), "ongshocched" to listOf("অংশচ্ছেদ"), "shadhinota" to listOf("স্বাধীনতা"),
            "dukkho" to listOf("দুখ", "দুঃখ"),
        )
        for ((roman, expected) in cases) {
            val got = dictionary.candidates(roman).map { it.nfc() }.toSet()
            for (word in expected) assertTrue("$roman -> $word in $got", word in got)
        }
    }

    @Test fun suggestionsRankRuleResultFirstAndIncludeDictionaryForms() {
        assertEquals(listOf("ভালো", "ভাল"), sug("bhalo", 5).take(2))
        assertEquals("ভালো", sug("bhalO", 5).first())
        firsts("ami" to "আমি", "tumi" to "তুমি", "bangla" to "বাংলা")
        assertEquals(listOf("স্বাধীনতা"), sug("shadhinota", 3))
        assertTrue("ভালোবাসা" in sug("bhalobasha", 5))
        assertTrue("jn is not a pattern key of its own", "জ্ঞান" in sug("jnan", 5))
    }

    @Test fun frequencyFileIsLoadedAndMatchesTheWordList() {
        val d = dictionary
        assertTrue(d.hasFrequencies)
        val scored = (0 until d.wordCount).count { d.score(it) > 0 }
        assertTrue(scored > d.wordCount * 9 / 10)
        assertEquals(0, d.score(-1))
        assertEquals(0, d.score(d.wordCount))
        val off = AvroDictionary.load(data, useFrequencies = false)!!
        assertFalse(off.hasFrequencies)
        assertEquals(0, off.score(0))
    }

    @Test fun commonWordsComeFirstWithFrequencies() =
        firsts("bhalO" to "ভালো", "ami" to "আমি", "kemon" to "কেমন", "achi" to "আছি", "tumi" to "তুমি")

    @Test fun aFinalOMeansTheOSpellingUnlessAnotherWordIsFarMoreCommon() {
        firsts(
            "bolo" to "বলো", "bol" to "বল", "koro" to "করো", "kor" to "কর", "ekhono" to "এখনো",
            "dekho" to "দেখো", "holo" to "হলো", "moto" to "মতো",
            "keno" to "কেন", "boro" to "বড়", "jeno" to "যেন", "bondho" to "বন্ধ",
        )
        assertEquals(listOf("বলো", "বল"), sug("bolo", 2))
    }

    @Test fun aConsonantAndIMeansTheVowelSign() {
        assertEquals(listOf("নি", "নী", "নই"), sug("ni", 3))
        assertEquals(listOf("নী", "নি"), sug("nI", 2))
        firsts(
            "di" to "দি", "bi" to "বি", "ti" to "তি", "ki" to "কি", "ami" to "আমি", "kori" to "করি", "bari" to "বাড়ি",
            "sei" to "সেই", "boi" to "বই", "hoi" to "হই", "bhai" to "ভাই",
        )
    }

    @Test fun anAMayStandForThePlainVowel() {
        firsts(
            "shatabdi" to "শতাব্দী", "satabdi" to "শতাব্দী", "rahim" to "রহিম", "karim" to "করিম",
            "shampa" to "শম্পা", "nazrul" to "নজরুল", "rabindranath" to "রবীন্দ্রনাথ",
            "kal" to "কাল", "kamal" to "কামাল", "jamal" to "জামাল", "hasan" to "হাসান", "amar" to "আমার",
            "kotha" to "কথা", "matha" to "মাথা",
        )
        assertTrue("কল" in sug("kal", 8))
        assertEquals(listOf("shotabdi", "shatobdi", "shotobdi"), BanglaEngine.plainVowelReadings("shatabdi").map { it.roman })
        assertTrue(BanglaEngine.plainVowelReadings("kotha").isEmpty())
        assertTrue(BanglaEngine.plainVowelReadings("ami").isEmpty())
    }

    @Test fun everydayWordsTheAvroListLacked() {
        firsts(
            "desher" to "দেশের", "mone" to "মনে", "dike" to "দিকে", "ekhane" to "এখানে", "kajer" to "কাজের",
            "boigulo" to "বইগুলো", "barite" to "বাড়িতে", "hothat" to "হঠাৎ", "jogot" to "জগৎ", "jogoter" to "জগতের",
            "kI" to "কী", "ekoi" to "একই", "jodio" to "যদিও", "ekta" to "একটা",
        )
        assertTrue("কী" in sug("ki", 5))
        for (roman in listOf("hotat", "hotath", "hothat", "hothath", "hoTat")) assertEquals(roman, "হঠাৎ", sug(roman, 3).first())
        for ((roman, words) in listOf("sot" to listOf("সৎ", "শত"), "chad" to listOf("ছাদ", "চাঁদ"), "cad" to listOf("চাঁদ"), "chand" to listOf("চাঁদ"))) {
            assertEquals(roman, words, sug(roman, 5).take(words.size))
        }
        assertTrue("আরও" in sug("aro", 5))
    }

    @Test fun capitalsAndMarksFromTheAvroGuide() {
        convertCases(
            "kI" to "কী", "dUr" to "দূর", "bOU" to "বৌ", "kOI" to "কৈ", "OIrabot" to "ঐরাবত",
            "Taka" to "টাকা", "Thik" to "ঠিক", "Dhaka" to "ঢাকা", "ek`Ta" to "একটা", "am`ra" to "আমরা",
            "ca^d" to "চাঁদ", "t``" to "ৎ", "bishwo" to "বিশ্ব", "bybohar" to "ব্যবহার", "aZDmin" to "অ্যাডমিন",
            "orrko" to "অর্ক", "rriju" to "ঋজু", "$" to "৳",
        )
        for ((roman, first) in listOf("Tumi" to "তুমি", "Desh" to "দেশ", "Rasta" to "রাস্তা", "Onek" to "অনেক", "Jodi" to "যদি")) {
            assertEquals(roman, first, sug(roman, 3).first())
        }
    }

    @Test fun lettersPeopleLeaveOut() {
        firsts(
            "jachi" to "যাচ্ছি", "jachhi" to "যাচ্ছি", "jachchi" to "যাচ্ছি", "gumachi" to "ঘুমাচ্ছি", "lagachi" to "লাগাচ্ছি",
            "khachi" to "খাচ্ছি", "kachhi" to "খাচ্ছি", "pachi" to "পাচ্ছি", "hoche" to "হচ্ছে", "ichha" to "ইচ্ছা",
            "acha" to "আচ্ছা", "bachcha" to "বাচ্চা", "utor" to "উত্তর", "purbo" to "পূর্ব", "oddhay" to "অধ্যায়",
            "sobbhota" to "সভ্যতা", "bebohar" to "ব্যবহার", "satya" to "সত্য",
            "nyae" to "ন্যায়", "koresi" to "করেছি", "gese" to "গেছে", "pothom" to "প্রথম", "boishak" to "বৈশাখ", "nowka" to "নৌকা",
            "surrzo" to "সূর্য", "gyan" to "জ্ঞান", "sodosyo" to "সদস্য", "syar" to "স্যার",
            "kache" to "কাছে", "korchi" to "করছি", "aschi" to "আসছি", "dorkar" to "দরকার", "korte" to "করতে",
        )
        for ((roman, word) in listOf("halo" to "হ্যালো", "dharma" to "ধর্ম", "ghan" to "ঘ্রাণ", "gam" to "গ্রাম")) {
            assertTrue(roman, word in sug(roman, 3))
        }
        val readings = listOf(
            "jachhi" to "jacchi", "hoche" to "hocche", "purbo" to "purrbo", "surrzo" to "surzo", "bebohar" to "bybohar",
            "halo" to "hyalo", "gyan" to "ggan", "nyae" to "nyay", "koresi" to "korechi", "diesi" to "diyechi",
        )
        for ((word, reading) in readings) assertTrue(word, BanglaEngine.casualReadings(word).any { it.roman == reading })
    }

    @Test fun lettersPeopleSwapStillFindTheWord() {
        val ta = sug("ta", 8)
        assertEquals("তা", ta.first())
        assertTrue("$ta", "টা" in ta)
        firsts(
            "kabo" to "খাবো", "khabo" to "খাবো", "jabo" to "যাবো", "bolo" to "বলো",
            "matha" to "মাথা", "bari" to "বাড়ি", "korbo" to "করবো", "kemon" to "কেমন",
        )
        assertTrue("ঠিক" in sug("thik", 5))
        assertFalse("বলে" in sug("bolo", 10))
    }

    @Test fun chatSpellingsFromThePhone() {
        // What the Android keyboard's Varnam engine got wrong (valobashi -> বলবশি, korbo -> খর্ব, jabo -> জব).
        firsts("korbo" to "করবো", "jabo" to "যাবো")
        // ভালবাসি and ভালোবাসি are both right: the older and the newer spelling.
        for (roman in listOf("valobashi", "bhalobashi", "valobasi")) assertTrue(roman, "ভালোবাসি" in sug(roman, 2))
        assertTrue(sug("ashbo", 2).any { it == "আসবো" || it == "আসব" })
    }

    @Test fun soundKeys() {
        for (roman in listOf("ta", "Ta", "tha", "Tha")) assertEquals(roman, "t", SoundKey.roman(roman))
        assertEquals(SoundKey.bangla("খাবো"), SoundKey.roman("kabo"))
        assertEquals(SoundKey.bangla("শতাব্দী"), SoundKey.roman("shatabdi"))
        assertEquals("stbd", SoundKey.roman("shotabdI"))
        assertEquals(SoundKey.bangla("আমি"), SoundKey.roman("ami"))
        assertEquals(SoundKey.bangla("ক্ষমা"), SoundKey.roman("khoma"))
        assertEquals(SoundKey.bangla("বাংলা"), SoundKey.roman("bangla"))
        assertEquals(SoundKey.bangla("ধন্যবাদ"), SoundKey.roman("dhonnobad"))
        assertTrue(SoundKey.bangla("কম্পিউটার") in SoundKey.romanKeys("computer"))
        for ((roman, word) in listOf(
            "main" to "मैं", "hai" to "है", "nahi" to "नहीं", "kya" to "क्या", "gyan" to "ज्ञान",
            "vah" to "वह", "wah" to "वह", "ladka" to "लड़का", "haan" to "हाँ", "yahan" to "यहाँ",
        )) assertTrue("$roman $word", SoundKey.devanagari(word) in SoundKey.hindiKeys(roman))
    }

    @Test fun theSoundKeyFileMatchesTheRules() {
        val d = dictionary
        val index = d.soundKeyIndex!!
        assertTrue(Math.abs(index.count - (d.wordCount - 10)) <= 50)
        var i = 0
        while (i < index.count) {
            val word = d.word(index.wordIndex(i))
            assertEquals(word, SoundKey.bangla(word), index.key(i))
            i += 997
        }
    }

    @Test fun englishWordsAndNamesTypedTheEnglishWay() {
        firsts(
            "computer" to "কম্পিউটার", "facebook" to "ফেসবুক", "Mobile" to "মোবাইল",
            "dhaka" to "ঢাকা", "nusrat" to "নুসরাত", "shatabdi" to "শতাব্দী",
        )
        assertEquals("দাতা", sug("data", 3).first())
    }

    @Test fun starterListIsWellFormed() {
        for (line in data.text("bn-starter.tsv")!!.split('\n')) {
            if (line.startsWith("#") || line.isEmpty()) continue
            val f = line.split('\t')
            assertEquals(line, 2, f.size)
            assertTrue(line, f[0].all { it.code < 128 && (it.isLowerCase() || it.isDigit()) })
            assertFalse(line, f[1].any { it.code < 128 })
        }
    }

    @Test fun frequencyOrdersWordsWithinTheSameCloseness() {
        val s = sug("bhalo", 8)
        assertEquals("$s", listOf("ভালো", "ভাল"), s.take(2))
        assertEquals("বড়", sug("boro", 3).first())
        assertEquals("বাড়ি", sug("bari", 3).first())
        assertEquals("সব", sug("shob", 3).first())
        val real = s.indexOf("ভয়াল")
        assertTrue("$s", real >= 0)
        for (junk in listOf("ভঅল", "ভএল")) {
            val j = s.indexOf(junk)
            assertTrue("$junk $s", j < 0 || real < j)
        }
    }

    @Test fun rankingWorksWithoutTheFrequencyFile() {
        val plain = BanglaEngine(data, useFrequencies = false)
        assertEquals("ভালো", sugOf(plain, "bhalo", 5).first())
        assertTrue("ভাল" in sugOf(plain, "bhalo", 3))
        assertEquals("আমি", sugOf(plain, "ami", 3).first())
        assertEquals("তুমি", sugOf(plain, "tumi", 3).first())
        assertEquals(listOf("স্বাধীনতা"), sugOf(plain, "shadhinota", 3))
        assertEquals(emptyList<String>(), sugOf(plain, "bhalo", 0))
        assertEquals(emptyList<String>(), sugOf(plain, "", 5))
    }

    @Test fun suggestionsEdgeCases() {
        assertEquals(2, sug("bhalo", 2).size)
        assertEquals(emptyList<String>(), sug("bhalo", 0))
        assertEquals(emptyList<String>(), sug("", 5))
        assertEquals(emptyList<String>(), sug("123 !?", 5))
        assertEquals(emptyList<String>(), sug("zzzzzzzzzzqqqq", 5))
        assertEquals("আছো", sug("kemon acho", 3).first())
        sug("oooooooooooooooooooooooooooo", 5) // stays bounded
    }

    @Test fun theComposerCommitsTheBestWord() {
        // What space commits on the Bangla (Avro) keyboard, as on iOS.
        for ((roman, word) in listOf(
            "khabo" to "খাবো", "kabo" to "খাবো", "jabo" to "যাবো", "jachi" to "যাচ্ছি", "pachi" to "পাচ্ছি",
            "korbo" to "করবো", "ami" to "আমি", "bhalo" to "ভালো", "Tumi" to "তুমি",
        )) assertEquals(roman, word.nfc(), PhoneticComposing.best(bn, roman, null).nfc())
        // Spelled out with a capital or a mark: the rule result.
        assertEquals("বলো".nfc(), PhoneticComposing.best(bn, "bolO", null).nfc())
        assertEquals("টাকা".nfc(), PhoneticComposing.best(bn, "Taka", null).nfc())
        // The user's earlier choice comes first.
        assertEquals("ভালবাসি".nfc(), PhoneticComposing.best(bn, "valobashi", "ভালবাসি").nfc())
        // The rule result stays in the list.
        val list = PhoneticComposing.candidates(bn, "khabo", bn.suggestions("khabo", 10), null)
        assertTrue(bn.convert("khabo").nfc() in list)
    }

    @Test fun closestWordScoresCommonWords() {
        val ami = bn.closestWord("ami")
        assertNotNull(ami)
        assertEquals("আমি", ami!!.word)
        assertTrue(ami.score >= 165)
        assertNull(bn.closestWord("zzzzqqq"))
    }

    @Test fun sortedWordListBehavesLikeATrie() {
        val list = SortedWordList.of(data.open("avro-words.bin")!!)!!
        fun enc(w: String) = list.codec.encode(w)!!.map { it.toInt() and 0xFF }.toIntArray()
        val aami = list.extend(list.root, enc("আমি"))!!
        assertTrue(list.isWord(aami))
        assertEquals("আমি", list.nodeWord(aami))
        val am = list.extend(list.root, enc("আম"))!!
        assertTrue(am.hi - am.lo > 10)
        assertNull(list.extend(list.root, enc("আমিংংং")))
        assertNull(list.extend(list.root, IntArray(0)))
        var i = 0
        while (i < list.count) {
            val w = list.word(i)
            val node = list.extend(list.root, enc(w))
            assertNotNull(w, node)
            assertTrue(w, list.isWord(node!!))
            assertEquals(w, list.nodeWord(node))
            i += 997
        }
    }

    companion object {
        private val shared = BanglaEngine(PhoneticData.Folder(File("assets/gemtype/phonetic")))
    }
}
