// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

import com.android.inputmethod.latin.BuildConfig
import com.android.inputmethod.latin.R
import com.google.android.material.color.MaterialColors

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A looping picture of how GemType works, drawn with plain views: a finger taps the blue mark in
 * a mini keyboard strip, a panel slides up with the corrected text, the finger taps Replace, and
 * the text above changes. A single clock drives every view along one timeline.
 */
internal class GemTypeDemoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val surface = themeColor(com.google.android.material.R.attr.colorSurface)
    private val keyboard = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    private val outline = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    private val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
    private val primary = themeColor(androidx.appcompat.R.attr.colorPrimary)
    private val onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary)

    private val before = label(context.getString(R.string.gemtype_setup_practice_text), onSurface)
    private val after = label(context.getString(R.string.gemtype_setup_demo_fixed), onSurface)
    private val strip = FrameLayout(context)
    private val mark = ImageView(context)
    private val keys = KeysView(context, surface)
    private val panel = FrameLayout(context)
    private val replace = TextView(context)
    private val finger = View(context)

    init {
        background = rounded(surface, outline, dp(16f))
        clipChildren = true
        clipToOutline = true

        addView(before, top(dp(15f)))
        addView(after, top(dp(15f)))

        // The keyboard: a suggestion strip with the GemType mark, then two rows of keys.
        val keyboardTop = dp(52f).roundToInt()
        val keyboardBackground = GradientDrawable().apply {
            setColor(keyboard)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(16f), dp(16f), dp(16f), dp(16f))
        }
        val keyboardHeight = dp(HEIGHT_DP).roundToInt() - keyboardTop
        addView(View(context).apply { background = keyboardBackground }, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, keyboardHeight, Gravity.BOTTOM
        ))
        addView(strip, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36f).roundToInt()).apply {
            topMargin = keyboardTop
        })
        mark.setImageResource(R.drawable.gemtype_mark)
        mark.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        strip.addView(mark, FrameLayout.LayoutParams(dp(26f).roundToInt(), dp(26f).roundToInt(), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(10f).roundToInt()
        })
        for (i in 0 until 3) {
            strip.addView(View(context).apply { background = rounded(surface, 0, dp(7f)) },
                FrameLayout.LayoutParams(dp(54f).roundToInt(), dp(14f).roundToInt(), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                    marginStart = dp(62f + i * 66f).roundToInt()
                })
        }
        addView(keys, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(76f).roundToInt(), Gravity.BOTTOM))

        // The GemType panel, covering the keyboard.
        panel.background = GradientDrawable().apply {
            setColor(surface)
            setStroke(dp(1f).roundToInt(), outline)
            cornerRadii = floatArrayOf(dp(14f), dp(14f), dp(14f), dp(14f), dp(16f), dp(16f), dp(16f), dp(16f))
        }
        addView(panel, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, keyboardHeight, Gravity.BOTTOM))
        val chips = listOf("Fix", "Improve", "Shorten")
        for ((i, name) in chips.withIndex()) {
            panel.addView(chip(name, selected = i == 0), FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(24f).roundToInt()
            ).apply {
                topMargin = dp(10f).roundToInt()
                marginStart = dp(12f + i * 64f).roundToInt()
            })
        }
        panel.addView(result(), FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(42f).roundToInt()
            marginStart = dp(14f).roundToInt()
        })
        replace.apply {
            text = context.getString(R.string.gemtype_replace)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(onPrimary)
            gravity = Gravity.CENTER
            background = rounded(primary, 0, dp(15f))
            val pad = dp(16f).roundToInt()
            setPadding(pad, 0, pad, 0)
        }
        panel.addView(replace, FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, dp(30f).roundToInt(), Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dp(10f).roundToInt()
            marginEnd = dp(12f).roundToInt()
        })

        finger.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(90, Color.red(onSurface), Color.green(onSurface), Color.blue(onSurface)))
            setStroke(dp(2f).roundToInt(), onSurface)
        }
        addView(finger, FrameLayout.LayoutParams(dp(24f).roundToInt(), dp(24f).roundToInt()))
        finger.alpha = 0f
        panel.translationY = keyboardHeight.toFloat()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The picture is laid out for one height.
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP).roundToInt(), MeasureSpec.EXACTLY)
        )
    }

    fun start() {
        if (running || !isAttachedToWindow) {
            return
        }
        if (!animationsEnabled()) {
            post { render(STILL_FRAME) }  // animations are off: show the finished picture
            return
        }
        running = true
        startedAtNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * Honours "remove animations" in the system settings. Debug builds animate regardless, so
     * the picture can be checked on emulators that run with animations switched off.
     */
    private fun animationsEnabled(): Boolean = BuildConfig.DEBUG ||
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    // One clock for every view, so the loop never drifts apart.
    private var running = false
    private var startedAtNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (startedAtNanos == 0L) startedAtNanos = frameTimeNanos
            val elapsedMs = (frameTimeNanos - startedAtNanos) / 1_000_000L
            render((elapsedMs % LOOP_MS).toFloat() / LOOP_MS)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---- the timeline ---------------------------------------------------------------------

    private val decelerate = DecelerateInterpolator(1.6f)
    private val accelerate = AccelerateInterpolator(1.4f)

    private fun render(t: Float) {
        if (width == 0) return
        val startX = width * 0.80f
        val startY = height * 0.86f
        val markX = strip.left + mark.left + mark.width / 2f
        val markY = strip.top + mark.top + mark.height / 2f
        val replaceX = panel.left + replace.left + replace.width * 0.55f
        val replaceY = panel.top + replace.top + replace.height * 0.55f

        val toMark = smooth(phase(t, 0.06f, 0.22f))
        val toReplace = smooth(phase(t, 0.46f, 0.62f))
        val x = lerp(lerp(startX, markX, toMark), replaceX, toReplace)
        val y = lerp(lerp(startY, markY, toMark), replaceY, toReplace)
        val tapMark = pulse(phase(t, 0.22f, 0.29f))
        val tapReplace = pulse(phase(t, 0.62f, 0.69f))

        finger.x = x - finger.width / 2f
        finger.y = y - finger.height / 2f
        finger.alpha = phase(t, 0.03f, 0.08f) * (1f - phase(t, 0.80f, 0.86f))
        val fingerScale = 1f - 0.3f * tapMark - 0.3f * tapReplace
        finger.scaleX = fingerScale
        finger.scaleY = fingerScale

        mark.scaleX = 1f + 0.2f * tapMark
        mark.scaleY = mark.scaleX
        replace.scaleX = 1f - 0.08f * tapReplace
        replace.scaleY = replace.scaleX

        val up = decelerate.getInterpolation(phase(t, 0.29f, 0.41f))
        val down = accelerate.getInterpolation(phase(t, 0.70f, 0.78f))
        panel.translationY = panel.height * (1f - up * (1f - down))

        val fixed = phase(t, 0.70f, 0.77f) * (1f - phase(t, 0.94f, 1f))
        after.alpha = fixed
        before.alpha = 1f - fixed
    }

    private fun phase(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
    private fun smooth(p: Float) = p * p * (3f - 2f * p)
    private fun pulse(p: Float) = sin(PI * p).toFloat()
    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p

    // ---- building blocks ------------------------------------------------------------------

    private fun themeColor(attr: Int) = MaterialColors.getColor(this, attr, Color.GRAY)

    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (stroke != 0) setStroke(dp(1f).roundToInt(), stroke)
    }

    private fun label(text: CharSequence, color: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        maxLines = 1
    }

    private fun top(topMargin: Float) = FrameLayout.LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
    ).apply {
        this.topMargin = topMargin.roundToInt()
        marginStart = dp(16f).roundToInt()
        marginEnd = dp(16f).roundToInt()
    }

    private fun chip(name: String, selected: Boolean) = TextView(context).apply {
        text = name
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        val pad = dp(10f).roundToInt()
        setPadding(pad, 0, pad, 0)
        if (selected) {
            setTextColor(onPrimary)
            background = rounded(primary, 0, dp(12f))
        } else {
            setTextColor(onSurface)
            background = rounded(Color.TRANSPARENT, outline, dp(12f))
        }
    }

    /** The corrected sentence with the changed words in green, like the real panel's diff. */
    private fun result(): TextView {
        val text = SpannableString(context.getString(R.string.gemtype_setup_demo_fixed))
        val green = if (isDark(surface)) Color.rgb(0x6F, 0xD3, 0x9A) else Color.rgb(0x1B, 0x8A, 0x4B)
        for (word in Regex("\\S+").findAll(text)) {
            if (word.value.trimEnd('.') in CHANGED_WORDS) {
                text.setSpan(
                    ForegroundColorSpan(green), word.range.first, word.range.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return label(text, onSurface).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
    }

    private fun isDark(color: Int) =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) < 128

    /** Two rows of faint keys. */
    private class KeysView(context: Context, keyColor: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = keyColor }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val gap = 5 * d
            val rowHeight = 26 * d
            val rows = intArrayOf(8, 7)
            for ((r, count) in rows.withIndex()) {
                val side = if (r == 0) 8 * d else 22 * d
                val keyWidth = (width - 2 * side - gap * (count - 1)) / count
                val top = 6 * d + r * (rowHeight + gap)
                for (i in 0 until count) {
                    val left = side + i * (keyWidth + gap)
                    rect.set(left, top, left + keyWidth, top + rowHeight)
                    canvas.drawRoundRect(rect, 6 * d, 6 * d, paint)
                }
            }
        }
    }

    companion object {
        private const val HEIGHT_DP = 164f
        private const val LOOP_MS = 7500L
        private const val STILL_FRAME = 0.88f
        private val CHANGED_WORDS = setOf("I", "went", "school")
    }
}
