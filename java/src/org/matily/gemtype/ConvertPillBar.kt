// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.inputmethod.latin.R
import org.matily.gemtype.core.PillFit

/**
 * The accent pills in the suggestion strip. Two ways to sit there:
 *
 * - **Offer** ("→ বাংলা", or the quieter outlined fix chip): at the trailing end, beside the word
 *   suggestions, which keep the rest of the strip. Only as many pills as leave each word slot
 *   [MIN_SLOT_DP] show ([PillFit]), and they never take more than half the strip. An emoji for the
 *   words just typed comes first ([showEmoji]), so it sits right beside the words.
 * - **Cover** (a spinner while converting, "Undo", or a short error message, [showMessage]; a toast
 *   would be hidden from a keyboard whose app has notifications off): at the start, in place of the
 *   words for those few seconds.
 *
 * The strip positions this view and makes room for [occupiedWidth]; it is told when that or
 * [covering] changes ([onLayoutChanged]) and tells this view how wide its centre is ([availableWidth]).
 */
class ConvertPillBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    /** Called when [occupiedWidth] or [covering] changed. */
    var onLayoutChanged: Runnable? = null

    /** Called with the index of the tapped pill. */
    var onPillClick: ((Int) -> Unit)? = null

    /** Called with the tapped emoji. */
    var onEmojiClick: ((String) -> Unit)? = null

    /** Hidden while true (the strip's toolbox is open, or the keyboard has no GemType key). */
    var suppressed = false
        set(value) {
            field = value
            refresh()
        }

    /** Width of the strip's centre (between the left keys and the right slot), set by the strip. */
    var availableWidth = 0
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    /** Width this bar takes from the words while offering, 0 when hidden or covering. */
    var occupiedWidth = 0
        private set

    /** Showing a spinner, Undo or a message in place of the words. */
    var covering = false
        private set

    private var labels: List<String> = emptyList()
    private var busy = -1
    private var chip = -1
    private var cover = false
    private var emojiView: View? = null

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

    /**
     * Offer one pill per label, best first; the pill at [chip] is the quieter outlined one (a suggested
     * fix, not an action on all the text). With [busy] set, that pill alone shows, with a spinner, in
     * place of the words; with [cover] set (Undo), the pills also take the words' place. [emoji]
     * (none while covering) comes before the pills.
     */
    fun show(labels: List<String>, busy: Int = -1, chip: Int = -1, cover: Boolean = false, emoji: String? = null) {
        this.labels = labels
        this.busy = busy
        this.chip = chip
        this.cover = cover || busy >= 0
        removeAllViews()
        emojiView = if (emoji != null && !this.cover) emojiChip(emoji) else null
        emojiView?.let { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, GemTypeUi.dp(context, 30))) }
        labels.forEachIndexed { index, label ->
            if (busy >= 0 && index != busy) return@forEachIndexed
            addView(pill(index, label), LayoutParams(LayoutParams.WRAP_CONTENT, GemTypeUi.dp(context, 30)))
        }
        refresh()
    }

    /** Show [message] in one two-line error pill in place of the words (tapping it calls [onPillClick] with -1). */
    fun showMessage(message: String) {
        labels = listOf(message)
        busy = -1
        chip = -1
        cover = true
        removeAllViews()
        emojiView = null
        addView(pill(MESSAGE, message), LayoutParams(LayoutParams.WRAP_CONTENT, GemTypeUi.dp(context, 36)))
        refresh()
    }

    /** The emoji offer: just the emoji, on a faint tint of the accent so it reads as tappable. */
    private fun emojiChip(emoji: String) = TextView(context).apply {
        text = emoji
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        val inset = GemTypeUi.dp(context, 7)
        setPadding(inset, 0, inset, 0)
        minWidth = GemTypeUi.dp(context, 38)
        background = GemTypeUi.roundRect(context, ColorUtils.setAlphaComponent(fill, 0x1F), 15f)
        contentDescription = context.getString(R.string.gemtype_emoji_spoken, emoji)
        isClickable = true
        isFocusable = true
        setOnClickListener { onEmojiClick?.invoke(emoji) }
    }

    private fun pill(index: Int, label: String) = LinearLayout(context).apply {
        val isMessage = index == MESSAGE
        val isChip = index == chip
        // Offered pills are compact: they share the strip with the words.
        val compact = !cover && !isMessage
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val inset = GemTypeUi.dp(context, if (compact) 9 else 12)
        setPadding(inset, 0, inset, 0)
        background = when {
            isMessage -> GemTypeUi.roundRect(context, errorFill, 18f)
            isChip -> GemTypeUi.roundRect(
                context, ColorUtils.setAlphaComponent(fill, 0x24), 15f, ColorUtils.setAlphaComponent(fill, 0x99),
            )
            else -> GemTypeUi.roundRect(context, fill, 15f)
        }
        contentDescription = if (!isMessage && !isChip && label.startsWith(ARROW)) {
            context.getString(R.string.gemtype_pill_spoken, label.removePrefix(ARROW).trim())
        } else label
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
        addView(TextView(context).apply {
            // The arrow stays first, even before an Urdu or Arabic name (a chip's text isolates its own sides).
            this.text = if (isMessage || isChip) label else LRM + label
            setTextColor(if (isMessage) errorText else if (isChip) fill else this@ConvertPillBar.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isMessage) 12f else 14f)
            maxWidth = GemTypeUi.dp(context, if (isMessage) 220 else if (isChip) 150 else 130)
            tag = maxWidth // the width [refresh] starts from before it fits the pills in
            maxLines = if (isMessage) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }

    private fun refresh() {
        val emoji = emojiView
        visibility = if (labels.isEmpty() && emoji == null || suppressed) GONE else VISIBLE
        val showing = visibility == VISIBLE
        val gap = GemTypeUi.dp(context, GAP_DP)
        var width = 0
        if (showing) {
            (0 until childCount).forEach { i ->
                (getChildAt(i).layoutParams as LayoutParams).marginStart = if (i == 0) 0 else gap
            }
            val pills = (0 until childCount).map { getChildAt(it) }.filter { it !== emoji }
            pills.forEach { pill ->
                pill.visibility = VISIBLE
                pill.label().maxWidth = pill.label().tag as Int
            }
            if (!cover && availableWidth > 0 && pills.isNotEmpty()) {
                val natural = pills.map { it.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED); it.measuredWidth }
                // The emoji keeps its place; the pills fit in what is left.
                val room = availableWidth - (emoji?.let { it.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED); it.measuredWidth + gap } ?: 0)
                val fit = PillFit.layout(room, natural, GemTypeUi.dp(context, MIN_SLOT_DP), gap)
                pills.forEachIndexed { i, pill -> pill.visibility = if (i < fit.pills) VISIBLE else GONE }
                // A single pill wider than half the strip keeps its arrow and cuts its name short.
                val shown = pills.take(fit.pills)
                val over = natural.take(fit.pills).sum() + gap * (fit.pills - 1) - fit.width
                if (over > 0 && shown.isNotEmpty()) {
                    val label = shown.last().label()
                    label.maxWidth = (label.measuredWidth - over).coerceAtLeast(GemTypeUi.dp(context, 24))
                }
            }
            measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            width = measuredWidth
        }
        val occupied = if (showing && !cover) width + gap else 0
        val covers = showing && cover
        if (occupied != occupiedWidth || covers != covering) {
            occupiedWidth = occupied
            covering = covers
            onLayoutChanged?.run()
        }
    }

    /** A pill's text (its last child; a busy pill has a spinner first). */
    private fun View.label(): TextView = (this as LinearLayout).getChildAt(childCount - 1) as TextView

    private companion object {
        const val ACCENT = 0xFF2F6FD1.toInt()
        const val LRM = "‎"
        const val ARROW = "→"
        const val MESSAGE = -1
        /** A word slot narrower than this is hard to read. */
        const val MIN_SLOT_DP = 56
        /** Between two pills, and between the pills and the words. */
        const val GAP_DP = 6
    }
}
