// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from GemType for iOS (GemTypeCore/Phonetic/AvroDictionary.swift): dictionary-backed Avro
// suggestions, a port of the algorithm in https://github.com/mugli/libavrophonetic (databasedconv), MIT
// License, Copyright (c) 2021 Mehdi Hasan Khan. The pattern table and the word list come from that
// repository's data/ directory (see assets/gemtype/phonetic/LICENSE-libavrophonetic).
//
// Where the original walks an in-memory trie of 159k words, this walks a memory-mapped sorted word array:
// a trie node is a [lo, hi) range of words sharing a prefix. Only the handful of words that match are
// ever turned into Strings.

package org.matily.gemtype.core.phonetic

/** Memory-mapped, bytewise-sorted word list ("GTWL" file) that behaves like a trie. */
class SortedWordList private constructor(private val file: DataFile) {
    /** A trie node: words lo until hi share the first [depth] bytes. */
    data class Node(val lo: Int, val hi: Int, val depth: Int)

    val codec = CompactCodec(file.u32(8))
    val count = file.u32(12)
    private val offsets = 20
    private val blob = 20 + 4 * (count + 1)

    val root: Node get() = Node(0, count, 0)

    private fun start(i: Int) = file.u32(offsets + 4 * i)
    private fun length(i: Int) = start(i + 1) - start(i)
    private fun byte(i: Int, d: Int) = file.u8(blob + start(i) + d)

    fun word(i: Int): String = codec.decode(file, blob + start(i), length(i))

    /** The node reached by following one byte, null when no word continues that way. */
    fun child(n: Node, b: Int): Node? {
        val depth = n.depth
        var lo = n.lo
        val hi = n.hi
        // A word equal to the prefix itself sorts first and has no byte at `depth`.
        if (lo < hi && length(lo) == depth) lo += 1
        if (lo >= hi) return null
        var a = lo
        var z = hi
        while (a < z) { // first index with byte >= b
            val m = (a + z) ushr 1
            if (byte(m, depth) < b) a = m + 1 else z = m
        }
        val first = a
        if (first >= hi || byte(first, depth) != b) return null
        z = hi
        while (a < z) { // first index with byte > b
            val m = (a + z) ushr 1
            if (byte(m, depth) <= b) a = m + 1 else z = m
        }
        return Node(first, a, depth + 1)
    }

    /** Follow a whole pattern; null for an empty pattern or when it leaves the word list. */
    fun extend(n: Node, pattern: IntArray): Node? {
        if (pattern.isEmpty()) return null
        var node = n
        for (b in pattern) node = child(node, b) ?: return null
        return node
    }

    /** True when the prefix of this node is itself a word. */
    fun isWord(n: Node) = n.depth > 0 && n.lo < n.hi && length(n.lo) == n.depth

    fun nodeWord(n: Node) = word(n.lo)

    /** Index of the word a node stands for (the node must satisfy [isWord]: it sorts first). */
    fun nodeIndex(n: Node) = n.lo

    companion object {
        fun of(file: DataFile): SortedWordList? {
            if (file.size < 20 || file.tag(0) != "GTWL" || file.u32(4) != 1) return null
            val list = SortedWordList(file)
            return if (list.blob + file.u32(16) <= file.size) list else null
        }
    }
}

/**
 * Word-frequency scores ("GTWF" file): one byte per word of the word list, in its order. 0 = unknown,
 * 1...255 = how common the word is on a log scale.
 */
class WordFrequencies private constructor(private val file: DataFile) {
    fun score(index: Int): Int = file.u8(12 + index)

    companion object {
        /** Null unless the file is a GTWF v1 file with exactly one score per word of the list. */
        fun of(file: DataFile, wordCount: Int): WordFrequencies? {
            if (file.size < 12 || file.tag(0) != "GTWF" || file.u32(4) != 1 ||
                file.u32(8) != wordCount || file.size < 12 + wordCount
            ) return null
            return WordFrequencies(file)
        }
    }
}

/** The "GTPT" pattern table: for each romanized chunk ("bh", "a", "o", ...) the Bangla spellings it may stand for, in priority order. */
class AvroPatternTable private constructor(private val file: DataFile) {
    class Block(val optional: Boolean, val patterns: List<IntArray>)

    private val directory = HashMap<String, Int>() // key -> payload offset
    private val paths = HashSet<String>() // every prefix of every key (the pattern trie's nodes)
    val common = ArrayList<IntArray>()
    private val blocks = HashMap<String, Block>()

    init {
        val keyCount = file.u32(12)
        val commonCount = file.u32(16)
        var p = 20
        repeat(commonCount) {
            val n = file.u8(p); p += 1
            common.add(IntArray(n) { file.u8(p + it) })
            p += n
        }
        repeat(keyCount) {
            val n = file.u8(p); p += 1
            val key = String(file.bytes(p, n), Charsets.UTF_8)
            p += n
            directory[key] = file.u32(p); p += 4
            for (l in 1..n) paths.add(key.substring(0, l))
        }
    }

    fun isKey(s: String) = directory.containsKey(s)
    fun isPath(s: String) = paths.contains(s)

    @Synchronized
    fun block(key: String): Block? {
        blocks[key]?.let { return it }
        var p = directory[key] ?: return null
        val optional = file.u8(p) and 1 == 1
        val count = file.u16(p + 1)
        p += 3
        val patterns = ArrayList<IntArray>(count)
        repeat(count) {
            val n = file.u8(p); p += 1
            patterns.add(IntArray(n) { file.u8(p + it) })
            p += n
        }
        val b = Block(optional, patterns)
        blocks[key] = b
        return b
    }

    companion object {
        fun of(file: DataFile): AvroPatternTable? =
            if (file.size >= 20 && file.tag(0) == "GTPT" && file.u32(4) == 1) AvroPatternTable(file) else null
    }
}

/** Sound-key index ("GTSK" file): word-list indices sorted by [SoundKey] key, most common word first within a key. */
class SoundKeyIndex private constructor(private val file: DataFile) {
    val count = file.u32(8)
    private val indices = 16 + 4 * (count + 1)
    private val blob = indices + 4 * count

    private fun keyStart(i: Int) = file.u32(16 + 4 * i)
    private fun keyEnd(i: Int) = file.u32(16 + 4 * (i + 1))

    fun key(i: Int): String = String(file.bytes(blob + keyStart(i), keyEnd(i) - keyStart(i)), Charsets.UTF_8)
    fun wordIndex(i: Int) = file.u32(indices + 4 * i)

    private fun compare(i: Int, b: ByteArray): Int {
        val start = blob + keyStart(i)
        val len = keyEnd(i) - keyStart(i)
        for (k in 0 until minOf(len, b.size)) {
            val x = file.u8(start + k)
            val y = b[k].toInt() and 0xFF
            if (x != y) return if (x < y) -1 else 1
        }
        return if (len == b.size) 0 else if (len < b.size) -1 else 1
    }

    /** Word-list indices of the words with exactly this key, most common first, at most [limit]. */
    fun words(key: String, limit: Int): List<Int> {
        val target = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (compare(mid, target) < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<Int>()
        var i = lo
        while (i < count && out.size < limit && compare(i, target) == 0) {
            out.add(wordIndex(i))
            i += 1
        }
        return out
    }

    companion object {
        fun of(file: DataFile): SoundKeyIndex? {
            if (file.size < 16 || file.tag(0) != "GTSK" || file.u32(4) != 1) return null
            val index = SoundKeyIndex(file)
            return if (index.blob + file.u32(12) <= file.size) index else null
        }
    }
}

/** libavrophonetic's `databasedconv.Converter.ConvertWord`. */
class AvroDictionary private constructor(
    private val words: SortedWordList,
    private val patterns: AvroPatternTable,
    private val frequencies: WordFrequencies?, // optional: ranking just loses its tie-breaker without it
    private val soundKeys: SoundKeyIndex?, // optional: without it only the Avro patterns find words
) {
    /** A dictionary word and its index in the word list. */
    data class Entry(val word: String, val index: Int)

    /** Words that sound like [roman] whatever letters were used (see [SoundKey]), most common first. */
    fun soundAlikes(roman: String, limit: Int = 200): List<Entry> {
        val keys = soundKeys ?: return emptyList()
        val seen = HashSet<Int>()
        val out = ArrayList<Entry>()
        for (key in SoundKey.romanKeys(roman)) {
            for (i in keys.words(key, limit)) {
                if (i < words.count && seen.add(i)) out.add(Entry(words.word(i), i))
            }
        }
        return out
    }

    /** How common a word is, 0...255 (0 = unknown, or no frequency file). */
    fun score(index: Int): Int {
        val f = frequencies ?: return 0
        return if (index >= 0 && index < words.count) f.score(index) else 0
    }

    val hasFrequencies: Boolean get() = frequencies != null
    val soundKeyIndex: SoundKeyIndex? get() = soundKeys
    val wordCount: Int get() = words.count
    fun word(index: Int): String = words.word(index)

    /**
     * The pattern-trie match at the start of [s]: the longest pattern key. libavrophonetic walks the key trie
     * as far as it goes; a few trie nodes ("jn", "rr", "gd", ...) are not keys themselves, in which case we
     * fall back to the longest real key (the original gives up there).
     */
    private fun nextKey(s: IntArray, from: Int): Pair<String, Int>? {
        val prefix = StringBuilder()
        var path = 0
        for (i in from until s.size) {
            val next = prefix.toString() + s[i].toChar()
            if (!patterns.isPath(next)) break
            prefix.append(s[i].toChar())
            path += 1
        }
        var l = path
        while (l > 0) {
            val k = prefix.substring(0, l)
            if (patterns.isKey(k)) return k to l
            l -= 1
        }
        return null
    }

    /** Candidate Bangla words for a romanized word, in discovery order (unique). */
    fun candidates(input: String, limit: Int = 400): List<String> = candidateEntries(input, limit).map { it.word }

    /** The same candidates with their index in the word list (for [score]). */
    fun candidateEntries(input: String, limit: Int = 400): List<Entry> {
        val word = fixString(input)
        if (word.isEmpty()) return emptyList()

        var nodes = ArrayList<SortedWordList.Node>()
        var seen = HashSet<SortedWordList.Node>()
        fun add(n: SortedWordList.Node?) {
            if (n != null && seen.add(n)) nodes.add(n)
        }
        fun addCommonSuffixes() {
            val extra = ArrayList<SortedWordList.Node>()
            for (node in nodes) for (c in patterns.common) words.extend(node, c)?.let { extra.add(it) }
            for (n in extra) add(n)
        }

        var at = 0
        var first = true
        while (at < word.size) {
            val next = nextKey(word, at)
            val block = next?.let { patterns.block(it.first) }
            if (next == null || block == null) {
                at += 1 // Not a letter we know (cannot happen for a-z): skip it.
                continue
            }
            at += next.second
            if (first) {
                first = false
                for (p in block.patterns) {
                    add(words.extend(words.root, p))
                    // As in the original, the optional diacritics are tried after every pattern, on everything found so far.
                    addCommonSuffixes()
                }
            } else {
                val nextNodes = ArrayList<SortedWordList.Node>()
                val nextSeen = HashSet<SortedWordList.Node>()
                for (p in block.patterns) {
                    for (node in nodes) {
                        val n = words.extend(node, p)
                        if (n != null && nextSeen.add(n)) nextNodes.add(n)
                    }
                }
                if (block.optional) {
                    for (n in nextNodes) add(n)
                } else {
                    nodes = nextNodes
                    seen = nextSeen
                }
                addCommonSuffixes()
            }
            if (nodes.isEmpty()) return emptyList()
            if (nodes.size > MAX_NODES) nodes = ArrayList(nodes.subList(0, MAX_NODES))
        }

        val result = ArrayList<Entry>()
        val unique = HashSet<String>()
        for (n in nodes) {
            if (!words.isWord(n)) continue
            val w = words.nodeWord(n)
            if (unique.add(w)) {
                result.add(Entry(w, words.nodeIndex(n)))
                if (result.size >= limit) break
            }
        }
        return result
    }

    companion object {
        /** Safety valve: a long, very ambiguous input must not stall the keyboard. */
        private const val MAX_NODES = 3000

        /** Null when the word list or the pattern table is missing. [useFrequencies] false ignores avro-freq.bin (tests). */
        fun load(data: PhoneticData, useFrequencies: Boolean = true): AvroDictionary? {
            val words = data.open("avro-words.bin")?.let(SortedWordList::of) ?: return null
            val patterns = data.open("avro-patterns.bin")?.let(AvroPatternTable::of) ?: return null
            val frequencies = if (useFrequencies) data.open("avro-freq.bin")?.let { WordFrequencies.of(it, words.count) } else null
            val soundKeys = data.open("avro-soundkeys.bin")?.let(SoundKeyIndex::of)
            return AvroDictionary(words, patterns, frequencies, soundKeys)
        }

        /**
         * The original `fixString`: keep only ASCII letters, lower-case them, except an `o` at the start of the
         * input or after a non-letter, which stays capital (it means "অ/ও" there).
         */
        fun fixString(input: String): IntArray {
            val src = input.trim().scalars()
            fun isLetter(c: Int) = c in 65..90 || c in 97..122
            val out = ArrayList<Int>()
            for (i in src.indices) {
                val c = src[i]
                if (!isLetter(c)) continue
                var ch = c
                val isO = ch == 111 || ch == 79
                if (isO && (i == 0 || !isLetter(src[i - 1]))) ch = 79 // 'O'
                else if (ch in 65..90) ch += 32
                out.add(ch)
            }
            return out.toIntArray()
        }
    }
}
