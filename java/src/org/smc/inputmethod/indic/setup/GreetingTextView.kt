// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** The welcome word. Paints a [GreetingStyle]: gradients, outlines and the flag disc behind the text. */
internal class GreetingTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    var style: GreetingStyle = GreetingStyle.Solid(currentTextColor)
        set(value) {
            field = value
            invalidate()
        }

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout == null || layout.lineCount == 0) {
            super.onDraw(canvas)
            return
        }
        val density = resources.displayMetrics.density
        val left = layout.getLineLeft(0)
        val width = layout.getLineWidth(0)
        val top = layout.getLineTop(0).toFloat()
        val bottom = layout.getLineBottom(0).toFloat()
        val textPaint = paint
        when (val s = style) {
            is GreetingStyle.Horizontal -> {
                textPaint.shader = LinearGradient(
                    left, 0f, left + width, 0f, s.colors, null, Shader.TileMode.CLAMP
                )
                super.onDraw(canvas)
            }
            is GreetingStyle.Solid -> {
                if (s.outlineDp > 0f) {
                    drawStroked(canvas, s.outlineColor, s.outlineDp * 2 * density)
                }
                textPaint.shader = flat(s.color)
                super.onDraw(canvas)
            }
            is GreetingStyle.Split -> {
                textPaint.shader = LinearGradient(
                    0f, top, 0f, bottom, intArrayOf(s.top, s.top, s.bottom, s.bottom),
                    floatArrayOf(0f, .5f, .5f, 1f), Shader.TileMode.CLAMP
                )
                super.onDraw(canvas)
            }
            is GreetingStyle.Disc -> {
                // A perfect circle sized to the line, centred on it, sitting at 45% of the word.
                getLineBounds(0, lineBounds)
                val diameter = DISC_TO_LINE * (bottom - top)
                discPaint.color = s.discColor
                canvas.drawCircle(
                    compoundPaddingLeft + left + DISC_X * width,
                    lineBounds.top + (bottom - top) / 2f, diameter / 2f, discPaint
                )
                drawStroked(canvas, s.haloColor, s.haloDp * 2 * density)
                textPaint.shader = flat(s.color)
                super.onDraw(canvas)
            }
        }
        textPaint.shader = null
    }

    /** Draws the text as a stroke only; the fill pass then covers the inner half. */
    private fun drawStroked(canvas: Canvas, color: Int, strokeWidth: Float) {
        val textPaint = paint
        textPaint.shader = flat(color)
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = strokeWidth
        textPaint.strokeJoin = Paint.Join.ROUND
        super.onDraw(canvas)
        textPaint.style = Paint.Style.FILL
    }

    // TextView.onDraw resets the paint colour from the text colour, so a shader is the only way to
    // change colour between passes without invalidating mid-draw.
    private fun flat(color: Int) =
        LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP)

    private companion object {
        const val DISC_TO_LINE = 0.85f
        const val DISC_X = 0.45f
    }
}
