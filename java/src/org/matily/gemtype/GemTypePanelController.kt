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
import org.matily.gemtype.core.GeminiException

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
    }

    enum class Scope { FIELD, SELECTION }

    private enum class Status { READY, EMPTY, NO_KEY, TOO_LONG, STALE }

    private val context: Context = panel.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private var status = Status.EMPTY
    private var textScope = Scope.FIELD
    private var source = ""
    private var before = 0
    private var after = 0
    private var mode = Action.FIX
    private val results = HashMap<Action, String>()
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
    private val resultLabel: TextView = panel.findViewById(R.id.gemtype_panel_result_label)
    private val textView: TextView = panel.findViewById(R.id.gemtype_panel_text)
    private val progress: ProgressBar = panel.findViewById(R.id.gemtype_panel_progress)
    private val linkButton: TextView = panel.findViewById(R.id.gemtype_panel_link_button)
    private val chipsScroll: HorizontalScrollView = panel.findViewById(R.id.gemtype_panel_chips_scroll)
    private val chips: LinearLayout = panel.findViewById(R.id.gemtype_panel_chips)
    private val buttons: View = panel.findViewById(R.id.gemtype_panel_buttons)
    private val copyButton: TextView = panel.findViewById(R.id.gemtype_panel_copy)
    private val replaceButton: TextView = panel.findViewById(R.id.gemtype_panel_replace)
    private val shareButton: TextView = panel.findViewById(R.id.gemtype_panel_share)
    private val keyboardKey: ImageButton = panel.findViewById(R.id.gemtype_panel_keyboard_key)
    private val chipViews = HashMap<Action, TextView>()

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
        shareButton.setOnClickListener { results[mode]?.let { host.share(it) } }
        keyboardKey.setOnClickListener { host.closePanel() }
        linkButton.setOnClickListener { onLinkButton() }
        copyButton.setOnClickListener { copy() }
        replaceButton.setOnClickListener { replace() }
        for (action in Action.entries) {
            val chip = TextView(context).apply {
                text = action.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                minHeight = dp(34)
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { pick(action) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) })
            chipViews[action] = chip
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
        textScope = Scope.SELECTION
        source = text
        status = when {
            text.isBlank() -> Status.EMPTY
            text.length > MAX_CHARS -> Status.TOO_LONG
            else -> Status.READY
        }
        startFix()
    }

    /** True when the last [showText] stopped because no API key is saved. */
    val needsKey: Boolean get() = status == Status.NO_KEY

    private fun reset() {
        results.clear()
        error = null
        loading = false
        mode = Action.FIX
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
            val hasKey = withContext(Dispatchers.IO) { GemTypeSettings.load(context).apiKey.isNotBlank() }
            if (hasKey) {
                fetch(Action.FIX)
            } else {
                loading = false
                status = Status.NO_KEY
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
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            textScope = Scope.SELECTION
            source = selected
            status = when {
                selected.isBlank() -> Status.EMPTY
                selected.length > MAX_CHARS -> Status.TOO_LONG
                else -> Status.READY
            }
            return
        }
        textScope = Scope.FIELD
        val b = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
        val a = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
        before = b.length
        after = a.length
        source = b + a
        status = when {
            source.isBlank() -> Status.EMPTY
            // Replacing only part of a longer field would leave the rest behind.
            b.length >= MAX_CHARS || a.length >= MAX_CHARS -> Status.TOO_LONG
            else -> Status.READY
        }
    }

    private fun replace() {
        val result = results[mode] ?: return
        if (external) {
            host.replaceSelection(result)
            return
        }
        val ic = host.inputConnection() ?: return
        // Make sure we are about to replace exactly what we sent to Gemini.
        val stale = if (textScope == Scope.SELECTION) {
            ic.getSelectedText(0)?.toString() != source
        } else {
            val b = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString().orEmpty()
            val a = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
            before = b.length
            after = a.length
            b + a != source
        }
        if (stale) {
            status = Status.STALE
            render()
            return
        }
        if (textScope == Scope.SELECTION) host.replaceSelection(result)
        else host.replaceField(result, before, after)
        host.closePanel()
    }

    private fun copy() {
        val result = results[mode] ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("GemType", result))
        copyButton.setText(R.string.gemtype_copied)
        copyButton.postDelayed({ copyButton.setText(R.string.gemtype_copy) }, 1200)
    }

    // ---- requests ------------------------------------------------------------------------

    private fun pick(action: Action) {
        if (status != Status.READY) return
        mode = action
        if (results.containsKey(action)) {
            job?.cancel()
            loading = false
            error = null
            render()
        } else {
            run(action)
        }
    }

    private fun run(action: Action) {
        job?.cancel()
        error = null
        loading = true
        mode = action
        render()
        job = scope.launch { fetch(action) }
    }

    private suspend fun fetch(action: Action) {
        mode = action
        val text = source
        try {
            val config = withContext(Dispatchers.IO) { GemTypeSettings.load(context) }
            results[action] = GemType.client.refine(text, action, config)
        } catch (e: GeminiException) {
            if (mode == action) error = e.friendly
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mode == action) error = "Something went wrong. Try again."
        } finally {
            if (mode == action) loading = false
        }
        if (mode == action) render()
    }

    private fun onLinkButton() {
        when {
            status == Status.NO_KEY -> host.openSettings()
            error != null -> run(mode)
        }
    }

    // ---- rendering -----------------------------------------------------------------------

    private fun render() {
        val ready = status == Status.READY
        scopeLabel.text = when {
            !ready || external -> ""
            textScope == Scope.SELECTION -> context.getString(R.string.gemtype_scope_selection)
            else -> context.getString(R.string.gemtype_scope_field)
        }
        chipsScroll.visibility = if (ready) View.VISIBLE else View.GONE
        buttons.visibility = if (ready) View.VISIBLE else View.GONE
        replaceButton.visibility = if (external && !canReplace) View.GONE else View.VISIBLE
        shareButton.visibility = if (external && !canReplace) View.VISIBLE else View.GONE
        keyboardKey.visibility = if (external) View.GONE else View.VISIBLE
        progress.visibility = if (ready && loading) View.VISIBLE else View.GONE
        linkButton.visibility = View.GONE
        textView.setTextColor(onSurface)
        resultLabel.text = if (ready) mode.resultLabel else ""
        resultLabel.visibility = if (ready) View.VISIBLE else View.GONE

        if (!ready) {
            textView.textSize = 16f
            when (status) {
                Status.NO_KEY -> {
                    textView.setText(R.string.gemtype_no_key)
                    linkButton.setText(R.string.gemtype_add_key)
                    linkButton.visibility = View.VISIBLE
                }
                Status.TOO_LONG -> textView.text = context.getString(
                    if (external) R.string.gemtype_too_long_shared else R.string.gemtype_too_long, MAX_CHARS,
                )
                Status.STALE -> textView.setText(R.string.gemtype_text_changed)
                else -> textView.setText(R.string.gemtype_empty_field)
            }
            scroll.scrollTo(0, 0)
            return
        }

        val result = results[mode]
        when {
            result != null -> textView.text =
                if (mode == Action.FIX) DiffSpans.build(source, result, diffColors) else result
            error != null -> {
                textView.text = error
                textView.setTextColor(errorColor)
                linkButton.setText(R.string.gemtype_try_again)
                linkButton.visibility = View.VISIBLE
            }
            else -> {
                textView.setText(R.string.gemtype_loading)
                textView.setTextColor(onSurfaceVariant)
            }
        }
        val canApply = result != null
        for ((button, enabled) in listOf(copyButton to canApply, replaceButton to canApply, shareButton to canApply)) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.4f
        }
        for ((action, chip) in chipViews) {
            val selected = action == mode
            chip.setTextColor(if (selected) onPrimary else onSurface)
            chip.background = ripple(roundRect(if (selected) primary else functionalColor, 18f))
        }
        scroll.scrollTo(0, 0)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun roundRect(fill: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun ripple(content: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x22)), content, null)

    private fun themeColor(attr: Int, fallback: Int, alt: Int = 0): Int {
        val value = TypedValue()
        for (a in intArrayOf(attr, alt)) {
            if (a != 0 && context.theme.resolveAttribute(a, value, true) &&
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
            ) return value.data
        }
        return fallback
    }

    companion object {
        /** Upper bound for text read around the cursor (each side), and for a selection. */
        const val MAX_CHARS = 10_000
        private const val ACCENT = 0xFF2F6FD1.toInt()
    }
}
