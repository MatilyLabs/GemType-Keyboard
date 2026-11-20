// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from GemType for iOS (Keyboard/Typing/PhoneticComposer.swift: ranked, best, spelledOut, spelled).

package org.matily.gemtype.core.phonetic

/**
 * The words a phonetic keyboard offers for the English letters of the word being typed ("khabo"), best first:
 * the first is what the field shows and what space commits (খাবো).
 *
 * Best first: the word the user picked before for these letters, then the dictionary's words, then the plain
 * rule result. A capital letter that changes the sound, or an Avro mark (^ `), is the user spelling the word
 * out ("bolO" = বলো): then the rule result comes first, and the dictionary words stay in the strip.
 */
object PhoneticComposing {
    /** Dictionary words asked for per word. */
    const val DICTIONARY_LIMIT = 10

    /** [dictionary]: the engine's suggestions for [buffer]; [picked]: the user's earlier choice for it. */
    fun candidates(engine: PhoneticEngine, buffer: String, dictionary: List<String>, picked: String?): List<String> {
        val converted = engine.convert(buffer).nfc()
        val list = LinkedHashSet<String>()
        if (spelledOut(engine, buffer, dictionary)) list.add(spelled(engine, buffer, dictionary))
        if (picked != null) list.add(picked)
        list.addAll(dictionary)
        list.add(converted)
        return list.filter { it.isNotEmpty() }
    }

    /** What space commits for [buffer] (the first of [candidates]). */
    fun best(engine: PhoneticEngine, buffer: String, picked: String?): String =
        candidates(engine, buffer, engine.suggestions(buffer, DICTIONARY_LIMIT), picked).firstOrNull() ?: engine.convert(buffer)

    /** Capital letters Avro reads as another sound than the small one (T = ট, O = ও, I = ঈ, S = শ, ...). */
    private const val AVRO_CAPITALS = "OIUDGJNRSTYZ"

    private fun soundsDifferent(engine: PhoneticEngine, capital: Char) = engine.language != "bn" || capital in AVRO_CAPITALS

    /**
     * The user typed one of Avro's marks (^ or `), or a capital letter that changes the sound: an exact spelling.
     * A capital only at the start is mostly the start of a sentence: in Bangla it counts when the word it
     * spells comes before the small-letter one ("Taka" -> টাকা, "Thik" -> ঠিক), not otherwise ("Tumi" -> তুমি).
     */
    fun spelledOut(engine: PhoneticEngine, buffer: String, dictionary: List<String>): Boolean {
        if (buffer.any { it == '^' || it == '`' }) return true
        val capitals = buffer.indices.filter { buffer[it].isUpperCase() && soundsDifferent(engine, buffer[it]) }
        val first = capitals.firstOrNull() ?: return false
        if (first != 0 || capitals.size > 1) return true
        if (engine.language != "bn") return false
        val spelled = dictionary.indexOf(engine.convert(buffer).nfc())
        if (spelled < 0) return false
        val small = dictionary.indexOf(engine.convert(buffer.take(1).lowercase() + buffer.drop(1)).nfc())
        return spelled < (if (small < 0) Int.MAX_VALUE else small)
    }

    /** The word as spelled out: the rule result, with a final small "o" as ো when that is a real word ("ag`lano" -> আগলানো). */
    private fun spelled(engine: PhoneticEngine, buffer: String, dictionary: List<String>): String {
        val exact = engine.convert(buffer).nfc()
        if (engine.language == "bn" && buffer.endsWith("o") && (exact + "ো").nfc() in dictionary) return (exact + "ো").nfc()
        return exact
    }
}
