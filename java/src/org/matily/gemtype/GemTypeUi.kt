// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/** Small view helpers shared by the panel and the conversion pills. */
internal object GemTypeUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun roundRect(context: Context, fill: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
            if (strokeColor != null) setStroke(dp(context, 1), strokeColor)
        }

    /** A colour attribute of the current theme: [attr] first, then [alt], else [fallback]. */
    fun themeColor(context: Context, attr: Int, fallback: Int, alt: Int = 0): Int {
        val value = TypedValue()
        for (a in intArrayOf(attr, alt)) {
            if (a != 0 && context.theme.resolveAttribute(a, value, true) &&
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
            ) return value.data
        }
        return fallback
    }
}
