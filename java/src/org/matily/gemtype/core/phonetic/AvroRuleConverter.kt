// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from GemType for iOS (GemTypeCore/Phonetic/AvroRuleConverter.swift), itself a port of the classic
// (rule-based) Avro Phonetic converter from https://github.com/mugli/libavrophonetic (rulebasedconv),
// MIT License, Copyright (c) 2021 Mehdi Hasan Khan. The rule table is data (avro-rules.json); see
// assets/gemtype/phonetic/LICENSE-libavrophonetic for the full MIT text.

package org.matily.gemtype.core.phonetic

import org.json.JSONObject

/** Rule-based Avro Phonetic: "ami" -> "আমি". */
class AvroRuleConverter private constructor(
    /** Rules grouped by the first character of their match, longest match first (the original's order). */
    private val rulesByFirst: Map<Int, List<Rule>>,
) {
    private enum class When { PREFIX, SUFFIX }
    private enum class Kind { PUNCTUATION, VOWEL, CONSONANT, EXACTLY }

    private class Condition(val whenAt: When, val kind: Kind, val isNot: Boolean, val value: IntArray)
    private class Exception(val conditions: List<Condition>, val replace: String)
    private class Rule(val match: IntArray, val replace: String, val exceptions: List<Exception>)

    fun convert(word: String): String {
        val input = fixCase(word)
        val output = StringBuilder()
        var i = 0
        while (i < input.size) {
            var matched = false
            val candidates = rulesByFirst[input[i]]
            if (candidates != null) {
                for (rule in candidates) {
                    val right = i + rule.match.size
                    if (right > input.size || !slice(input, i, right, rule.match)) continue
                    var replacement = rule.replace
                    for (exception in rule.exceptions) {
                        if (holds(exception, input, i, right)) {
                            replacement = exception.replace
                            break
                        }
                    }
                    output.append(replacement)
                    i = right
                    matched = true
                    break
                }
            }
            if (!matched) {
                output.appendCodePoint(input[i])
                i += 1
            }
        }
        return output.toString()
    }

    companion object {
        /** Null when the rule file is missing or unreadable. */
        fun load(data: PhoneticData): AvroRuleConverter? {
            val text = data.text("avro-rules.json") ?: return null
            return try {
                val rules = JSONObject(text).getJSONArray("rules")
                val groups = HashMap<Int, MutableList<Pair<Int, Rule>>>()
                for (index in 0 until rules.length()) {
                    val r = rules.getJSONObject(index)
                    val match = r.getString("m").scalars()
                    if (match.isEmpty()) continue
                    val exceptions = ArrayList<Exception>()
                    val x = r.optJSONArray("x")
                    if (x != null) {
                        for (e in 0 until x.length()) {
                            val ex = x.getJSONObject(e)
                            val c = ex.getJSONArray("c")
                            val conditions = (0 until c.length()).map { k ->
                                val cond = c.getJSONObject(k)
                                Condition(
                                    if (cond.getString("w") == "prefix") When.PREFIX else When.SUFFIX,
                                    kind(cond.getString("i")),
                                    cond.getBoolean("n"),
                                    cond.getString("v").scalars(),
                                )
                            }
                            exceptions.add(Exception(conditions, ex.getString("t")))
                        }
                    }
                    groups.getOrPut(match[0]) { ArrayList() }.add(index to Rule(match, r.getString("r"), exceptions))
                }
                val sorted = groups.mapValues { (_, list) ->
                    list.sortedWith(compareByDescending<Pair<Int, Rule>> { it.second.match.size }.thenBy { it.first })
                        .map { it.second }
                }
                AvroRuleConverter(sorted)
            } catch (e: org.json.JSONException) {
                null
            }
        }

        private fun kind(s: String) = when (s) {
            "punctuation" -> Kind.PUNCTUATION
            "vowel" -> Kind.VOWEL
            "consonant" -> Kind.CONSONANT
            else -> Kind.EXACTLY
        }

        // ---- character classes (ASCII only, as in the original)

        private fun lower(c: Int) = if (c in 65..90) c + 32 else c
        private fun isVowel(c: Int) = when (lower(c)) { 97, 101, 105, 111, 117 -> true; else -> false }
        private fun isConsonant(c: Int): Boolean {
            val l = lower(c)
            return l in 97..122 && !isVowel(l)
        }
        private fun isPunctuation(c: Int) = !isVowel(c) && !isConsonant(c)

        /** o i u d g j n r s t y z keep their case: capital and small letters are different sounds. */
        private fun isCaseSensitive(c: Int) = when (lower(c)) {
            111, 105, 117, 100, 103, 106, 110, 114, 115, 116, 121, 122 -> true
            else -> false
        }

        private fun fixCase(s: String): IntArray = s.scalars().map { if (isCaseSensitive(it)) it else lower(it) }.toIntArray()

        private fun slice(input: IntArray, from: Int, to: Int, needle: IntArray): Boolean {
            if (to - from != needle.size) return false
            for (k in needle.indices) if (input[from + k] != needle[k]) return false
            return true
        }

        /** All conditions of the exception hold at this position. */
        private fun holds(e: Exception, input: IntArray, start: Int, right: Int): Boolean {
            val left = start - 1
            for (c in e.conditions) {
                val cursor = if (c.whenAt == When.SUFFIX) right else left
                when (c.kind) {
                    Kind.PUNCTUATION -> {
                        val v = (cursor < 0 && c.whenAt == When.PREFIX) ||
                            (cursor >= input.size && c.whenAt == When.SUFFIX) ||
                            (cursor >= 0 && cursor < input.size && isPunctuation(input[cursor]))
                        if (v == c.isNot) return false
                    }
                    Kind.VOWEL -> {
                        val inRange = (cursor >= 0 && c.whenAt == When.PREFIX) || (cursor < input.size && c.whenAt == When.SUFFIX)
                        if ((inRange && isVowel(input[cursor])) == c.isNot) return false
                    }
                    Kind.CONSONANT -> {
                        val inRange = (cursor >= 0 && c.whenAt == When.PREFIX) || (cursor < input.size && c.whenAt == When.SUFFIX)
                        if ((inRange && isConsonant(input[cursor])) == c.isNot) return false
                    }
                    Kind.EXACTLY -> {
                        val s: Int
                        val end: Int
                        if (c.whenAt == When.SUFFIX) { s = right; end = right + c.value.size } else { s = start - c.value.size; end = start }
                        // Same bounds as the original, including its strict `end < len`.
                        val exact = s >= 0 && end < input.size && slice(input, s, end, c.value)
                        if (exact == c.isNot) return false
                    }
                }
            }
            return true
        }
    }
}
