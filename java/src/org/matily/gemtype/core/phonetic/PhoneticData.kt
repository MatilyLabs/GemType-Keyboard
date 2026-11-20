// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from GemType for iOS (GemTypeCore/Phonetic/PhoneticResources.swift, StarterWords.swift).

package org.matily.gemtype.core.phonetic

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer

/**
 * Where the bundled phonetic data comes from (assets/gemtype/phonetic on the device, the same folder on
 * disk in tests). The big word lists are memory-mapped: pages load on demand, so a 5 MB list costs far
 * less than 5 MB of keyboard memory.
 */
interface PhoneticData {
    /** The file [name], or null when it is missing. */
    fun open(name: String): DataFile?

    /** The text file [name] (UTF-8), or null. */
    fun text(name: String): String? = open(name)?.let { String(it.bytes(0, it.size), Charsets.UTF_8) }

    /** The files of a folder on disk (tests, tools). */
    class Folder(private val dir: File) : PhoneticData {
        override fun open(name: String): DataFile? {
            val file = File(dir, name)
            if (!file.isFile) return null
            return RandomAccessFile(file, "r").use { raf ->
                DataFile(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
            }
        }
    }
}

/** A read-only little-endian file. Reads are absolute, so one file can be read from any thread. */
class DataFile(buffer: ByteBuffer) {
    private val buf: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val size: Int = buf.capacity()

    fun u32(offset: Int): Int = buf.getInt(offset)
    fun u16(offset: Int): Int = buf.getShort(offset).toInt() and 0xFFFF
    fun u8(offset: Int): Int = buf.get(offset).toInt() and 0xFF

    fun tag(offset: Int): String = String(bytes(offset, 4), Charsets.US_ASCII)

    fun bytes(offset: Int, count: Int): ByteArray = ByteArray(count) { buf.get(offset + it) }
}

/**
 * One byte per character: block start + 0x00..0x7F -> 0x80..0xFF, ZWNJ -> 1, ZWJ -> 2. The mapping is
 * monotonic, so byte order equals code point order and prefixes stay prefixes.
 */
class CompactCodec(private val base: Int) {
    fun encode(s: String): ByteArray? {
        val out = ArrayList<Byte>(s.length)
        for (v in s.codePoints()) {
            when {
                v >= base && v < base + 0x80 -> out.add((0x80 + (v - base)).toByte())
                v == 0x200C -> out.add(1)
                v == 0x200D -> out.add(2)
                else -> return null
            }
        }
        return out.toByteArray()
    }

    fun decode(file: DataFile, offset: Int, count: Int): String {
        val sb = StringBuilder(count)
        for (i in 0 until count) {
            val b = file.u8(offset + i)
            when {
                b >= 0x80 -> sb.appendCodePoint(base + b - 0x80)
                b == 1 -> sb.append('‌')
                b == 2 -> sb.append('‍')
            }
        }
        return sb.toString()
    }
}

/** Levenshtein distance over code points, used to rank dictionary candidates. */
object EditDistance {
    fun of(a: IntArray, b: IntArray): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.size]
    }

    fun between(a: String, b: String): Int = of(a.scalars(), b.scalars())
}

/** The code points of a string (Swift's unicodeScalars). */
internal fun String.scalars(): IntArray = codePoints().toArray()

internal fun IntArray.asString(): String = String(this, 0, size)

internal fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

/**
 * English words and names as people type them in English letters, with their native spelling
 * ("computer" -> কম্পিউটার, "dhaka" -> ঢাকা), from <lang>-starter.tsv (written by Matily for GemType).
 * The word lists behind the phonetic engines have few of them, and the rules spell them letter by letter.
 */
class StarterWords private constructor(private val table: Map<String, List<String>>) {
    /** The words for exactly these letters (any case), best first. */
    fun words(roman: String): List<String> = table[roman.lowercase()] ?: emptyList()

    val count: Int get() = table.size

    companion object {
        /** Null when the language has no list. */
        fun load(data: PhoneticData, language: String): StarterWords? {
            val text = data.text("$language-starter.tsv") ?: return null
            val table = HashMap<String, MutableList<String>>()
            for (line in text.split('\n')) {
                if (line.startsWith("#")) continue
                val f = line.split('\t')
                if (f.size != 2 || f[0].isEmpty() || f[1].isEmpty()) continue
                table.getOrPut(f[0].lowercase()) { ArrayList() }.add(f[1].trimEnd('\r').nfc())
            }
            return StarterWords(table)
        }
    }
}

/**
 * Phonetic typing: English letters in, native script out ("ami" -> "আমি"). One engine per language;
 * rules and word lists are our own or permissively licensed. Engines are safe to call from any thread.
 */
interface PhoneticEngine {
    /** Language code ("bn", "hi"). */
    val language: String

    /** Rule-based conversion of one romanized word. Never empty for non-empty input. */
    fun convert(roman: String): String

    /** Dictionary candidates for one romanized word, best first, at most [limit]. */
    fun suggestions(roman: String, limit: Int): List<String>
}

/** The engines, created lazily from [data] and kept. */
class PhoneticEngines(private val data: PhoneticData) {
    private val cache = HashMap<String, PhoneticEngine?>()

    /** The engine for [language], or null when there is none. */
    @Synchronized
    fun engine(language: String): PhoneticEngine? {
        if (cache.containsKey(language)) return cache[language]
        val engine = when (language) {
            "bn" -> BanglaEngine(data)
            "hi" -> HindiEngine(data)
            else -> null
        }
        cache[language] = engine
        return engine
    }

    companion object {
        /** Languages that have a phonetic engine. */
        val LANGUAGES = setOf("bn", "hi")
    }
}
