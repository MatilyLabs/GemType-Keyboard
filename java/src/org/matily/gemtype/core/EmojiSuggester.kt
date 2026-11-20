// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * The emoji the suggestion strip offers beside the words just typed ("haha" → 😂, "I am crying" → 😢,
 * "lots of fun" → 😄). Tapping it adds the emoji after the word; the word itself stays.
 *
 * Only words that clearly carry a feeling or a thing are listed, in English, in Bangla and Hindi typed
 * in English letters, and in Bangla and Hindi script, so everyday words ("the", "good") never show one.
 */
object EmojiSuggester {

    /** How far back [suggest] looks: enough for the last three words. */
    const val TAIL_CHARS = 48

    /** One line per emoji: the emoji, then its words and phrases. */
    private const val TABLE = """
😂: haha, hahaha, lol, lolz, funny, hilarious, rofl, lmao, lmfao, joking, kidding, hasi, hashi, hasir, hashir, হাহা, হাসি, হাসির, हाहा, हंसी
🤣: dying laughing, rolling on the floor, ded, dead lol
😄: fun, happy, yay, yaay, glad, cheerful, lots of fun, so much fun, moja, mojar, mozar, maza, mazaa, mazza, mast, masti, anondo, ananda, khushi, khusi, khush, মজা, মজার, আনন্দ, খুশি, मज़ा, मजा, मस्ती, खुश, खुशी
😊: smile, smiling, blush, sweet, pleased, grateful, nice one
😍: beautiful, gorgeous, adorable, cute, stunning, sundor, shundor, sundar, khoobsurat, সুন্দর, सुंदर, खूबसूरत
❤️: love, loving, lovely, love you, love u, luv, luv u, i love you, valobashi, bhalobashi, valobasi, bhalobasi, bhalobasha, valobasa, pyaar, pyar, ishq, mohabbat, ভালোবাসি, ভালোবাসা, প্রেম, प्यार, इश्क, मोहब्बत
😘: kiss, kisses, muah, mwah, xoxo
🥰: in love, crush
😢: cry, crying, cried, cries, tears, sad, unhappy, upset, heartbroken, miss you, missing you, miss u, kanna, kannakati, kandchi, kandi, kadchi, kadi, kosto, koshto, dukkho, dukho, dukh, dukhi, rona, ro raha, ro rahi, udaas, udas, কান্না, কাঁদছি, কষ্ট, দুঃখ, মন খারাপ, रोना, दुख, दुखी, उदास
😭: sobbing, so sad, crying so much
😞: disappointed, mon kharap, mon kharap lagche, mood off
😠: angry, mad at, furious, annoyed, rag, raag, ragi, gussa, gusa, naraz, রাগ, রাগী, গুসসা, गुस्सा, नाराज़, नाराज
😡: so angry, very angry, pissed
😱: shocked, omg, oh my god, scary, scared, horror, bhoy, voy, bhoi, dar, dar gaya, ভয়, डर
😮: wow, woah, whoa, amazing, unbelievable, incredible
😴: sleep, sleepy, sleeping, tired, nap, good night sleep, ghum, ghumabo, ghumacchi, ghumachi, neend, so raha, ঘুম, नींद
🤔: hmm, hmmm, thinking, wondering, confused, bhabchi, sochta, soch raha, ভাবছি, सोच
🙏: thanks, thank you, thank u, thankyou, thx, please, pls, plz, grateful, pray, praying, blessed, dhonnobad, dhonnobaad, dhonyobad, dhonyabad, donnobad, shukriya, sukriya, dhanyavad, dhanyavaad, dhanyawad, ধন্যবাদ, शुक्रिया, धन्यवाद
👍: okay, okk, sure thing, agreed, thik ache, thikache, thik ase, theek hai, thik hai, ঠিক আছে, ठीक है
👏: congrats, congratulations, well done, bravo, proud of you, ovinondon, obhinondon, abhinandan, badhai, অভিনন্দন, बधाई
🎉: party, celebrate, celebration, hurray, hooray, woohoo
🎂: birthday, happy birthday, bday, happy bday, hbd, jonmodin, shuvo jonmodin, subho jonmodin, janamdin, janmadin, জন্মদিন, शुभ जन्मदिन, जन्मदिन
🔥: fire, lit, awesome, epic, hot, osthir, oshthir, jhakkas, zabardast, jabardast, অস্থির, ज़बरदस्त, जबरदस्त
💯: perfect, hundred percent, exactly, absolutely
😎: cool, swag, chill, chilling
🤗: hug, hugs, hugging
🥺: please please, puppy eyes, aww, awww
😅: oops, awkward, phew, sorry lol
😔: sorry, my bad, maaf, maf, khoma, kkhoma, ক্ষমা, माफ़, माफ
🤒: sick, fever, ill, unwell, jor, jwor, osukh, bukhar, জ্বর, অসুখ, बुखार
🤞: good luck, fingers crossed, best of luck
💪: strong, strength, gym, workout, power
☀️: good morning, morning, sunny, suprobhat, shuvo shokal, subho sokal, শুভ সকাল, সুপ্রভাত, सुप्रभात
🌙: good night, goodnight, gn, night night, shuvo ratri, subho ratri, shubh ratri, শুভ রাত্রি, शुभ रात्रि
🌧️: rain, raining, rainy, bristi, brishti, barish, baarish, বৃষ্টি, बारिश
☕: coffee, tea, cha, chai, চা, चाय
🍕: pizza
🍔: burger
🍛: food, lunch, dinner, biryani, khabar, khabo, khaoa, khawa, bhat, khana, khaana, খাবার, খাওয়া, ভাত, खाना
🍰: cake
⚽: football, soccer
🏏: cricket
✈️: travel, flight, trip, vacation
🏠: home, bari, basa, ghar, বাড়ি, বাসা, घर
📚: study, studying, exam, exams, homework, porashona, porchi, padhai, পড়াশোনা, পরীক্ষা, पढ़ाई, परीक्षा
💼: work, office, meeting, job
💰: money, cash, salary, taka, poisa, paisa, টাকা, पैसा
🎵: music, song, songs, gaan, gana, গান, गाना
🎮: game, gaming, games
🙄: whatever, eye roll, seriously
😏: smirk, wink wink
😉: wink, just kidding, jk
👋: hello, hi there, hey there, bye, goodbye, see you, see ya, tata, alvida, আসসালামু আলাইকুম
🕌: eid, eid mubarak, ঈদ মোবারক, ঈদ, ईद मुबारक, ईद
🪔: diwali, happy diwali, दीपावली, दिवाली
🌸: puja, pujo, durga puja, পূজা, পুজো
💔: broken heart, breakup, heartbreak
🤝: friends, friend, friendship, bondhu, dost, dosti, বন্ধু, दोस्त, दोस्ती
👶: baby, babies
🐶: dog, puppy
🐱: cat, kitten
"""

    private val table: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (line in TABLE.lines()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val emoji = line.substring(0, colon).trim()
            for (phrase in line.substring(colon + 1).split(',')) {
                val key = normalize(phrase)
                if (key.isNotEmpty()) map.putIfAbsent(key, emoji)
            }
        }
        map
    }

    /** "haha", "hahahaha", "hehe", "hihi" (and Bangla/Hindi script laughs are in the table). */
    private val LAUGH = Regex("^(?:a?(?:ha){2,}h?|(?:he){2,}h?|(?:hi){3,}|(?:ja){3,}|(?:ah){3,})$")
    private val LOL = Regex("^(?:lo+l+(?:o+l+)*|lmf?a+o+|rofl+)$")

    /** The emoji for the words right before the cursor in [textBefore] (finished or still being typed), or null. */
    fun suggest(textBefore: CharSequence): String? {
        val tail = textBefore.takeLast(TAIL_CHARS).toString().trimEnd()
        // "haha!" counts; "haha 😂" or "haha:" does not (an emoji is already there).
        val body = tail.trimEnd('!', '?', '.', ',')
        if (body.isEmpty() || !body.last().isLetter() && !isMark(body.last())) return null
        val words = body.split(' ', '\n', '\t').filter { it.isNotEmpty() }.takeLast(3).map(::normalize)
        if (words.isEmpty() || words.last().isEmpty()) return null
        // The longest phrase wins: "so much fun" before "fun", "good night" before "night".
        for (n in words.size downTo 2) {
            if (words.takeLast(n).any { it.isEmpty() }) continue
            table[words.takeLast(n).joinToString(" ")]?.let { return it }
        }
        return forWord(words.last())
    }

    private fun forWord(word: String): String? {
        table[word]?.let { return it }
        if (LAUGH.matches(word) || LOL.matches(word)) return table.getValue("haha")
        // Stretched words: "loveeee", "sooo saddd", "yaaay".
        val twice = word.replace(Regex("(.)\\1{2,}"), "$1$1")
        if (twice != word) table[twice]?.let { return it }
        val once = word.replace(Regex("(.)\\1+"), "$1")
        if (once != word) table[once]?.let { return it }
        return null
    }

    /** Lower case, letters and marks only (so "Haha!!" and "haha" are one key; apostrophes go). */
    private fun normalize(text: String): String =
        text.trim().lowercase().split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.filter { it.isLetter() || isMark(it) } }

    /** A combining mark, as in Bangla and Devanagari vowel signs (they end many words). */
    private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
