// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from GemType for iOS (GemTypeCore/Phonetic/BanglaEngine.swift). Avro Phonetic for Bangla: the rule
// converter and the dictionary matcher are ports of https://github.com/mugli/libavrophonetic (MIT, Copyright
// (c) 2021 Mehdi Hasan Khan); see AvroRuleConverter.kt, AvroDictionary.kt and LICENSE-libavrophonetic.

package org.matily.gemtype.core.phonetic

/** Bangla phonetic typing in the Avro style: "ami" -> আমি, "bhalo" -> ভাল (rules) / ভালো (dictionary). */
class BanglaEngine(private val data: PhoneticData, private val useFrequencies: Boolean = true) : PhoneticEngine {
    override val language = "bn"

    private val rules: AvroRuleConverter? by lazy { AvroRuleConverter.load(data) }
    private val dictionary: AvroDictionary? by lazy { AvroDictionary.load(data, useFrequencies) }
    private val starters: StarterWords? by lazy { StarterWords.load(data, "bn") }

    override fun convert(roman: String): String = rules?.convert(roman) ?: roman

    override fun suggestions(roman: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        // Suggestions are for one word: the last one if the caller passes a phrase.
        val word = roman.split(Regex("\\s+")).lastOrNull { it.isNotEmpty() } ?: roman
        // English words and names typed the English way come first ("computer" -> কম্পিউটার, "dhaka" -> ঢাকা).
        // NFC throughout, so a starter word and the same dictionary word (ড় as one letter or two) are one.
        val out = ArrayList(starters?.words(word) ?: emptyList())
        for (w in rankedCandidates(word).take(limit).map { it.word.nfc() }) if (w !in out) out.add(w)
        return out.take(limit)
    }

    /** A dictionary word close to what was typed and how common it is (0...255). */
    data class Closest(val word: String, val score: Int)

    /**
     * The closest dictionary word for [roman] typed in Latin letters and how common it is, or null when no
     * word is spelled close to it. Used to spot Bangla typed with English letters (Romanized).
     */
    fun closestWord(roman: String): Closest? {
        val dictionary = dictionary ?: return null
        var best: Closest? = null
        for (item in rankedCandidates(roman).take(12)) {
            if (item.far) continue
            val score = dictionary.score(item.index)
            if (score > (best?.score ?: -1)) best = Closest(item.word, score)
        }
        return best
    }

    private class Ranked(val far: Boolean, val key: Int, val distance: Int, val index: Int, val word: String)

    private class Spelling(val distance: Int, val word: String)

    private fun rankedCandidates(word: String): List<Ranked> {
        val dictionary = dictionary ?: return emptyList()
        val found = dictionary.candidateEntries(word)
        // Many people write the plain vowel as "a" (shatabdi for শতাব্দী, rahim for রহিম), which Avro reads as া.
        // Those spellings are looked up too, reading such a's as "o"; they lead only when the word as typed
        // has no close spelling ("kal" stays কাল, not কল).
        class Loose(val entry: AvroDictionary.Entry, val roman: String, val changed: Int)
        val loose = plainVowelReadings(word).flatMap { reading ->
            dictionary.candidateEntries(reading.roman).map { Loose(it, reading.roman, reading.changed) }
        }
        // Words that sound the same whatever letters were used: "ta" -> তা and টা, "kabo" -> খাবো (see SoundKey).
        val alikes = dictionary.soundAlikes(word)
        if (found.isEmpty() && loose.isEmpty() && alikes.isEmpty() && casualReadings(word).isEmpty()) return emptyList()
        // The dictionary returns every spelling the Avro patterns allow, in pattern order, which is not a
        // ranking. Close spellings (weighted distance <= 3 from the rule result) come first and are ranked by
        // how common they are, the rule result getting a head start of CLOSENESS points per unit of distance.
        // Far spellings come after, by frequency. Ties fall back to the distance, then the dictionary's order.
        val target = convert(word).scalars()
        // A final "o" is the inherent vowel for the rules (bhalo -> ভাল), but people usually mean the ো
        // spelling (ভালো): it counts as exact too.
        val withO: IntArray? = if (word.endsWith("o") && target.isNotEmpty() && !isSign(target.last())) target + 0x09CB else null
        var oWord: Pair<Int, Int>? = null // score, index
        var topScore = 0 // of the close spellings
        val distances = HashMap<Int, Spelling>() // word index -> distance
        val mergedTarget = SoundKey.merged(target)
        val mergedO = withO?.let { SoundKey.merged(it) }
        // The ো spelling for a final "o": the exact one, or the most common sound-alike of it
        // ("kabo" -> খাবো, "jabo" -> যাবো rather than the rare জাবো).
        fun considerO(scalars: IntArray, index: Int) {
            if (withO == null) return
            val score = dictionary.score(index)
            if ((scalars.contentEquals(withO) || (SoundKey.merged(scalars) == mergedO && alikeDistance(withO, scalars) == 1)) &&
                score > (oWord?.first ?: -1)
            ) oWord = score to index
        }
        for (entry in found) {
            val scalars = entry.word.scalars()
            considerO(scalars, entry.index)
            val d = if (withO != null && scalars.contentEquals(withO)) 0 else distance(target, scalars)
            if (d <= 3) topScore = maxOf(topScore, dictionary.score(entry.index))
            distances[entry.index] = Spelling(d, entry.word)
        }
        val typedHasClose = distances.values.any { it.distance <= 3 }
        val targets = HashMap<String, IntArray>()
        for (item in loose) {
            if (distances[item.entry.index] != null && typedHasClose) continue
            val t = targets.getOrPut(item.roman) { convert(item.roman).scalars() }
            // Each a read as the plain vowel costs a little; next to a close spelling as typed, it is far.
            var d = distance(t, item.entry.word.scalars()) + item.changed
            if (typedHasClose) d = maxOf(d, 4)
            if (d < (distances[item.entry.index]?.distance ?: Int.MAX_VALUE)) distances[item.entry.index] = Spelling(d, item.entry.word)
            if (d <= 3) topScore = maxOf(topScore, dictionary.score(item.entry.index))
        }
        // A sound-alike counts only when it differs from what was typed in those letters alone (ত/ট, ক/খ...):
        // the same vowels, so "bolo" never brings বলে. It is then close, one step from exact.
        for (alike in alikes) {
            if (distances[alike.index] != null) continue
            val scalars = alike.word.scalars()
            val merged = SoundKey.merged(scalars)
            val d = when {
                merged == mergedTarget -> alikeDistance(target, scalars)
                merged == mergedO && withO != null -> alikeDistance(withO, scalars)
                else -> continue
            }
            distances[alike.index] = Spelling(d, alike.word)
            if (d <= 3) topScore = maxOf(topScore, dictionary.score(alike.index))
            considerO(scalars, alike.index)
        }
        // Casual spellings of what Avro writes otherwise: "jachi" and "jachhi" for যাচ্ছি (Avro: "jacchi"),
        // "bebohar" for ব্যবহার, "purbo" for পূর্ব (see casualReadings). Those readings are looked up too,
        // sound-alikes included (same vowels only), one step farther when they cost one, and compete by how
        // common they are: যাচ্ছি beats the rare যাচি.
        for (reading in casualReadings(word)) {
            val t = convert(reading.roman).scalars()
            val merged = SoundKey.merged(t)
            val vowels = vowels(t)
            for (entry in dictionary.candidateEntries(reading.roman) + dictionary.soundAlikes(reading.roman, limit = 50)) {
                val scalars = entry.word.scalars()
                // The reading only guesses consonants: the vowels stay as typed ("pachi" is পাচ্ছি, not পাচ্ছে).
                if (vowels(scalars) != vowels) continue
                // Measured against the reading, and against what was typed when its vowels agree too.
                var d = distance(t, scalars)
                if (vowels(target) == vowels) d = minOf(d, distance(target, scalars))
                if (SoundKey.merged(scalars) == merged) d = minOf(d, alikeDistance(t, scalars))
                d += reading.changed
                // Only close spellings: a reading is a guess, its far matches would only crowd the bar.
                if (d > 3 || d >= (distances[entry.index]?.distance ?: Int.MAX_VALUE)) continue
                distances[entry.index] = Spelling(d, entry.word)
                topScore = maxOf(topScore, dictionary.score(entry.index))
            }
        }
        val ranked = distances.map { (index, value) ->
            val score = dictionary.score(index)
            val d = value.distance
            Ranked(d > 3, if (d > 3) score else score - CLOSENESS * d, d, index, value.word)
        }.sortedWith(
            compareBy<Ranked> { it.far }.thenByDescending { it.key }.thenBy { it.distance }.thenBy { it.index },
        ).toMutableList()
        // A final "o" means the ো spelling: "bolo" -> বলো, "koro" -> করো ("bol" stays বল). Unless a close
        // spelling is far more common: কেন for "keno" (not কেনো), বড় for "boro" (not বরো).
        val o = oWord
        if (o != null && o.first + O_MARGIN >= topScore) {
            val at = ranked.indexOfFirst { it.index == o.second }
            if (at >= 0) ranked.add(0, ranked.removeAt(at))
        }
        // A lone consonant + "i" means the vowel sign: "ni" -> নি, then নী, ahead of নই. ("I" -> নী, then নি.)
        // Longer words keep the frequency order ("bari" -> বাড়ি, not বারি).
        val last = target.lastOrNull()
        if (last != null && (last == SHORT_I || last == LONG_I) && word.dropLast(1).none { it in "aeiouAEIOU" }) {
            val other = target.copyOf(target.size - 1) + (if (last == SHORT_I) LONG_I else SHORT_I)
            for (spelling in listOf(other, target)) {
                val at = ranked.indexOfFirst { it.word.scalars().contentEquals(spelling) }
                if (at >= 0) ranked.add(0, ranked.removeAt(at))
            }
        }
        return ranked
    }

    /** A spelling of the input to look up too, and what it costs. */
    data class Reading(val roman: String, val changed: Int)

    companion object {
        /**
         * [word] with the a's that may stand for the plain vowel read as "o" ("shatabdi" -> "shotabdi",
         * "shatobdi", "shotobdi"), with how many were changed. Only an "a" between two consonant letters, at
         * most the first three of them, and a last "a" after two consonant sounds ("satya" -> "satyo"); a first
         * "a" or one after a single consonant is আ / া ("amar", "kotha").
         */
        fun plainVowelReadings(word: String): List<Reading> {
            val letters = word.toCharArray()
            fun consonant(c: Char) = c.code < 128 && c.isLetter() && c !in "aeiouAEIOU"
            val spots = ArrayList<Int>()
            for (i in letters.indices) {
                if (letters[i] == 'a' && i > 0 && i + 1 < letters.size && consonant(letters[i - 1]) && consonant(letters[i + 1])) {
                    spots.add(i)
                    if (spots.size == 3) break
                }
            }
            // A last "a" after two consonant sounds is often the plain vowel too: "satya" (সত্য), "dharma"
            // (ধর্ম), "grantha" (গ্রন্থ); after one it is া ("kotha" is কথা).
            if (spots.size < 3 && letters.size > 3 && letters.last() == 'a') {
                var sounds = 0
                var j = letters.size - 2
                while (j >= 0 && consonant(letters[j])) {
                    if (!(letters[j] == 'h' && j > 0 && letters[j - 1] in "kgcjtdpbsSTDR")) sounds += 1
                    j -= 1
                }
                if (sounds >= 2 && j >= 0) spots.add(letters.size - 1)
            }
            if (spots.isEmpty()) return emptyList()
            return (1 until (1 shl spots.size)).map { mask ->
                val out = letters.copyOf()
                var changed = 0
                spots.forEachIndexed { bit, i ->
                    if (mask and (1 shl bit) != 0) {
                        out[i] = 'o'
                        changed += 1
                    }
                }
                Reading(String(out), changed)
            }
        }

        /**
         * [word] as people casually spell what Avro writes otherwise, with what each reading costs (at most
         * eight readings; one change per reading, plus the verb endings): a doubled letter left out ("hoche" ->
         * হচ্ছে), য-ফলা ("bebohar" -> ব্যবহার), রেফ ("purbo" -> পূর্ব), "ri" -> ৃ, "gy" -> জ্ঞ, ঐ/ঔ spellings,
         * a র-ফলা left out ("pothom" -> প্রথম), and chat verb endings ("s" for ছ, "ie" for "iye": "diesi" -> দিয়েছি).
         */
        fun casualReadings(word: String): List<Reading> {
            // Chat spellings of verb endings come on top of the rest: "s" for ছ ("koresi" -> "korechi", "gese"),
            // "ie" / "oe" for "iye" / "oye" ("diesi" -> "diyechi", "hoechilo").
            val bases = arrayListOf(Reading(word, 0))
            verbS(word)?.let { bases.add(Reading(it, 1)) }
            for (base in bases.toList()) {
                val y = base.roman.replace("ie", "iye").replace("oe", "oye")
                if (y != base.roman && y.length > 3) bases.add(Reading(y, base.changed))
            }
            val out = ArrayList<Reading>()
            for (base in bases) {
                if (base.roman != word) out.add(base)
                for (r in spotReadings(base.roman)) {
                    if (out.none { it.roman == r.roman } && r.roman != word) out.add(Reading(r.roman, r.changed + base.changed))
                }
            }
            return out.take(8)
        }

        /** [word] with the "s" of a verb ending read as "ch" (-se, -si, -silo, -silam ...), or null. */
        private fun verbS(word: String): String? {
            val i = word.lastIndexOf('s')
            if (i <= 1 || word[i - 1] == 's' || word[i - 1] == 'h') return null
            val rest = word.substring(i + 1)
            if (rest !in listOf("e", "i", "ilo", "il", "ilam", "ilen", "en", "is", "o")) return null
            return word.substring(0, i) + "ch" + rest
        }

        /** The readings that change one spot of [word] (see [casualReadings]). */
        private fun spotReadings(word: String): List<Reading> {
            val letters = word.toCharArray()
            fun vowel(i: Int) = i >= 0 && i < letters.size && letters[i] in "aeiouAEIOU"
            fun at(i: Int, c: Char) = i >= 0 && i < letters.size && letters[i] == c
            fun consonant(i: Int) = i >= 0 && i < letters.size && letters[i].code < 128 && letters[i].isLetter() &&
                !vowel(i) && letters[i] !in "rhyw"
            val out = ArrayList<Reading>()
            fun add(i: Int, removing: Int, s: String, cost: Int) {
                if (out.size >= 6) return
                val reading = String(letters, 0, i) + s + String(letters, i + removing, letters.size - i - removing)
                if (reading != word && out.none { it.roman == reading }) out.add(Reading(reading, cost))
            }
            // The first syllable: consonant letters, then its vowel.
            val v = letters.indexOfFirst { it in "aeiouAEIOU" }
            if (v > 0 && (0 until v).all { consonant(it) || at(it, 'h') } && 'y' !in letters.copyOfRange(0, v) &&
                ('r' !in letters.copyOfRange(0, v) || v == 1)
            ) {
                if (at(v, 'e') && !vowel(v + 1)) add(v, 1, "y", 1)
                if (at(v, 'a')) add(v, 1, "ya", 1)
                // A র-ফলা left out ("pothom" -> প্রথম, "gam" -> গ্রাম).
                if (v <= 2 && !at(v - 1, 'r') && letters[0] in "pbgkdtfsv") add(v, 0, "r", 1)
            }
            if (at(0, 'o') && at(1, 'i')) add(0, 2, "OI", 0)
            if (at(0, 'o') && at(1, 'u')) add(0, 2, "OU", 0)
            // ৈ / ৌ after a consonant, as people spell them: "boishak" -> বৈশাখ, "nowka" -> নৌকা, "kautuk" -> কৌতুক.
            for (j in 0 until letters.size - 1) {
                if (j <= 0 || !consonant(j - 1)) continue
                val a = letters[j]
                val b = letters[j + 1]
                when {
                    a == 'o' && b == 'i' -> add(j, 2, "OI", 1)
                    (a == 'o' && b == 'u') || (a == 'o' && b == 'w') || (a == 'a' && b == 'u') -> add(j, 2, "OU", 1)
                }
            }
            var i = 0
            while (i + 1 < letters.size) {
                if (at(i, 'c') && at(i + 1, 'h') && !at(i - 1, 'c') && vowel(i - 1)) {
                    if (at(i + 2, 'c') && at(i + 3, 'h')) {
                        add(i, 4, "cch", 0) // "jachchi", "bachcha"
                        i += 4
                    } else if (at(i + 2, 'h')) {
                        add(i, 3, "cch", 0)
                        i += 3
                    } else {
                        add(i, 2, "cch", 1)
                        add(i, 2, "cc", 1)
                        i += 2
                    }
                    continue
                }
                if (at(i, 't') && !at(i + 1, 'h') && !at(i - 1, 't') && vowel(i - 1) && vowel(i + 1)) add(i, 1, "tt", 1)
                if (letters[i] in "dbtgjp" && at(i + 1, letters[i]) && at(i + 2, 'h') && vowel(i - 1) && !at(i + 3, 'y')) {
                    add(i, 3, letters[i] + "hy", 1)
                    i += 3
                    continue
                }
                if (at(i, 'r') && at(i + 1, 'r') && consonant(i + 2)) {
                    add(i, 2, "r", 0)
                    i += 2
                    continue
                }
                if (at(i, 'r') && !at(i - 1, 'r') && vowel(i - 1) && consonant(i + 1)) add(i, 1, "rr", 1)
                if (at(i, 'r') && at(i + 1, 'i') && consonant(i - 1)) add(i, 2, "rri", 1)
                if (at(i, 'g') && at(i + 1, 'y') && !at(i - 1, 'g')) add(i, 2, "gg", 0)
                i += 1
            }
            if (letters.size > 2 && at(letters.size - 2, 'a') && at(letters.size - 1, 'e')) {
                add(letters.size - 2, 2, "ay", 0)
            } else if (letters.size > 3 && word.endsWith("aye")) {
                add(letters.size - 3, 3, "ay", 0)
            }
            return out
        }

        /**
         * The vowels of a Bangla word in order (signs and letters), to tell spellings that only differ in
         * consonants (যাচ্ছি / জাচ্ছি) from other forms of a word (যাচ্ছে). Long and short i and u count as one,
         * and ো as no vowel ("o" is both).
         */
        fun vowels(scalars: IntArray): List<Int> = scalars.asList().mapNotNull { c ->
            if (c !in 0x0985..0x0994 && c !in 0x09BE..0x09CC) return@mapNotNull null
            when (c) {
                0x0988 -> 0x0987 // ঈ -> ই
                0x098A -> 0x0989 // ঊ -> উ
                0x09C0 -> 0x09BF // ী -> ি
                0x09C2 -> 0x09C1 // ূ -> ু
                0x09CB -> null // ো: people type it as "o", Avro's plain vowel
                else -> c
            }
        }

        private val ASPIRATED = setOf(0x0996, 0x0998, 0x099B, 0x099D, 0x09A0, 0x09A5, 0x09A2, 0x09A7, 0x09AB, 0x09AD)

        /**
         * Distance of a sound-alike (same letters up to their sound group) from the spelling typed: 1 when it
         * only swaps ত/ট, ক/খ and the like; far (4) when it drops an "h" sound the user typed ("matha" is মাথা,
         * not মাতা), while adding one they left out is fine ("kabo" -> খাবো, "thik" -> ঠিক).
         */
        fun alikeDistance(typed: IntArray, word: IntArray): Int {
            val a = typed.filter { it != 0x09BC }
            val b = word.filter { it != 0x09BC }
            if (a.size != b.size) return 4
            for (k in a.indices) if (a[k] != b[k] && a[k] in ASPIRATED && b[k] !in ASPIRATED) return 4
            return 1
        }

        /** Score points (log scale, 1...255) the most common close spelling may lead by and still lose to the ো spelling for a final "o". */
        const val O_MARGIN = 60

        private const val SHORT_I = 0x09BF // ি
        private const val LONG_I = 0x09C0 // ী

        /** Score points one unit of spelling distance is worth (scores are 1...255 on a log scale). */
        const val CLOSENESS = 6

        private fun isSign(c: Int) = c in 0x09BE..0x09CC || c == 0x0982 || c == 0x0983 || c == 0x0981 || c == 0x09BC

        /**
         * Weighted edit distance: adding or dropping a vowel sign (ো in ভাল -> ভালো) is the cheapest difference,
         * swapping one vowel sign for another is next, anything else costs more.
         */
        fun distance(a: IntArray, b: IntArray): Int {
            fun gap(c: Int) = if (isSign(c)) 2 else 3
            fun swap(x: Int, y: Int) = if (x == y) 0 else if (isSign(x) && isSign(y)) 2 else 3
            var prev = IntArray(b.size + 1)
            for (j in 1..b.size) prev[j] = prev[j - 1] + gap(b[j - 1])
            var cur = prev.copyOf()
            for (i in a.indices) {
                cur[0] = prev[0] + gap(a[i])
                for (j in b.indices) {
                    cur[j + 1] = minOf(prev[j + 1] + gap(a[i]), cur[j] + gap(b[j]), prev[j] + swap(a[i], b[j]))
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.size]
        }
    }
}
