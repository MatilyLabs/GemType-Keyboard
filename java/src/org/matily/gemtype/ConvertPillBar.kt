// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.inputmethod.latin.R

/**
 * The accent pills in the suggestion strip, right after the GemType mark and menu key: "→ বাংলা",
 * or a single "Undo". A pill can show a spinner while its request runs, or a short error message
 * ([showMessage]; a toast would be hidden from a keyboard whose app has notifications off). The
 * strip makes room for [occupiedWidth] and is told when it changes ([onWidthChanged]).
 */
class ConvertPillBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    /** Called when [occupiedWidth] changed. */
    var onWidthChanged: Runnable? = null

    /** Called with the index of the tapped pill. */
    var onPillClick: ((Int) -> Unit)? = null

    /** Hidden while true (the strip's toolbox is open, or the keyboard has no GemType key). */
    var suppressed = false
        set(value) {
            field = value
            refresh()
        }

    /** Width this bar takes from the strip, 0 when hidden. */
    var occupiedWidth = 0
        private set

    private var labels: List<String> = emptyList()
    private var busy = -1

    private val fill = GemTypeUi.themeColor(context, R.attr.md3Primary, ACCENT, R.attr.colorPrimary)
    private val text = GemTypeUi.themeColor(context, R.attr.md3OnPrimary, Color.WHITE, R.attr.colorOnPrimary)
    private val dark = ColorUtils.calculateLuminance(
        GemTypeUi.themeColor(context, R.attr.md3Surface, Color.WHITE, R.attr.colorSurface),
    ) < 0.5
    private val errorFill = if (dark) 0xFF8C1D18.toInt() else 0xFFF9DEDC.toInt()
    private val errorText = if (dark) 0xFFF9DEDC.toInt() else 0xFF410E0B.toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }

    /** Show one pill per label; the pill at [busy] (if any) shows a spinner and the others are hidden. */
    fun show(labels: List<String>, busy: Int = -1) {
        this.labels = labels
        this.busy = busy
        removeAllViews()
        labels.forEachIndexed { index, label ->
            if (busy >= 0 && index != busy) return@forEachIndexed
            addView(pill(index, label), LayoutParams(LayoutParams.WRAP_CONTENT, GemTypeUi.dp(context, 30)).apply {
                marginEnd = GemTypeUi.dp(context, 6)
            })
        }
        refresh()
    }

    /** Show [message] in one two-line error pill (tapping it calls [onPillClick] with -1). */
    fun showMessage(message: String) {
        labels = listOf(message)
        busy = -1
        removeAllViews()
        addView(pill(MESSAGE, message), LayoutParams(LayoutParams.WRAP_CONTENT, GemTypeUi.dp(context, 36)).apply {
            marginEnd = GemTypeUi.dp(context, 6)
        })
        refresh()
    }

    private fun pill(index: Int, label: String) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(GemTypeUi.dp(context, 12), 0, GemTypeUi.dp(context, 12), 0)
        background = if (index == MESSAGE) GemTypeUi.roundRect(context, errorFill, 18f)
            else GemTypeUi.roundRect(context, fill, 15f)
        contentDescription = label
        isClickable = true
        isFocusable = true
        setOnClickListener { onPillClick?.invoke(index) }
        if (busy >= 0 && index == busy) {
            val size = GemTypeUi.dp(context, 14)
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(text)
            }, LayoutParams(size, size).apply { marginEnd = GemTypeUi.dp(context, 6) })
        }
        val isMessage = index == MESSAGE
        addView(TextView(context).apply {
            this.text = if (isMessage) label else LRM + label // keeps the arrow first, even before an Urdu or Arabic name
            setTextColor(if (isMessage) errorText else this@ConvertPillBar.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isMessage) 12f else 14f)
            maxWidth = GemTypeUi.dp(context, if (isMessage) 190 else 130)
            maxLines = if (isMessage) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }

    private fun refresh() {
        visibility = if (labels.isEmpty() || suppressed) GONE else VISIBLE
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val width = if (visibility == VISIBLE) measuredWidth else 0
        if (width != occupiedWidth) {
            occupiedWidth = width
            onWidthChanged?.run()
        }
    }

    private companion object {
        const val ACCENT = 0xFF2F6FD1.toInt()
        const val LRM = "\u200E"
        const val MESSAGE = -1
    }
}
