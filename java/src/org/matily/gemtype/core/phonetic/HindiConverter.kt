// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from GemType for iOS (GemTypeCore/Phonetic/HindiConverter.swift).
//
// Hindi phonetic typing: an ITRANS-style scheme (the public Indian-language transliteration
// convention: aa/ii/uu long vowels, kh gh ch chh th dh ph bh digraphs, M/H for anusvara/visarga,
// capital T D N R for retroflexes) made forgiving for casual Hinglish. The rules below are our
// own, written from the public scheme description; no third-party code or tables are used.
//
// Casual (all lower case, optionally one capital first letter):
//   - a consonant with no vowel after it keeps its inherent "a" (schwa): "ham" -> हम, "kal" -> कल
//   - a consonant followed by a consonant forms a conjunct: "namaste" -> नमस्ते, "dost" -> दोस्त
//   - a word-final "a" or "i" after a consonant is long, as people type it: "mera" -> मेरा, "hindi" -> हिंदी
//   - n/m before a stop is the anusvara: "ganga" -> गंगा; -ain/-ein/-oon/-haan end in ं/ँ
//   - a short list of very common words is spelled directly ("main" -> मैं, "nahi" -> नहीं)
// Strict ITRANS (a capital letter after the first letter, e.g. "kaTina", "paaThashaalaa"): case matters,
// no Hinglish shortcuts: "T D N R Sh Th Dh" are the retroflex/aspirated letters, "A I U" long vowels.

package org.matily.gemtype.core.phonetic

object HindiConverter {
    private sealed class Token {
        /** A consonant (with its conjunct/nukta spelling); [stop] = a stop, which makes a preceding n/m an anusvara. */
        class Consonant(val dev: String, val stop: Boolean, val nasal: Boolean) : Token()
        class Vowel(val independent: String, val matra: String) : Token()
        class Mark(val mark: String) : Token()
        class Other(val text: String) : Token()
    }

    private const val VIRAMA = "्"
    private const val NUKTA = "़"

    // ---- tables

    private val consonants: Map<String, Pair<String, Boolean>> = mapOf(
        "k" to ("क" to true), "kh" to ("ख" to true), "g" to ("ग" to true), "gh" to ("घ" to true),
        "ch" to ("च" to true), "chh" to ("छ" to true), "cch" to ("च्छ" to true), "j" to ("ज" to true), "jh" to ("झ" to true),
        "t" to ("त" to true), "th" to ("थ" to true), "d" to ("द" to true), "dh" to ("ध" to true), "n" to ("न" to false),
        "p" to ("प" to true), "ph" to ("फ" to true), "b" to ("ब" to true), "bh" to ("भ" to true), "m" to ("म" to false),
        "y" to ("य" to false), "r" to ("र" to false), "l" to ("ल" to false), "v" to ("व" to false), "w" to ("व" to false),
        "sh" to ("श" to false), "s" to ("स" to false), "h" to ("ह" to false),
        "f" to ("फ$NUKTA" to false), "z" to ("ज$NUKTA" to false), "q" to ("क$NUKTA" to true),
        "x" to ("क्स" to false), "ksh" to ("क्ष" to true), "gy" to ("ज्ञ" to true),
    )

    /** Extra spellings that only exist in strict (case-sensitive) ITRANS. */
    private val strictConsonants: Map<String, Pair<String, Boolean>> = mapOf(
        "T" to ("ट" to true), "Th" to ("ठ" to true), "D" to ("ड" to true), "Dh" to ("ढ" to true), "N" to ("ण" to false),
        "R" to ("ड$NUKTA" to false), "Rh" to ("ढ$NUKTA" to false), "Sh" to ("ष" to false), "shh" to ("ष" to false),
        "GY" to ("ज्ञ" to false), "kSh" to ("क्ष" to false), "L" to ("ळ" to false),
    )

    private val vowels: Map<String, Pair<String, String>> = mapOf(
        "a" to ("अ" to ""), "aa" to ("आ" to "ा"), "i" to ("इ" to "ि"), "ii" to ("ई" to "ी"), "ee" to ("ई" to "ी"),
        "u" to ("उ" to "ु"), "uu" to ("ऊ" to "ू"), "oo" to ("ऊ" to "ू"), "e" to ("ए" to "े"), "ai" to ("ऐ" to "ै"),
        "o" to ("ओ" to "ो"), "au" to ("औ" to "ौ"),
    )

    private val strictVowels: Map<String, Pair<String, String>> = mapOf(
        "A" to ("आ" to "ा"), "I" to ("ई" to "ी"), "U" to ("ऊ" to "ू"), "RRi" to ("ऋ" to "ृ"),
    )

    private val strictMarks: Map<String, String> = mapOf("M" to "ं", "H" to "ः")

    /** Very common words whose Hinglish spelling the letter rules cannot guess. */
    val overrides: Map<String, String> by lazy { rawOverrides().mapValues { it.value.nfc() } }

    private fun rawOverrides(): Map<String, String> = mapOf(
        "main" to "मैं", "mein" to "में", "mai" to "मैं", "me" to "में", "hain" to "हैं", "hai" to "है", "hoon" to "हूँ", "hun" to "हूँ",
        "ho" to "हो", "hu" to "हूँ", "tha" to "था", "thi" to "थी", "the" to "थे", "hum" to "हम", "ham" to "हम",
        "nahi" to "नहीं", "nahin" to "नहीं", "nhi" to "नहीं", "nai" to "नहीं", "na" to "ना",
        "kya" to "क्या", "kyun" to "क्यों", "kyon" to "क्यों", "kyunki" to "क्योंकि", "kyonki" to "क्योंकि",
        "ye" to "ये", "yeh" to "यह", "yah" to "यह", "wo" to "वो", "woh" to "वह", "vo" to "वो", "voh" to "वह",
        "yahan" to "यहाँ", "yaha" to "यहाँ", "wahan" to "वहाँ", "vahan" to "वहाँ", "waha" to "वहाँ", "kahan" to "कहाँ", "jahan" to "जहाँ",
        "koi" to "कोई", "kuch" to "कुछ", "sab" to "सब", "sabhi" to "सभी", "abhi" to "अभी", "ab" to "अब", "tab" to "तब", "jab" to "जब",
        "bhai" to "भाई", "bhi" to "भी", "aur" to "और", "aap" to "आप", "aapka" to "आपका", "aapki" to "आपकी", "aapko" to "आपको",
        "tum" to "तुम", "tu" to "तू", "tumhara" to "तुम्हारा", "tumhari" to "तुम्हारी", "tumhe" to "तुम्हें", "tumko" to "तुमको",
        "mujhe" to "मुझे", "mujhko" to "मुझको", "tujhe" to "तुझे", "mera" to "मेरा", "meri" to "मेरी", "mere" to "मेरे",
        "hamara" to "हमारा", "humara" to "हमारा", "hamari" to "हमारी", "humari" to "हमारी", "hamare" to "हमारे",
        "uska" to "उसका", "uski" to "उसकी", "unka" to "उनका", "unki" to "उनकी", "usne" to "उसने", "unhone" to "उन्होंने",
        "accha" to "अच्छा", "acha" to "अच्छा", "achha" to "अच्छा", "achchha" to "अच्छा", "achi" to "अच्छी", "achhi" to "अच्छी",
        "theek" to "ठीक", "thik" to "ठीक", "bahut" to "बहुत", "bohot" to "बहुत", "bahot" to "बहुत", "boht" to "बहुत",
        "zyada" to "ज़्यादा", "jyada" to "ज़्यादा", "thoda" to "थोड़ा", "kaisa" to "कैसा", "kaisi" to "कैसी", "kitna" to "कितना",
        "dhanyavad" to "धन्यवाद", "dhanyawad" to "धन्यवाद", "shukriya" to "शुक्रिया", "shukriyaa" to "शुक्रिया",
        "namaskar" to "नमस्कार", "chahiye" to "चाहिए", "chahie" to "चाहिए", "liye" to "लिए", "lie" to "लिए", "isliye" to "इसलिए",
        "jaise" to "जैसे", "waise" to "वैसे", "vaise" to "वैसे", "sirf" to "सिर्फ़", "mat" to "मत", "matlab" to "मतलब",
        "bilkul" to "बिल्कुल", "dost" to "दोस्त", "dil" to "दिल", "zindagi" to "ज़िंदगी", "jaldi" to "जल्दी", "baat" to "बात",
        "ghar" to "घर", "paani" to "पानी", "pani" to "पानी", "khana" to "खाना", "aaj" to "आज", "kal" to "कल", "phir" to "फिर",
        "fir" to "फिर", "chai" to "चाय", "jai" to "जय", "aaya" to "आया", "aayi" to "आई", "gaya" to "गया", "gayi" to "गई",
        "raha" to "रहा", "rahi" to "रही", "rahe" to "रहे", "kaam" to "काम", "naam" to "नाम", "log" to "लोग", "din" to "दिन",
        "ram" to "राम", "krishna" to "कृष्ण", "sita" to "सीता", "india" to "इंडिया", "delhi" to "दिल्ली", "mumbai" to "मुंबई",
        "bharat" to "भारत", "hindi" to "हिंदी", "english" to "इंग्लिश", "ok" to "ओके", "thanks" to "थैंक्स", "sorry" to "सॉरी",
        "please" to "प्लीज़", "hello" to "हैलो", "bye" to "बाय",
    )

    // ---- conversion

    /** Converts running text: letter runs (ASCII A-Z, a-z) are transliterated, everything else is kept. */
    fun convert(roman: String): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) { out.append(convertWord(run.toString())); run.setLength(0) }
        }
        for (ch in roman.scalars()) {
            if (ch in 65..90 || ch in 97..122) {
                run.appendCodePoint(ch)
            } else {
                flush()
                out.appendCodePoint(ch)
            }
        }
        flush()
        return out.toString()
    }

    fun convertWord(word: String): String {
        val strict = word.drop(1).any { it.isUpperCase() }
        val text = if (strict) word else word.lowercase()
        if (!strict) overrides[text]?.let { return it }

        // Word-final nasal vowels that people spell with a plain n.
        var tail: String? = null
        var body = text
        if (!strict) {
            for ((suffix, keep, mark) in listOf(
                Triple("aan", "aa", "ँ"), Triple("oon", "oo", "ँ"),
                Triple("ain", "ai", "ं"), Triple("ein", "e", "ं"),
            )) {
                if (body.length > suffix.length - 1 && body.endsWith(suffix) && (suffix != "aan" || body.endsWith("haan"))) {
                    body = body.dropLast(suffix.length) + keep
                    tail = mark
                    break
                }
            }
        }

        val tokens = tokenize(body.toCharArray(), strict)
        val out = StringBuilder()
        var open = false // the last thing written is a consonant that has no vowel decided yet
        for ((i, token) in tokens.withIndex()) {
            val isLast = i == tokens.size - 1 && tail == null
            when (token) {
                is Token.Consonant -> {
                    // n or m in front of a stop consonant is the anusvara ("ganga" -> गंगा).
                    if (!strict && isNasalLetter(token) && i > 0 && i + 1 < tokens.size && isStop(tokens[i + 1])) {
                        out.append("ं")
                        open = false
                        continue
                    }
                    if (open) out.append(VIRAMA)
                    out.append(token.dev)
                    open = true
                }
                is Token.Vowel -> {
                    if (open) {
                        var m = token.matra
                        if (!strict && isLast && token.matra.isEmpty()) m = "ा"            // mera, kya, ka
                        else if (!strict && isLast && token.matra == "ि") m = "ी"         // hindi, pani, ki
                        out.append(m)
                    } else {
                        out.append(token.independent)
                    }
                    open = false
                }
                is Token.Mark -> {
                    out.append(token.mark)
                    open = false
                }
                is Token.Other -> {
                    out.append(token.text)
                    open = false
                }
            }
        }
        if (tail != null) out.append(tail)
        return out.toString()
    }

    private fun isNasalLetter(t: Token): Boolean =
        t is Token.Consonant && t.nasal && (t.dev == "न" || t.dev == "म")

    private fun isStop(t: Token): Boolean = t is Token.Consonant && t.stop

    private fun tokenize(chars: CharArray, strict: Boolean): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < chars.size) {
            var matched = false
            for (length in minOf(4, chars.size - i) downTo 1) {
                val key = String(chars, i, length)
                if (strict) strictConsonants[key]?.let { c ->
                    tokens.add(Token.Consonant(c.first, stop = c.second, nasal = false)); i += length; matched = true
                }
                if (matched) break
                if (strict) strictVowels[key]?.let { v ->
                    tokens.add(Token.Vowel(v.first, v.second)); i += length; matched = true
                }
                if (matched) break
                if (strict) strictMarks[key]?.let { m ->
                    tokens.add(Token.Mark(m)); i += length; matched = true
                }
                if (matched) break
                vowels[key]?.let { v ->
                    tokens.add(Token.Vowel(v.first, v.second)); i += length; matched = true
                }
                if (matched) break
                if (key == "c") {
                    // soft c before e/i/y, otherwise k
                    val next = if (i + 1 < chars.size) chars[i + 1] else ' '
                    val soft = next == 'e' || next == 'i' || next == 'y'
                    tokens.add(Token.Consonant(if (soft) "स" else "क", stop = !soft, nasal = false)); i += 1; matched = true
                    break
                }
                consonants[key]?.let { c ->
                    tokens.add(Token.Consonant(c.first, stop = c.second, nasal = key == "n" || key == "m")); i += length; matched = true
                }
                if (matched) break
            }
            if (!matched) {
                // A capital letter we have no strict meaning for: treat as its lower-case letter.
                val lower = chars[i].lowercase()
                if (lower != chars[i].toString() && lower.length == 1 && (consonants[lower] != null || vowels[lower] != null)) {
                    val rest = chars.copyOfRange(i, chars.size)
                    rest[0] = lower[0]
                    return tokens + tokenize(rest, strict)
                }
                tokens.add(Token.Other(chars[i].toString())); i += 1
            }
        }
        return tokens
    }
}
