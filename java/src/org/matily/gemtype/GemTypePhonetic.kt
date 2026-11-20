// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import org.json.JSONArray
import org.matily.gemtype.core.PhoneticStanding
import org.matily.gemtype.core.Romanized
import org.matily.gemtype.core.phonetic.DataFile
import org.matily.gemtype.core.phonetic.PhoneticData
import org.matily.gemtype.core.phonetic.PhoneticComposing
import org.matily.gemtype.core.phonetic.PhoneticEngines
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * GemType's own phonetic engines on the device (Bangla and Hindi, as on iOS: assets/gemtype/phonetic), the
 * thread they run on, and what they tell the keyboard: the words of a romanized word in its own script
 * ([suggest]) and what a romanized word is for detection ([Lookup]).
 */
object GemTypePhonetic {
    private var engines: PhoneticEngines? = null

    /** One background thread: lookups are short, and in order. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GemTypePhonetic").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun engines(context: Context): PhoneticEngines =
        engines ?: PhoneticEngines(AssetData(context.applicationContext.assets)).also { engines = it }

    /** Languages whose words [suggest] can offer. */
    @JvmStatic
    fun hasEngine(language: String): Boolean = language in PhoneticEngines.LANGUAGES

    /** Gets the words, best first. */
    fun interface Words {
        fun onWords(words: List<String>)
    }

    /** The words [roman] may stand for in [language]'s script, best first, at most [limit]; [done] runs on the main thread. */
    @JvmStatic
    fun suggest(context: Context, language: String, roman: String, limit: Int, done: Words) {
        val app = context.applicationContext
        executor.execute {
            val words = runCatching { engines(app).engine(language)?.suggestions(roman, limit) }.getOrNull().orEmpty()
            main.post { done.onWords(words) }
        }
    }

    /**
     * The phonetic keyboard ("Bangla (Avro)", "Hindi (Transliteration)") as on iOS: the words the English
     * letters of the word being typed may be, best first (see [PhoneticComposing]): the first is what the field
     * shows and what space commits. [picked] is the user's earlier choice for these letters.
     */
    @JvmStatic
    fun candidates(context: Context, language: String, buffer: String, picked: String?, done: Words) {
        val app = context.applicationContext
        executor.execute {
            val words = runCatching {
                engines(app).engine(language)?.let { engine ->
                    PhoneticComposing.candidates(engine, buffer, engine.suggestions(buffer, PhoneticComposing.DICTIONARY_LIMIT), picked)
                }
            }.getOrNull().orEmpty()
            main.post { done.onWords(words) }
        }
    }

    /** What space commits for [buffer], now (a few milliseconds): the strip's lookup may not be in yet. */
    @JvmStatic
    fun bestNow(context: Context, language: String, buffer: String, picked: String?): String? = runCatching {
        engines(context).engine(language)?.let { PhoneticComposing.best(it, buffer, picked) }
    }.getOrNull()

    /** The rule result for [buffer] (what the field shows until the dictionary answers). */
    @JvmStatic
    fun convert(context: Context, language: String, buffer: String): String? =
        runCatching { engines(context).engine(language)?.convert(buffer) }.getOrNull()

    /** The language whose phonetic engine types for this transliteration layout, or null. */
    @JvmStatic
    fun layoutLanguage(transliterationMethod: String?): String? = when (transliterationMethod) {
        "bn-avro" -> "bn"
        "hi-transliteration" -> "hi"
        else -> null
    }

    /**
     * The [Romanized.Recognizer] for Bangla, Hindi and Urdu: asks the bundled dictionaries, off the main
     * thread, and remembers the answer. A word not looked up yet stands as NONE; [onLearned] runs once its
     * answer is in, so detection can look again. Main thread only.
     */
    class Lookup(context: Context) : Romanized.Recognizer {
        private val app = context.applicationContext

        /** Called when an answer came in that may change what detection finds. */
        var onLearned: Runnable? = null

        private val known = object : LinkedHashMap<String, Romanized.Standing>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Romanized.Standing>?) = size > CAPACITY
        }
        private val pending = HashSet<String>()

        fun handles(language: String) = language in PhoneticStanding.OWN_LANGUAGES

        override fun standing(language: String, word: String): Romanized.Standing {
            if (!handles(language) || !PhoneticStanding.eligible(word)) return Romanized.Standing.NONE
            val key = "$language|$word"
            known[key]?.let { return it }
            if (pending.size < MAX_PENDING && pending.add(key)) {
                executor.execute {
                    val standing = runCatching { PhoneticStanding.own(engines(app), language, word) }
                        .getOrDefault(Romanized.Standing.NONE)
                    main.post {
                        pending.remove(key)
                        known[key] = standing
                        if (standing != Romanized.Standing.NONE) onLearned?.run()
                    }
                }
            }
            return Romanized.Standing.NONE
        }

        private companion object {
            const val CAPACITY = 2_000
            const val MAX_PENDING = 24
        }
    }

    /**
     * The native word the user picked for a romanized spelling ("valobashi" -> ভালোবাসি): it comes first the
     * next time. Kept in the keyboard's prefs on this device; only while learning is on (the caller checks).
     */
    class Picks(private val prefs: SharedPreferences) {
        private var cache: LinkedHashMap<String, String>? = null
        private val map: LinkedHashMap<String, String> get() = cache ?: read().also { cache = it }

        // "Delete all" in the settings clears the pref: read it again then. (Prefs keep listeners weakly.)
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == PREF) cache = null
        }

        init {
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }

        fun pick(language: String, roman: String): String? = map["$language|${roman.lowercase()}"]

        fun record(language: String, roman: String, word: String) {
            val key = "$language|${roman.lowercase()}"
            if (map[key] == word) return
            map.remove(key)
            map[key] = word
            while (map.size > CAPACITY) map.remove(map.keys.first())
            val json = JSONArray()
            for ((k, v) in map) json.put(JSONArray().put(k).put(v))
            prefs.edit { putString(PREF, json.toString()) }
        }

        fun clear() {
            cache = null
            prefs.edit { remove(PREF) }
        }

        private fun read(): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            runCatching {
                val json = JSONArray(prefs.getString(PREF, null) ?: return out)
                for (i in 0 until json.length()) json.getJSONArray(i).let { out[it.getString(0)] = it.getString(1) }
            }
            return out
        }

        companion object {
            const val PREF = "gemtype_phonetic_picks"
            private const val CAPACITY = 500
        }
    }

    /** The data files in the APK. The big .bin files are stored uncompressed (build.gradle) and memory-mapped. */
    private class AssetData(private val assets: AssetManager) : PhoneticData {
        override fun open(name: String): DataFile? {
            val path = "gemtype/phonetic/$name"
            try {
                assets.openFd(path).use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.use { channel ->
                        return DataFile(channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength))
                    }
                }
            } catch (e: IOException) {
                // Compressed in the APK (the small text files): read it instead.
            }
            return try {
                assets.open(path).use { DataFile(ByteBuffer.wrap(it.readBytes())) }
            } catch (e: IOException) {
                null
            }
        }
    }
}
