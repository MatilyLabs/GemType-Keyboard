// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.smc.inputmethod.indic.setup

import android.content.Context
import android.content.res.Configuration
import android.telephony.TelephonyManager
import java.util.Locale

/** How one welcome greeting is painted. Colours are resolved for the current theme up front. */
internal sealed class GreetingStyle {
    /** A horizontal gradient across the word's width. */
    class Horizontal(val colors: IntArray) : GreetingStyle()

    /** A flat colour, optionally outlined; [outlineDp] is the stroke width. */
    class Solid(val color: Int, val outlineColor: Int = 0, val outlineDp: Float = 0f) : GreetingStyle()

    /** Two colours split by a hard stop halfway down the line. */
    class Split(val top: Int, val bottom: Int) : GreetingStyle()

    /** The Bangladesh flag: [color] text over a round [discColor] disc, with a white halo. */
    class Disc(val color: Int, val discColor: Int, val haloColor: Int, val haloDp: Float) : GreetingStyle()

    companion object {
        /** The cycle, in the same order as the `gemtype_setup_greetings` texts. */
        fun cycle(banglaStyle: BanglaStyle, night: Boolean): List<GreetingStyle> = listOf(
            Horizontal(intArrayOf(0xFF1F5FD0.toInt(), 0xFF3D8BFF.toInt(), 0xFF6FA8FF.toInt())),
            when (banglaStyle) {
                BanglaStyle.BANGLADESH -> Disc(
                    color = if (night) 0xFF2BB58A.toInt() else 0xFF006A4E.toInt(),
                    discColor = 0xFFF42A41.toInt(),
                    haloColor = 0xFFFFFFFF.toInt(),
                    haloDp = 1.5f
                )
                BanglaStyle.INDIA -> indiaStyle()
            },
            indiaStyle(),
            Solid(if (night) 0xFF3FAE70.toInt() else 0xFF01411C.toInt()),
            Solid(
                0xFFDC143C.toInt(),
                outlineColor = if (night) 0xFF6E94E0.toInt() else 0xFF003893.toInt(),
                outlineDp = 1f
            )
        )

        private fun indiaStyle() = Split(top = 0xFFFF9933.toInt(), bottom = 0xFF138808.toInt())

        fun isNight(context: Context): Boolean =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }
}

/** Which flag the Bangla greeting wears. */
internal enum class BanglaStyle { BANGLADESH, INDIA }

/** Bangla is Bangladesh's flag unless the phone is in India, where it wears India's. */
internal fun banglaStyleFor(country: String?): BanglaStyle =
    if (country.equals("IN", ignoreCase = true)) BanglaStyle.INDIA else BanglaStyle.BANGLADESH

/** The phone's country: the locale's, else the SIM's, else the network's. No permission needed. */
internal fun phoneCountry(context: Context): String {
    Locale.getDefault().country.takeIf { it.isNotEmpty() }?.let { return it }
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return ""
    return tm.simCountryIso.takeIf { !it.isNullOrEmpty() }
        ?: tm.networkCountryIso.takeIf { !it.isNullOrEmpty() }
        ?: ""
}
