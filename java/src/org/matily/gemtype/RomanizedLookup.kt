// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import org.matily.gemtype.core.PhoneticStanding
import org.matily.gemtype.core.Romanized

/**
 * The [Romanized.Recognizer] the convert pill uses on the device: asks the keyboard's phonetic engine
 * (Varnam, for the languages whose pack is installed) what a romanized word is, and remembers the answer.
 *
 * The engine answers asynchronously, so a word it has not seen yet stands as NONE the first time; the
 * lookup starts then, and [onLearned] runs once the answer is in so detection can look again. Main thread only.
 */
class RomanizedLookup(private val engine: Engine) : Romanized.Recognizer {
    /** The phonetic engine behind the English keyboard. */
    interface Engine {
        /** The language it transliterates to, or null while none is ready. */
        fun language(): String?

        /**
         * Looks [word] up and calls [done] on the main thread with the weight of its best exact
         * dictionary match (null: none). Returns false when no lookup could start.
         */
        fun lookUp(word: String, done: (Int?) -> Unit): Boolean
    }

    /** Called when an answer came in that may change what detection finds. */
    var onLearned: Runnable? = null

    private val known = object : LinkedHashMap<String, Romanized.Standing>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Romanized.Standing>?) = size > CAPACITY
    }
    private val pending = HashSet<String>()

    override fun standing(language: String, word: String): Romanized.Standing {
        if (language != engine.language() || !PhoneticStanding.eligible(word)) return Romanized.Standing.NONE
        val key = "$language|$word"
        known[key]?.let { return it }
        if (pending.size < MAX_PENDING && pending.add(key)) {
            val started = engine.lookUp(word) { weight ->
                pending.remove(key)
                val standing = PhoneticStanding.of(language, weight)
                known[key] = standing
                if (standing != Romanized.Standing.NONE) onLearned?.run()
            }
            if (!started) pending.remove(key)
        }
        return Romanized.Standing.NONE
    }

    /** The engine changed: lookups in flight will not answer. */
    fun reset() = pending.clear()

    /** The typing path already looked [word] up in [language]: keep the answer. */
    fun record(language: String, word: String, weight: Int?) {
        if (PhoneticStanding.eligible(word)) known["$language|$word"] = PhoneticStanding.of(language, weight)
    }

    private companion object {
        const val CAPACITY = 2_000
        const val MAX_PENDING = 24
    }
}
