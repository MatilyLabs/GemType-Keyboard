// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
// Ported from GemType for iOS (GemTypeCore/Phonetic/HindiDictionary.swift).
//
// Hindi word list: 196k common words from udgamlabs/lekhani's words.bin (CC0 1.0, derived from IndicCorp v2
// word counts), indexed by HindiKey. Memory-mapped; see tools/build_hindi_data.py (GemType for iOS) for the layout.

package org.matily.gemtype.core.phonetic

class HindiDictionary private constructor(
    private val file: DataFile,
    private val codec: CompactCodec,
    val wordCount: Int,
    val keyCount: Int,
    // byte offsets of the tables inside the file
    private val wordOffsets: Int,
    private val wordBlob: Int,
    private val bands: Int,
    private val keyOffsets: Int,
    private val keyBlob: Int,
    private val postStart: Int,
    private val postings: Int,
) {
    fun word(id: Int): String {
        val s = file.u32(wordOffsets + 4 * id)
        val e = file.u32(wordOffsets + 4 * (id + 1))
        return codec.decode(file, wordBlob + s, e - s)
    }

    fun band(id: Int): Int = file.u8(bands + id)

    private fun keyStart(i: Int): Int = keyBlob + file.u32(keyOffsets + 4 * i)

    private fun keyLength(i: Int): Int = file.u32(keyOffsets + 4 * (i + 1)) - file.u32(keyOffsets + 4 * i)

    /** Index of the first key >= [key] (bytewise). */
    private fun lowerBound(key: ByteArray): Int {
        var lo = 0
        var hi = keyCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (less(mid, key)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Key [i] < [b], bytewise (unsigned). */
    private fun less(i: Int, b: ByteArray): Boolean {
        val start = keyStart(i)
        val count = keyLength(i)
        val n = minOf(count, b.size)
        for (j in 0 until n) {
            val x = file.u8(start + j)
            val y = b[j].toInt() and 0xFF
            if (x != y) return x < y
        }
        return count < b.size
    }

    /** Key [i] starts with [b] (all of its first b.size bytes equal). */
    private fun hasPrefix(i: Int, b: ByteArray): Boolean {
        if (keyLength(i) < b.size) return false
        val start = keyStart(i)
        for (j in b.indices) if (file.u8(start + j) != (b[j].toInt() and 0xFF)) return false
        return true
    }

    private fun posts(keyIndex: Int): List<Int> {
        val s = file.u32(postStart + 4 * keyIndex)
        val e = file.u32(postStart + 4 * (keyIndex + 1))
        return (s until e).map { file.u32(postings + 4 * it) }
    }

    /** Word ids whose key is exactly [key], best band first. */
    fun exact(key: String): List<Int> {
        val bytes = key.toByteArray(Charsets.UTF_8)
        val i = lowerBound(bytes)
        if (i >= keyCount || keyLength(i) != bytes.size || !hasPrefix(i, bytes)) return emptyList()
        return posts(i)
    }

    /** Word ids of keys that start with [prefix] (but are longer), scanning at most [maxKeys] keys. */
    fun completions(prefix: String, maxKeys: Int): List<Int> {
        val bytes = prefix.toByteArray(Charsets.UTF_8)
        var i = lowerBound(bytes)
        val ids = ArrayList<Int>()
        var scanned = 0
        while (i < keyCount && scanned < maxKeys) {
            if (!hasPrefix(i, bytes)) break
            if (keyLength(i) > bytes.size) ids.addAll(posts(i))
            i += 1
            scanned += 1
        }
        return ids
    }

    companion object {
        /** The word list in [data], or null when hindi-words.bin is missing or invalid. Never throws. */
        fun load(data: PhoneticData): HindiDictionary? = try {
            data.open("hindi-words.bin")?.let { parse(it) }
        } catch (e: Exception) {
            null
        }

        private fun parse(f: DataFile): HindiDictionary? {
            if (f.size < 32 || f.tag(0) != "GTHW" || f.u32(4) != 1) return null
            fun u(offset: Int): Long = f.u32(offset).toLong() and 0xFFFFFFFFL
            fun pad4(n: Long): Long = (n + 3) and 3L.inv()
            val wordCount = u(12)
            val wordBlobSize = u(16)
            val keyCount = u(20)
            val keyBlobSize = u(24)
            val wordOffsets = 32L
            val wordBlob = wordOffsets + 4 * (wordCount + 1)
            val bands = wordBlob + pad4(wordBlobSize)
            val keyOffsets = bands + pad4(wordCount)
            val keyBlob = keyOffsets + 4 * (keyCount + 1)
            val postStart = keyBlob + pad4(keyBlobSize)
            val postings = postStart + 4 * (keyCount + 1)
            if (postings + 4 * u(28) > f.size) return null
            return HindiDictionary(
                file = f,
                codec = CompactCodec(f.u32(8)),
                wordCount = wordCount.toInt(),
                keyCount = keyCount.toInt(),
                wordOffsets = wordOffsets.toInt(),
                wordBlob = wordBlob.toInt(),
                bands = bands.toInt(),
                keyOffsets = keyOffsets.toInt(),
                keyBlob = keyBlob.toInt(),
                postStart = postStart.toInt(),
                postings = postings.toInt(),
            )
        }
    }
}
