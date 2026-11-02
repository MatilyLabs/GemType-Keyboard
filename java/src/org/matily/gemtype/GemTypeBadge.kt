// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * The small red count bubble on the GemType mark in the suggestion strip: how many mistakes "check as you
 * type" found. It is not clickable, so a tap on it goes to the mark underneath.
 */
class GemTypeBadge @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {

    /** Hidden while true (the keyboard has no GemType mark right now). */
    var suppressed = false
        set(value) {
            field = value
            refresh()
        }

    var count = 0
        set(value) {
            field = value
            text = if (value > MAX) "$MAX+" else value.toString()
            refresh()
        }

    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
        minWidth = GemTypeUi.dp(context, 16)
        minHeight = GemTypeUi.dp(context, 16)
        setPadding(GemTypeUi.dp(context, 4), 0, GemTypeUi.dp(context, 4), 0)
        background = GemTypeUi.roundRect(context, RED, 8f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    private fun refresh() {
        visibility = if (count > 0 && !suppressed) VISIBLE else GONE
    }

    private companion object {
        const val RED = 0xFFD93025.toInt()
        const val MAX = 9
    }
}
