// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.os.Handler
import android.os.Looper
import com.android.inputmethod.latin.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matily.gemtype.core.Action
import org.matily.gemtype.core.Correction
import org.matily.gemtype.core.Corrections
import org.matily.gemtype.core.EmojiSuggester
import org.matily.gemtype.core.GeminiException
import org.matily.gemtype.core.Romanized

/**
 * "Type in English letters, convert to your language": watches the sentence being typed, shows a
 * pill per language it looks like ("→ বাংলা") and, on tap, has Gemini rewrite the text in its native
 * script and replaces it in place, with an Undo pill for a few seconds.
 *
 * Detection is offline and cheap (cached lists, no I/O); it runs [DEBOUNCE_MS] after typing or cursor
 * moves pause. Owns a scope that is cancelled when input finishes, so no request outlives its field.
 *
 * The bar also carries the "top fix" chip from "check as you type" ([FixSource]) when no conversion
 * pill is showing; tapping it applies that one correction, with the same Undo pill. And it offers an
 * emoji for the words just typed ([EmojiSuggester]: "haha" → 😂); tapping it adds the emoji after the word.
 *
 * With the pill switched off, detection still runs: the suggestions follow the language the text looks like.
 */
class ConvertController(
    private val bar: ConvertPillBar,
    private val host: Host,
    private val fixes: FixSource? = null,
) {

    /** What the pills need from the keyboard service, on top of what the panel needs. */
    interface Host : GemTypePanelController.Host {
        /** Up to [n] chars before the cursor from the keyboard's own cache (includes the word being typed). */
        fun textBeforeCursor(n: Int): CharSequence

        /** Where the cursor is, as the keyboard believes. */
        fun cursor(): Int

        /** The current keyboard types Latin letters and does not transliterate by itself (English qwerty). */
        fun isLatinKeyboard(): Boolean

        /** Reset the suggestion strip after the text was replaced. */
        fun clearSuggestions()

        /** Select [start]..[end] in the field (a cursor when equal). */
        fun select(start: Int, end: Int)

        /** What the phonetic dictionaries say about romanized words, or null when none is ready. */
        fun recognizer(): Romanized.Recognizer? = null

        /** [language] is what the text looks like now (null: nothing): the suggestions follow it. */
        fun onRomanizedChanged(language: String?) {}

        /** The field takes ordinary text, where an emoji beside the words makes sense (not a URL, email or number). */
        fun emojiAllowed(): Boolean = false

        /** Put [emoji] after the word just typed, keeping the word as typed. */
        fun insertEmoji(emoji: String) {}
    }

    /** Where the chip's correction comes from. */
    interface FixSource {
        /** The chip's text ("dont → don't"), or null when no chip should show now. */
        fun chipLabel(): String?

        /** The user is in the middle of a word, so a chip would only be in the way. */
        fun midWord(): Boolean

        /** The first correction as it applies to [text] (what the field holds now), or null if it no longer does. */
        fun topFix(text: String): Correction?
    }

    /** [field] was replaced by [result], leaving the cursor at [cursor]. */
    private class Undo(val field: FieldText, val result: String, val cursor: Int)

    private val context = bar.context
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + GemType.uncaught())
    private var job: Job? = null
    private var matches = emptyList<Romanized.Match>()
    /** The last sentence that matched, carried into the next one ("ami kal ashbo. See you"). */
    private var carried: Romanized.Carried? = null
    private var busy = -1
    private var undo: Undo? = null
    private var message: String? = null
    private var chip: String? = null
    private var emoji: String? = null
    private val evaluate = Runnable { evaluate() }
    private val undoExpired = Runnable { clearUndo() }
    private val messageExpired = Runnable { clearMessage() }

    init {
        bar.onPillClick = ::onPillClick
        bar.onEmojiClick = ::onEmojiClick
    }

    // ---- lifecycle (called by the keyboard) -----------------------------------------------

    /** A field got focus: unless it is only restarting, start clean; then look at any text already in it. */
    fun onStartInput(restarting: Boolean) {
        if (!restarting) reset()
        schedule()
    }

    /** Typing or a cursor move. */
    fun onSelectionUpdate(start: Int, end: Int) {
        if (!GemType.isEnabled) return
        val undo = undo
        if (undo != null) {
            // The replacement itself reports this position; anything else is the user typing.
            if (start == undo.cursor && end == undo.cursor) return
            clearUndo()
        }
        // The user is typing again: a leftover error would only be in the way.
        if (message != null) clearMessage() else if (busy < 0) schedule()
        // Typing into a word: the chip goes right away, it comes back at the next pause.
        if (chip != null && fixes?.midWord() == true) render()
    }

    /** What the chip would say may have changed. */
    fun refreshChip() {
        if (undo == null && message == null && busy < 0) render()
    }

    fun onSubtypeChanged() = schedule()

    /** The phonetic dictionary learned about a word: look at the text again. */
    fun onDictionaryAnswer() {
        if (undo == null && message == null && busy < 0) schedule()
    }

    /** Input finished: drop the request and hide the pills. */
    fun onFinishInput() = reset()

    fun destroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // ---- detection -----------------------------------------------------------------------

    private fun schedule() {
        handler.removeCallbacks(evaluate)
        // Right after a finished word (a space or punctuation) there is nothing to wait for: the pill
        // shows with the third word, not after a pause.
        val last = host.textBeforeCursor(1).lastOrNull()
        val wordEnded = last != null && !last.isLetterOrDigit() && last != '\'' && last != '’'
        handler.postDelayed(evaluate, if (wordEnded) WORD_END_MS else DEBOUNCE_MS)
    }

    /** The language the text being typed looks like (the best pill), or null. */
    val romanizedLanguage: String?
        get() = if (undo == null && busy < 0) matches.firstOrNull()?.language else null

    private fun evaluate() {
        matches = detect()
        emoji = suggestEmoji()
        render()
    }

    private fun suggestEmoji(): String? {
        if (undo != null || message != null || busy >= 0 || host.isPasswordField() || !host.emojiAllowed() ||
            !ConvertPrefs.suggestEmoji(context)
        ) return null
        return EmojiSuggester.suggest(host.textBeforeCursor(EmojiSuggester.TAIL_CHARS))
    }

    private fun detect(): List<Romanized.Match> {
        if (!GemType.isEnabled || undo != null || message != null || busy >= 0 || host.isPasswordField() ||
            !host.isLatinKeyboard()
        ) return emptyList()
        val languages = ConvertPrefs.convertTo(context)
        if (languages.isEmpty()) return emptyList()
        val lists = RomanizedLists.peek()
        if (lists == null) {
            // First use: read the lists off the main thread, then look again.
            scope.launch {
                // Missing lists just mean no pills; never let that crash the keyboard.
                runCatching { RomanizedLists.load(context) }.onSuccess { schedule() }
            }
            return emptyList()
        }
        val before = host.textBeforeCursor(Romanized.TAIL_CHARS)
        if (before.none { it.isLetter() }) {
            carried = null // an empty field (sent, cleared): start over
            return emptyList()
        }
        val sentence = Romanized.currentSentence(before)
        val found = Romanized.detectRecent(before, carried, languages, lists::get, host.englishCheck(), host.recognizer())
        val previous = carried
        if (found.isEmpty()) {
            carried = null
        } else if (previous == null || previous.matches != found || sentence.startsWith(previous.sentence)) {
            carried = Romanized.Carried(sentence, found)
        }
        return Romanized.order(found, ConvertPrefs.lastLanguage(context))
    }

    private fun render() {
        message?.let { return bar.showMessage(it) }
        // A conversion pill is the better offer; the chip only fills an otherwise empty bar.
        val offered = if (ConvertPrefs.showPill(context)) matches else emptyList()
        chip = if (undo == null && busy < 0 && offered.isEmpty() && GemType.isEnabled) fixes?.chipLabel() else null
        val labels = when {
            undo != null -> listOf(context.getString(R.string.gemtype_pill_undo))
            else -> offered.map { context.getString(R.string.gemtype_pill_label, Romanized.nativeName(it.language)) } +
                listOfNotNull(chip)
        }
        bar.show(labels, busy, if (chip != null) labels.lastIndex else -1, cover = undo != null, emoji = emoji)
        host.onRomanizedChanged(romanizedLanguage)
    }

    private fun reset() {
        job?.cancel()
        job = null
        handler.removeCallbacksAndMessages(null)
        busy = -1
        undo = null
        message = null
        chip = null
        emoji = null
        matches = emptyList()
        carried = null
        render()
    }

    // ---- converting ----------------------------------------------------------------------

    private fun onPillClick(index: Int) {
        val undo = undo
        when {
            busy >= 0 -> {}
            message != null -> clearMessage()
            undo != null -> undoConversion(undo)
            else -> {
                val match = matches.getOrNull(index)?.takeIf { ConvertPrefs.showPill(context) }
                if (match != null) convert(index, match.language) else if (chip != null) applyTopFix()
            }
        }
    }

    private fun onEmojiClick(emoji: String) {
        this.emoji = null
        render()
        host.insertEmoji(emoji)
    }

    /** The chip was tapped: apply that one correction in place. */
    private fun applyTopFix() {
        val ic = host.inputConnection() ?: return
        val field = FieldText.read(ic)
        val fix = if (field.status == FieldText.Status.READY && field.scope == FieldText.Scope.FIELD) {
            fixes?.topFix(field.text)
        } else null
        val result = fix?.let { Corrections.apply(field.text, listOf(it)) }
        if (result == null || result == field.text || !field.replace(host, result)) {
            return showMessage(context.getString(R.string.gemtype_pill_text_changed))
        }
        host.clearSuggestions()
        offerUndo(field, result)
    }

    private fun convert(index: Int, language: String) {
        val ic = host.inputConnection() ?: return
        val field = FieldText.read(ic)
        when (field.status) {
            FieldText.Status.EMPTY -> return showMessage(context.getString(R.string.gemtype_pill_empty))
            FieldText.Status.TOO_LONG -> return showMessage(context.getString(R.string.gemtype_pill_too_long))
            FieldText.Status.READY -> {}
        }
        ConvertPrefs.setLastLanguage(context, language)
        busy = index
        render()
        job = scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { GemTypeSettings.load(context) }
                if (config.apiKey.isBlank()) {
                    host.openSettings()
                    return@launch
                }
                val result = GemType.client.refine(field.text, Action.NATIVE, config, Romanized.englishName(language)).text
                if (field.replace(host, result)) {
                    host.clearSuggestions()
                    carried = null // the text is in its own script now
                    busy = -1
                    offerUndo(field, result)
                } else {
                    showMessage(context.getString(R.string.gemtype_pill_text_changed))
                }
            } catch (e: GeminiException) {
                showMessage(e.brief)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(context.getString(R.string.gemtype_error_generic))
            } finally {
                busy = -1
                if (isActive) {
                    if (undo != null) render() else evaluate()
                }
            }
        }
    }

    /**
     * [field] was just replaced by [result] (by a pill, or by Replace in the panel): show Undo
     * for [UNDO_MS], or until the user types or moves the cursor.
     */
    fun offerUndo(field: FieldText, result: CharSequence) {
        handler.removeCallbacks(evaluate)
        handler.removeCallbacks(messageExpired)
        message = null
        undo = Undo(field, result.toString(), host.cursor())
        handler.removeCallbacks(undoExpired)
        handler.postDelayed(undoExpired, UNDO_MS)
        render()
    }

    private fun undoConversion(undo: Undo) {
        clearUndo()
        val ic = host.inputConnection() ?: return
        // Only if the converted text is still right before the cursor.
        if (ic.getTextBeforeCursor(undo.result.length, 0)?.toString() != undo.result) {
            return showMessage(context.getString(R.string.gemtype_pill_text_changed))
        }
        val original = undo.field
        val start = host.cursor() - undo.result.length
        host.replaceField(original.text, undo.result.length, 0)
        host.clearSuggestions()
        // The replacement leaves the cursor at the end; put the cursor (or selection) back where it was.
        if (start >= 0 && (original.selectionStart != original.text.length || original.selectionEnd != original.text.length)) {
            host.select(start + original.selectionStart, start + original.selectionEnd)
        }
    }

    private fun clearUndo() {
        handler.removeCallbacks(undoExpired)
        if (undo == null) return
        undo = null
        render()
        schedule()
    }

    /** Show [text] in the strip for a few seconds (not a toast: those are suppressed when the app's notifications are off). */
    private fun showMessage(text: String) {
        message = text
        handler.removeCallbacks(messageExpired)
        handler.postDelayed(messageExpired, MESSAGE_MS)
        render()
    }

    private fun clearMessage() {
        handler.removeCallbacks(messageExpired)
        if (message == null) return
        message = null
        evaluate()
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val WORD_END_MS = 50L
        const val UNDO_MS = 10_000L
        const val MESSAGE_MS = 4_000L
    }
}
