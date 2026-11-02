// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.inputmethod.latin.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matily.gemtype.core.Action
import org.matily.gemtype.core.Correction
import org.matily.gemtype.core.Corrections
import org.matily.gemtype.core.FixMode
import org.matily.gemtype.core.GeminiException
import org.matily.gemtype.core.Rewrite
import org.matily.gemtype.core.Romanized
import org.matily.gemtype.core.Versions

/**
 * Drives the GemType panel: reads the field (or selection) through the
 * InputConnection, asks Gemini, shows the result and applies Replace.
 *
 * Owns a scope (SupervisorJob + Main) that is cancelled when the panel closes, input
 * finishes or the service is destroyed, so a slow request never outlives its panel.
 */
class GemTypePanelController(private val panel: GemTypePanelView, private val host: Host) {

    /** What the panel needs from the keyboard service. */
    interface Host {
        fun inputConnection(): InputConnection?
        fun isPasswordField(): Boolean

        /** Replace the current selection with [text]. */
        fun replaceSelection(text: CharSequence)

        /** Replace the whole field: delete [before]/[after] chars around the cursor, then insert [text]. */
        fun replaceField(text: CharSequence, before: Int, after: Int)

        /** Return to the main keyboard (resetting its composing/suggestion state). */
        fun closePanel()
        fun openSettings()

        /** Share [text] (only used by [showText], when the result cannot be put back). */
        fun share(text: CharSequence) {}

        /** Whether a lower-case word is valid English, or null when there is no English dictionary to ask. */
        fun englishCheck(): ((String) -> Boolean)? = null

        /** The language being typed on the keyboard (its English name), a hint for Fix when the writing language is auto. */
        fun keyboardLanguage(): String? = null

        /** The mistakes "check as you type" already found in exactly [text] (English only), if it has; Fix then needs no request. */
        fun cachedCheck(text: String): List<Correction>? = null

        /** Replace [field] with [result] went through and the panel is closed: offer Undo. */
        fun onReplaced(field: FieldText, result: CharSequence) {}
    }

    /** What a chip does: an [Action], plus the language (code) to write in for [Action.NATIVE]. */
    private data class Pick(val action: Action, val language: String? = null) {
        val label: String get() = if (language != null) Romanized.nativeName(language) else action.label
    }

    private enum class Status { READY, EMPTY, NO_KEY, TOO_LONG, STALE }

    private val context: Context = panel.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + GemType.uncaught())
    private var job: Job? = null

    private var status = Status.EMPTY
    private var field: FieldText? = null
    private var textScope = FieldText.Scope.FIELD
    private var source = ""
    private var mode = Pick(Action.FIX)
    // The last few results of each chip (see Versions), and the language (code) of the text when it is romanized.
    private val results = HashMap<Pick, Versions<Rewrite>>()
    private var romanizedLanguage: String? = null

    // English Fix is a list of corrections (what Gemini found, and which of them the user skipped); any other
    // text gets the rewrite, like the other chips. Decided in startFix once the text has been looked at.
    private var fixMode = FixMode.REWRITE
    private var fixes: List<Correction>? = null
    private val skipped = HashSet<Int>()
    private var error: String? = null
    private var loading = false

    // Set by [showText]: the text comes from outside (selection menu / share sheet), not the field.
    private var external = false
    private var canReplace = true

    // ---- views and theme colours --------------------------------------------------------
    private val scopeLabel: TextView = panel.findViewById(R.id.gemtype_panel_scope)
    private val titleView: TextView = panel.findViewById(R.id.gemtype_panel_title)
    private val resultBox: View = panel.findViewById(R.id.gemtype_panel_result_box)
    private val scroll: ScrollView = panel.findViewById(R.id.gemtype_panel_scroll)
    private val labelRow: View = panel.findViewById(R.id.gemtype_panel_label_row)
    private val resultLabel: TextView = panel.findViewById(R.id.gemtype_panel_result_label)
    private val versionsView: View = panel.findViewById(R.id.gemtype_panel_versions)
    private val versionPrev: TextView = panel.findViewById(R.id.gemtype_panel_version_prev)
    private val versionCount: TextView = panel.findViewById(R.id.gemtype_panel_version_count)
    private val versionNext: TextView = panel.findViewById(R.id.gemtype_panel_version_next)
    private val retryButton: TextView = panel.findViewById(R.id.gemtype_panel_retry)
    private val textView: TextView = panel.findViewById(R.id.gemtype_panel_text)
    private val readAsView: TextView = panel.findViewById(R.id.gemtype_panel_read_as)
    private val fixRows: LinearLayout = panel.findViewById(R.id.gemtype_panel_fixes)
    private val progress: ProgressBar = panel.findViewById(R.id.gemtype_panel_progress)
    private val linkButton: TextView = panel.findViewById(R.id.gemtype_panel_link_button)
    private val chipsScroll: HorizontalScrollView = panel.findViewById(R.id.gemtype_panel_chips_scroll)
    private val chips: LinearLayout = panel.findViewById(R.id.gemtype_panel_chips)
    private val buttons: View = panel.findViewById(R.id.gemtype_panel_buttons)
    private val copyButton: TextView = panel.findViewById(R.id.gemtype_panel_copy)
    private val replaceButton: TextView = panel.findViewById(R.id.gemtype_panel_replace)
    private val shareButton: TextView = panel.findViewById(R.id.gemtype_panel_share)
    private val keyboardKey: ImageButton = panel.findViewById(R.id.gemtype_panel_keyboard_key)
    private val chipViews = LinkedHashMap<Pick, TextView>()

    // Keyboard theme attrs first; an activity theme has none of them, so fall back to Material's.
    private val surface = themeColor(R.attr.md3Surface, Color.WHITE, R.attr.colorSurface)
    private val onSurface = themeColor(R.attr.md3OnSurface, Color.BLACK, R.attr.colorOnSurface)
    private val onSurfaceVariant = themeColor(R.attr.md3OnSurfaceVariant, Color.DKGRAY, R.attr.colorOnSurfaceVariant)
    private val primary = themeColor(R.attr.md3Primary, ACCENT, R.attr.colorPrimary)
    private val onPrimary = themeColor(R.attr.md3OnPrimary, Color.WHITE, R.attr.colorOnPrimary)
    private val keyColor = themeColor(R.attr.md3KeyColor, Color.LTGRAY, R.attr.colorSurfaceContainerHigh)
    private val functionalColor = themeColor(R.attr.md3FunctionalKeyColor, Color.LTGRAY, R.attr.colorSurfaceContainerHighest)
    private val dark = ColorUtils.calculateLuminance(surface) < 0.5
    private val errorColor = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
    private val diffColors = if (dark) {
        DiffSpans.Colors(removed = 0xFFF2B8B5.toInt(), added = 0xFFA8E6C1.toInt(), addedBackground = 0x3326A869)
    } else {
        DiffSpans.Colors(removed = 0xFFC0362C.toInt(), added = 0xFF0B5D3B.toInt(), addedBackground = 0xFFDCF3E9.toInt())
    }

    init {
        titleView.setTextColor(onSurface)
        scopeLabel.setTextColor(onSurfaceVariant)
        resultLabel.setTextColor(onSurfaceVariant)
        textView.setTextColor(onSurface)
        readAsView.setTextColor(onSurfaceVariant)
        for (view in listOf(versionPrev, versionNext)) {
            view.setTextColor(onSurfaceVariant)
            view.background = ripple(roundRect(Color.TRANSPARENT, 15f))
        }
        versionCount.setTextColor(onSurfaceVariant)
        retryButton.setTextColor(primary)
        retryButton.background = ripple(roundRect(Color.TRANSPARENT, 15f))
        versionPrev.setOnClickListener { stepVersion(-1) }
        versionNext.setOnClickListener { stepVersion(1) }
        retryButton.setOnClickListener { retry() }
        progress.indeterminateTintList = ColorStateList.valueOf(primary)
        progress.progressBackgroundTintList = ColorStateList.valueOf(functionalColor)
        resultBox.background = roundRect(keyColor, 14f, strokeColor = ColorUtils.setAlphaComponent(primary, 0x30))
        linkButton.setTextColor(onPrimary)
        linkButton.background = ripple(roundRect(primary, 20f))
        copyButton.setTextColor(primary)
        copyButton.background = ripple(roundRect(Color.TRANSPARENT, 12f, strokeColor = ColorUtils.setAlphaComponent(primary, 0x80)))
        replaceButton.setTextColor(onPrimary)
        replaceButton.background = ripple(roundRect(primary, 12f))
        shareButton.setTextColor(onPrimary)
        shareButton.background = ripple(roundRect(primary, 12f))
        shareButton.setOnClickListener { currentResult()?.let { host.share(it) } }
        keyboardKey.setOnClickListener { host.closePanel() }
        linkButton.setOnClickListener { onLinkButton() }
        copyButton.setOnClickListener { copy() }
        replaceButton.setOnClickListener { replace() }
        setChips(emptyList())
    }

    /** Chips, in order: a native-script chip per detected language, the five rewrites, then English. */
    private fun setChips(native: List<Romanized.Match>) {
        val picks = native.map { Pick(Action.NATIVE, it.language) } + Action.REWRITES.map { Pick(it) } + Pick(Action.ENGLISH)
        chips.removeAllViews()
        chipViews.clear()
        for (item in picks) {
            val chip = TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                minHeight = dp(34)
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { pick(item) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) })
            chipViews[item] = chip
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------

    /** The panel was just shown: read the text and run Fix. */
    fun onShown() {
        job?.cancel()
        external = false
        reset()
        if (host.isPasswordField()) {
            host.closePanel()
            return
        }
        val ic = host.inputConnection()
        if (ic == null) {
            status = Status.EMPTY
            render()
            return
        }
        readText(ic)
        startFix()
    }

    /**
     * Use [text] from outside the field (selection menu, share sheet) instead of reading the
     * InputConnection, and run Fix. [canReplace] false shows Share instead of Replace.
     */
    fun showText(text: String, canReplace: Boolean) {
        job?.cancel()
        external = true
        this.canReplace = canReplace
        reset()
        textScope = FieldText.Scope.SELECTION
        source = text
        status = when {
            text.isBlank() -> Status.EMPTY
            text.length > FieldText.MAX_CHARS -> Status.TOO_LONG
            else -> Status.READY
        }
        startFix()
    }

    /** True when the last [showText] stopped because no API key is saved. */
    val needsKey: Boolean get() = status == Status.NO_KEY

    private fun reset() {
        results.clear()
        romanizedLanguage = null
        fixes = null
        fixMode = FixMode.REWRITE
        skipped.clear()
        error = null
        loading = false
        mode = Pick(Action.FIX)
        setChips(emptyList())
    }

    private fun startFix() {
        if (status != Status.READY) {
            render()
            return
        }
        // Checking for a key opens the encrypted store (Keystore I/O), so do it off the main thread.
        loading = true
        render()
        job = scope.launch {
            try {
                val hasKey = withContext(Dispatchers.IO) { GemTypeSettings.load(context).apiKey.isNotBlank() }
                RomanizedLists.load(context)
                if (hasKey) {
                    // Text typed in English letters that looks like one of the user's languages: convert it first, not Fix.
                    val native = detectLanguages()
                    setChips(native)
                    romanizedLanguage = native.firstOrNull()?.language
                    // English is proofread; Bangla, Hindi, mixed and romanized text is rewritten.
                    fixMode = FixMode.of(source, native.isNotEmpty())
                    val first = native.firstOrNull()?.let { Pick(Action.NATIVE, it.language) } ?: Pick(Action.FIX)
                    // "Check as you type" may have checked exactly this text already: no second request.
                    val cached = if (proofreading(first) && !external) host.cachedCheck(source) else null
                    if (cached != null) {
                        mode = first
                        fixes = cached
                        loading = false
                        render()
                    } else {
                        fetch(first)
                    }
                } else {
                    loading = false
                    status = Status.NO_KEY
                    render()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the key store or the word lists failed to open: show it, never leave the spinner up.
                loading = false
                error = context.getString(R.string.gemtype_error_generic)
                render()
            }
        }
    }

    /** The panel was hidden (back to keyboard, input finished, ...): drop any request in flight. */
    fun onHidden() {
        job?.cancel()
        job = null
        loading = false
    }

    /** The keyboard service is going away. */
    fun destroy() {
        scope.cancel()
    }

    // ---- reading and writing the field ---------------------------------------------------

    /** Reads straight from the InputConnection, bypassing the keyboard's 1024-char text cache. */
    private fun readText(ic: InputConnection) {
        val read = FieldText.read(ic)
        field = read
        textScope = read.scope
        source = read.text
        status = when (read.status) {
            FieldText.Status.READY -> Status.READY
            FieldText.Status.EMPTY -> Status.EMPTY
            FieldText.Status.TOO_LONG -> Status.TOO_LONG
        }
    }

    /** The user's languages that [source] seems to be written in with English letters, best first. */
    private fun detectLanguages(): List<Romanized.Match> {
        val lists = RomanizedLists.peek() ?: return emptyList()
        val languages = ConvertPrefs.convertTo(context)
        val found = Romanized.detect(source.take(DETECT_CHARS), languages, lists::get, host.englishCheck())
        return Romanized.order(found, ConvertPrefs.lastLanguage(context))
    }

    /** [pick] is Fix on English text: a list of corrections, not a rewrite. */
    private fun proofreading(pick: Pick) = pick.action == Action.FIX && fixMode == FixMode.PROOFREAD

    /** What Replace, Copy and Share use: the rewrite, or for English Fix the text with the corrections not skipped applied. */
    private fun currentResult(): String? {
        val found = fixes
        return if (proofreading(mode) && found != null) {
            Corrections.apply(source, found.filterIndexed { i, _ -> i !in skipped })
        } else results[mode]?.current?.text
    }

    private fun hasResult(pick: Pick) = if (proofreading(pick)) fixes != null else results.containsKey(pick)

    /** The ‹ / › next to the label: show an earlier or later version of the current result. */
    private fun stepVersion(delta: Int) {
        val versions = results[mode] ?: return
        versions.step(delta)
        error = null
        render()
    }

    /** The ✕ / ↺ on a Fix row: skip that correction, or put it back. */
    private fun toggleFix(index: Int) {
        if (!skipped.remove(index)) skipped.add(index)
        render(keepScroll = true)
    }

    private fun replace() {
        val result = currentResult() ?: return
        if (external) {
            host.replaceSelection(result)
            return
        }
        // Make sure we are about to replace exactly what we sent to Gemini.
        val sent = field
        if (sent == null || !sent.replace(host, result)) {
            status = Status.STALE
            render()
            return
        }
        host.closePanel()
        host.onReplaced(sent, result)
    }

    private fun copy() {
        val result = currentResult() ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("GemType", result))
        copyButton.setText(R.string.gemtype_copied)
        copyButton.postDelayed({ copyButton.setText(R.string.gemtype_copy) }, 1200)
    }

    // ---- requests ------------------------------------------------------------------------

    private fun pick(pick: Pick) {
        // Tapping the chip that is already running would only restart the same request.
        if (status != Status.READY || (loading && pick == mode)) return
        // Tapping the chip that is already shown asks for another version (the proofreader's list has none).
        if (pick == mode && hasResult(pick)) return retry()
        mode = pick
        if (hasResult(pick)) {
            job?.cancel()
            loading = false
            error = null
            render()
        } else {
            run(pick)
        }
    }

    private fun run(pick: Pick, avoid: List<String> = emptyList()) {
        job?.cancel()
        error = null
        loading = true
        mode = pick
        render()
        job = scope.launch { fetch(pick, avoid) }
    }

    /** Ask for another version of what the current chip shows, different from the versions kept so far. */
    private fun retry() {
        val versions = results[mode]
        if (status != Status.READY || loading || proofreading(mode) || versions == null) return
        run(mode, versions.all.map { it.text })
    }

    private suspend fun fetch(pick: Pick, avoid: List<String> = emptyList()) {
        mode = pick
        val text = source
        try {
            val config = withContext(Dispatchers.IO) { GemTypeSettings.load(context) }
            // Native script writes the chip's language; the other chips read the text as romanized in its language.
            val target = (pick.language ?: romanizedLanguage)?.let { Romanized.englishName(it) }
            if (proofreading(pick)) {
                fixes = GemType.client.check(text, config, host.keyboardLanguage())
                skipped.clear()
            } else {
                val rewrite = GemType.client.refine(text, pick.action, config, target, avoid = avoid)
                results.getOrPut(pick) { Versions() }.add(rewrite)
            }
            // Remember which language was wanted: it is offered first next time.
            pick.language?.let { ConvertPrefs.setLastLanguage(context, it) }
        } catch (e: GeminiException) {
            if (mode == pick) error = e.friendly
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mode == pick) error = "Something went wrong. Try again."
        } finally {
            if (mode == pick) loading = false
        }
        if (mode == pick) render()
    }

    private fun onLinkButton() {
        when {
            status == Status.NO_KEY -> host.openSettings()
            // After a failed "another version" the earlier versions are still there: ask again for a new one.
            error != null -> if (hasResult(mode)) retry() else run(mode)
        }
    }

    // ---- rendering -----------------------------------------------------------------------

    private fun render(keepScroll: Boolean = false) {
        val ready = status == Status.READY
        scopeLabel.text = when {
            !ready || external -> ""
            textScope == FieldText.Scope.SELECTION -> context.getString(R.string.gemtype_scope_selection)
            else -> context.getString(R.string.gemtype_scope_field)
        }
        chipsScroll.visibility = if (ready) View.VISIBLE else View.GONE
        buttons.visibility = if (ready) View.VISIBLE else View.GONE
        replaceButton.visibility = if (external && !canReplace) View.GONE else View.VISIBLE
        shareButton.visibility = if (external && !canReplace) View.VISIBLE else View.GONE
        keyboardKey.visibility = if (external) View.GONE else View.VISIBLE
        progress.visibility = if (ready && loading) View.VISIBLE else View.GONE
        linkButton.visibility = View.GONE
        fixRows.removeAllViews()
        fixRows.visibility = View.GONE
        textView.setTextColor(onSurface)
        resultLabel.text = if (ready) mode.action.resultLabel else ""
        labelRow.visibility = if (ready) View.VISIBLE else View.GONE
        readAsView.visibility = View.GONE

        if (!ready) {
            textView.textSize = 16f
            when (status) {
                Status.NO_KEY -> {
                    textView.setText(R.string.gemtype_no_key)
                    linkButton.setText(R.string.gemtype_add_key)
                    linkButton.visibility = View.VISIBLE
                }
                Status.TOO_LONG -> textView.text = context.getString(
                    if (external) R.string.gemtype_too_long_shared else R.string.gemtype_too_long, FieldText.MAX_CHARS,
                )
                Status.STALE -> textView.setText(R.string.gemtype_text_changed)
                else -> textView.setText(R.string.gemtype_empty_field)
            }
            scroll.scrollTo(0, 0)
            return
        }

        val found = fixes.takeIf { proofreading(mode) }
        val result = currentResult()
        when {
            found != null && found.isEmpty() -> {
                resultLabel.setText(R.string.gemtype_no_mistakes)
                textView.setText(R.string.gemtype_looks_good)
            }
            found != null -> showFixes(found)
            // Before the result: "another version" can fail while the earlier versions are still kept.
            error != null -> {
                textView.text = error
                textView.setTextColor(errorColor)
                linkButton.setText(R.string.gemtype_try_again)
                linkButton.visibility = View.VISIBLE
            }
            // A rewrite Fix shows what changed; the other rewrites show the new text.
            result != null -> textView.text = if (mode.action == Action.FIX) DiffSpans.build(source, result, diffColors) else result
            else -> {
                textView.setText(R.string.gemtype_loading)
                textView.setTextColor(onSurfaceVariant)
            }
        }
        showVersions(found == null && error == null)
        val canApply = result != null && error == null
        // Nothing to put back when there are no corrections, or every one was skipped.
        val canPut = canApply && (found == null || result != source)
        for ((button, enabled) in listOf(copyButton to canApply, replaceButton to canPut, shareButton to canApply)) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.4f
        }
        for ((pick, chip) in chipViews) {
            val selected = pick == mode
            chip.setTextColor(if (selected) onPrimary else onSurface)
            chip.background = ripple(roundRect(if (selected) primary else functionalColor, 18f))
        }
        if (!keepScroll) scroll.scrollTo(0, 0)
    }

    /** The ‹ 2/3 › and ↻ next to the label, and the "Read as" line; [rewrite] is false for the proofreader's list and for errors. */
    private fun showVersions(rewrite: Boolean) {
        val versions = results[mode]
        val shown = if (rewrite) versions?.current else null
        readAsView.text = context.getString(R.string.gemtype_read_as, shown?.readAs.orEmpty())
        readAsView.visibility = if (shown?.readAs != null) View.VISIBLE else View.GONE
        // Retry also stays with an error that follows a failed retry (versions kept); never while a request runs.
        val canVary = !proofreading(mode) && versions != null
        retryButton.visibility = if (canVary && !loading) View.VISIBLE else View.GONE
        val many = canVary && versions!!.size > 1
        versionsView.visibility = if (many) View.VISIBLE else View.GONE
        if (many) {
            versionCount.text = context.getString(R.string.gemtype_version_count, versions!!.index + 1, versions.size)
            for ((button, enabled) in listOf(versionPrev to (versions.index > 0), versionNext to (versions.index < versions.size - 1))) {
                button.isEnabled = enabled
                button.alpha = if (enabled) 1f else 0.3f
            }
        }
    }

    /** Fix result: the header, the text with the corrections marked in it, and a row per correction. */
    private fun showFixes(found: List<Correction>) {
        val kept = found.size - skipped.size
        resultLabel.text = if (skipped.isEmpty()) {
            context.resources.getQuantityString(R.plurals.gemtype_fixes_count, found.size, found.size)
        } else {
            context.resources.getQuantityString(R.plurals.gemtype_fixes_partial, found.size, kept, found.size)
        }
        textView.text = DiffSpans.corrections(source, found, skipped, diffColors)
        fixRows.visibility = View.VISIBLE
        found.forEachIndexed { index, c -> fixRows.addView(fixRow(index, c, index in skipped)) }
    }

    /** `old → new`, then the type and the reason in smaller text, and the ✕ / ↺ toggle. */
    private fun fixRow(index: Int, c: Correction, skipped: Boolean): View {
        val diff = TextView(context).apply {
            text = DiffSpans.pair(c.original, c.replacement, if (skipped) null else diffColors)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(onSurface)
        }
        val why = TextView(context).apply {
            text = listOf(typeLabel(c.type), c.explanation).filter { it.isNotBlank() }.joinToString(" · ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(onSurfaceVariant)
        }
        val toggle = TextView(context).apply {
            text = if (skipped) "↺" else "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(onSurfaceVariant)
            gravity = Gravity.CENTER
            minWidth = dp(40)
            minHeight = dp(40)
            background = ripple(roundRect(Color.TRANSPARENT, 20f))
            contentDescription = context.getString(if (skipped) R.string.gemtype_fix_restore else R.string.gemtype_fix_skip)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleFix(index) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (skipped) 0.6f else 1f
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(diff)
                    addView(why)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setPadding(0, dp(2), 0, dp(2))
        }
    }

    private fun typeLabel(type: Correction.Type) = context.getString(
        when (type) {
            Correction.Type.GRAMMAR -> R.string.gemtype_type_grammar
            Correction.Type.SPELLING -> R.string.gemtype_type_spelling
            Correction.Type.PUNCTUATION -> R.string.gemtype_type_punctuation
            Correction.Type.STYLE -> R.string.gemtype_type_style
        },
    )

    // ---- helpers -------------------------------------------------------------------------

    private fun dp(value: Int) = GemTypeUi.dp(context, value)

    private fun roundRect(fill: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GemTypeUi.roundRect(context, fill, radiusDp, strokeColor)

    private fun ripple(content: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x22)), content, null)

    private fun themeColor(attr: Int, fallback: Int, alt: Int = 0): Int = GemTypeUi.themeColor(context, attr, fallback, alt)

    companion object {
        /** How much of the text is looked at to guess whether it is romanized (words are cheap to count, but not unlimited). */
        internal const val DETECT_CHARS = 2_000
        private const val ACCENT = 0xFF2F6FD1.toInt()
    }
}
