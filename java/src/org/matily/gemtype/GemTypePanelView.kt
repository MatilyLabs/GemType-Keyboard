// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.android.inputmethod.latin.R
import com.android.inputmethod.latin.utils.ResourceUtils

/**
 * The panel container: sized like the keyboard it replaces (keyboard + suggestion
 * strip) so the host app's layout doesn't jump when switching. Contents are wired up
 * by [GemTypePanelController].
 */
class GemTypePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.emojiPalettesViewStyle,
) : LinearLayout(context, attrs, defStyle) {

    private var totalHeight = 0

    /** Height to occupy, excluding the bottom navigation-bar padding InputView adds. */
    fun setTotalHeight(height: Int) {
        if (height > 0 && height != totalHeight) {
            totalHeight = height
            requestLayout()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val gutter = ResourceUtils.getKeyboardGutterWidth(context)
        setPadding(gutter, paddingTop, gutter, paddingBottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val res = context.resources
        val width = ResourceUtils.getKeyboardContentWidth(context) + paddingLeft + paddingRight
        val height = (if (totalHeight > 0) totalHeight else
            ResourceUtils.getDefaultKeyboardHeight(res) +
                res.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)) +
            paddingTop + paddingBottom
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
