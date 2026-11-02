// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matily.gemtype.core.Correction
import org.matily.gemtype.core.LiveCheck
import org.matily.gemtype.core.LiveCheckState

/**
 * "Check as you type": [LiveCheck.DEBOUNCE_MS] after typing pauses, sends the field's text to Gemini for
 * proofreading and shows the number of mistakes as a badge on the GemType mark. Tapping the mark opens Fix
 * with that answer ([cached]); the first mistake is offered as a chip in the strip ([chipLabel], drawn by
 * [ConvertController]). What to ask and when is decided by [LiveCheckState]; this class only does the
 * Android part. It is silent: errors never show anything, they only pause the checks.
 *
 * Owns a scope that is cancelled when input finishes, so no request outlives its field.
 */
class LiveCheckController(private val badge: GemTypeBadge, private val host: Host) : ConvertController.FixSource {

    /** What the check needs from the keyboard service, on top of what the pills need. */
    interface Host : ConvertController.Host {
        /** The field holds ordinary text: not a password, URL, email address or number. */
        fun isCheckableField(): Boolean

        /** The GemType panel (which runs its own check) is open. */
        fun isPanelShowing(): Boolean
    }

    private val context = badge.context
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + GemType.uncaught())
    private var job: Job? = null
    private val state = LiveCheckState()
    private val evaluate = Runnable { evaluate() }

    /** Called when what [chipLabel] answers may have changed. */
    var onChange: Runnable? = null

    // ---- lifecycle (called by the keyboard) -----------------------------------------------

    /** A field got focus: unless it is only restarting, start clean. */
    fun onStartInput(restarting: Boolean) {
        if (!restarting) reset()
        schedule()
    }

    /** Typing or a cursor move. */
    fun onSelectionUpdate() = schedule()

    /** GemType changed the text (Replace, the chip, a conversion): drop the fixed mistakes from the badge now. */
    fun onReplaced() {
        handler.postDelayed(refreshBadge, REPLACED_SETTLE_MS)
        schedule()
    }

    /** Input finished: drop the request and the badge. */
    fun onFinishInput() = reset()

    fun destroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // ---- what the panel and the strip ask --------------------------------------------------

    /** The answer for exactly [text], for the Fix panel to show without asking again. */
    // The panel asks while it opens, so the panel check in active() must not apply here.
    fun cached(text: String): List<Correction>? = if (allowed()) state.cached(text) else null

    /** The first mistake as a chip, or null when there is none or the user is in the middle of a word or not at the end of the text. */
    override fun chipLabel(): String? {
        val first = state.corrections.firstOrNull() ?: return null
        if (!active() || midWord()) return null
        // Applying it replaces the whole field, which leaves the cursor at the end: only offer it from there.
        val ic = host.inputConnection() ?: return null
        if (!ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return null
        return LiveCheck.chipLabel(first)
    }

    override fun midWord(): Boolean = LiveCheck.isMidWord(host.textBeforeCursor(MID_WORD_CHARS))

    override fun topFix(text: String): Correction? = state.refresh(text).firstOrNull()

    // ---- checking -------------------------------------------------------------------------

    private fun allowed() = GemType.isEnabled && CheckPrefs.liveCheck(context) && host.isCheckableField() &&
        !host.isPasswordField()

    private fun active() = allowed() && !host.isPanelShowing()

    private fun schedule() {
        if (!GemType.isEnabled) return
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, LiveCheck.DEBOUNCE_MS)
    }

    private fun evaluate() {
        // The panel runs its own check; the badge stays as it was until it closes.
        if (host.isPanelShowing()) return
        if (!active()) return publish(emptyList())
        val ic = host.inputConnection() ?: return
        val field = FieldText.read(ic)
        // A selection is for the user to fix on purpose (the panel), not to check by itself.
        if (field.scope == FieldText.Scope.SELECTION) return
        if (field.status != FieldText.Status.READY) {
            state.reset()
            return publish(emptyList())
        }
        val text = field.text
        publish(state.refresh(text))
        when (val decision = state.decide(text, SystemClock.elapsedRealtime())) {
            LiveCheck.Decision.Check -> check(text)
            is LiveCheck.Decision.Later -> handler.postDelayed(evaluate, decision.afterMs)
            LiveCheck.Decision.Skip -> {}
        }
    }

    private fun check(text: String) {
        val id = state.start(text)
        job = scope.launch {
            try {
                // Opening the encrypted key store is Keystore I/O: off the main thread.
                val config = withContext(Dispatchers.IO) { GemTypeSettings.load(context) }
                if (config.apiKey.isBlank()) {
                    state.drop(id)
                    return@launch
                }
                val found = GemType.client.check(text, config, host.keyboardLanguage())
                if (state.succeed(id, text, found)) publish(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any error (a 429 included): say nothing, stop asking for a while.
                state.fail(id, SystemClock.elapsedRealtime())
            } finally {
                // The user may have typed while the request was out.
                if (isActive) schedule()
            }
        }
    }

    private val refreshBadge = Runnable {
        val ic = host.inputConnection() ?: return@Runnable
        if (!active()) return@Runnable
        val field = FieldText.read(ic)
        if (field.status == FieldText.Status.READY && field.scope == FieldText.Scope.FIELD) publish(state.refresh(field.text))
    }

    private fun publish(corrections: List<Correction>) {
        badge.count = corrections.size
        onChange?.run()
    }

    private fun reset() {
        job?.cancel()
        job = null
        handler.removeCallbacks(evaluate)
        handler.removeCallbacks(refreshBadge)
        state.reset()
        publish(emptyList())
    }

    private companion object {
        /** Enough text before the cursor to see whether it ends inside a word. */
        const val MID_WORD_CHARS = 8

        /** The editor reports the replaced text a moment after the keyboard sends it. */
        const val REPLACED_SETTLE_MS = 150L
    }
}
