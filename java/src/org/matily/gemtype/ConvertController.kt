// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import org.matily.gemtype.core.GeminiException
import org.matily.gemtype.core.Romanized

/**
 * "Type in English letters, convert to your language": watches the sentence being typed, shows a
 * pill per language it looks like ("→ বাংলা") and, on tap, has Gemini rewrite the text in its native
 * script and replaces it in place, with an Undo pill for a few seconds.
 *
 * Detection is offline and cheap (cached lists, no I/O); it runs [DEBOUNCE_MS] after typing or cursor
 * moves pause. Owns a scope that is cancelled when input finishes, so no request outlives its field.
 */
class ConvertController(private val bar: ConvertPillBar, private val host: Host) {

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
    }

    private class Undo(val original: String, val result: String, val cursor: Int)

    private val context = bar.context
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var matches = emptyList<Romanized.Match>()
    private var busy = -1
    private var undo: Undo? = null
    private val evaluate = Runnable { evaluate() }
    private val undoExpired = Runnable { clearUndo() }

    init {
        bar.onPillClick = ::onPillClick
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
        if (busy < 0) schedule()
    }

    fun onSubtypeChanged() = schedule()

    /** Input finished: drop the request and hide the pills. */
    fun onFinishInput() = reset()

    fun destroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // ---- detection -----------------------------------------------------------------------

    private fun schedule() {
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, DEBOUNCE_MS)
    }

    private fun evaluate() {
        matches = detect()
        render()
    }

    private fun detect(): List<Romanized.Match> {
        if (!GemType.isEnabled || undo != null || busy >= 0 || host.isPasswordField() ||
            !host.isLatinKeyboard() || !ConvertPrefs.showPill(context)
        ) return emptyList()
        val languages = RomanizedLists.enabledNonLatinLanguages(context)
        if (languages.isEmpty()) return emptyList()
        val lists = RomanizedLists.peek()
        if (lists == null) {
            // First use: read the lists off the main thread, then look again.
            scope.launch {
                RomanizedLists.load(context)
                schedule()
            }
            return emptyList()
        }
        val sentence = Romanized.currentSentence(host.textBeforeCursor(Romanized.TAIL_CHARS))
        val found = Romanized.detect(sentence, languages, lists::get, host.englishCheck())
        return Romanized.order(found, ConvertPrefs.lastLanguage(context))
    }

    private fun render() {
        val labels = when {
            undo != null -> listOf(context.getString(R.string.gemtype_pill_undo))
            else -> matches.map { context.getString(R.string.gemtype_pill_label, Romanized.nativeName(it.language)) }
        }
        bar.show(labels, busy)
    }

    private fun reset() {
        job?.cancel()
        job = null
        handler.removeCallbacksAndMessages(null)
        busy = -1
        undo = null
        matches = emptyList()
        render()
    }

    // ---- converting ----------------------------------------------------------------------

    private fun onPillClick(index: Int) {
        val undo = undo
        when {
            busy >= 0 -> {}
            undo != null -> undoConversion(undo)
            else -> matches.getOrNull(index)?.let { convert(index, it.language) }
        }
    }

    private fun convert(index: Int, language: String) {
        val ic = host.inputConnection() ?: return
        val field = FieldText.read(ic)
        when (field.status) {
            FieldText.Status.EMPTY -> return toast(context.getString(R.string.gemtype_empty_field))
            FieldText.Status.TOO_LONG -> return toast(context.getString(R.string.gemtype_too_long, FieldText.MAX_CHARS))
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
                val result = GemType.client.refine(field.text, Action.NATIVE, config, Romanized.englishName(language))
                if (field.replace(host, result)) {
                    host.clearSuggestions()
                    undo = Undo(field.text, result, host.cursor())
                    handler.postDelayed(undoExpired, UNDO_MS)
                } else {
                    toast(context.getString(R.string.gemtype_pill_text_changed))
                }
            } catch (e: GeminiException) {
                toast(e.friendly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(context.getString(R.string.gemtype_error_generic))
            } finally {
                busy = -1
                if (isActive) {
                    if (undo != null) render() else evaluate()
                }
            }
        }
    }

    private fun undoConversion(undo: Undo) {
        clearUndo()
        val ic = host.inputConnection() ?: return
        // Only if the converted text is still right before the cursor.
        if (ic.getTextBeforeCursor(undo.result.length, 0)?.toString() != undo.result) {
            return toast(context.getString(R.string.gemtype_pill_text_changed))
        }
        host.replaceField(undo.original, undo.result.length, 0)
        host.clearSuggestions()
    }

    private fun clearUndo() {
        handler.removeCallbacks(undoExpired)
        if (undo == null) return
        undo = null
        render()
        schedule()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val UNDO_MS = 6_000L
    }
}
