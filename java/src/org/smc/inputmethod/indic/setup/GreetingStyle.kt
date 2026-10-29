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
        /**
         * The greetings to cycle through, each with its style. [texts] are the
         * `gemtype_setup_greetings`: Welcome, Bangla, Hindi, Urdu, Nepali. When the phone is in
         * neither Bangladesh nor India, Bangla is shown twice, once in each flag.
         */
        fun cycle(
            texts: Array<String>, banglaStyle: BanglaStyle, night: Boolean
        ): List<Pair<String, GreetingStyle>> {
            val (welcome, bangla, hindi, urdu, nepali) = texts
            val bangladesh = Disc(
                color = if (night) 0xFF2BB58A.toInt() else 0xFF006A4E.toInt(),
                discColor = 0xFFF42A41.toInt(),
                haloColor = 0xFFFFFFFF.toInt(),
                haloDp = 1.5f
            )
            return listOfNotNull(
                welcome to Horizontal(intArrayOf(0xFF1F5FD0.toInt(), 0xFF3D8BFF.toInt(), 0xFF6FA8FF.toInt())),
                bangla to if (banglaStyle == BanglaStyle.INDIA) indiaStyle() else bangladesh,
                hindi to indiaStyle(),
                urdu to Solid(if (night) 0xFF3FAE70.toInt() else 0xFF01411C.toInt()),
                // Spaced away from the first Bangla and from Hindi so the two flags alternate.
                if (banglaStyle == BanglaStyle.BOTH) bangla to indiaStyle() else null,
                nepali to Solid(
                    0xFFDC143C.toInt(),
                    outlineColor = if (night) 0xFF6E94E0.toInt() else 0xFF003893.toInt(),
                    outlineDp = 1f
                )
            )
        }

        private fun indiaStyle() = Split(top = 0xFFFF9933.toInt(), bottom = 0xFF138808.toInt())

        fun isNight(context: Context): Boolean =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }
}

/** Which flag the Bangla greeting wears; BOTH shows it twice, once in each. */
internal enum class BanglaStyle { BANGLADESH, INDIA, BOTH }

/**
 * The first of [countries] (most telling first) that is Bangladesh or India decides; with
 * neither, we can't tell which Bangla the user writes, so both are shown.
 */
internal fun banglaStyleFor(countries: List<String?>): BanglaStyle =
    when (countries.firstOrNull { it.equals("BD", true) || it.equals("IN", true) }?.uppercase()) {
        "BD" -> BanglaStyle.BANGLADESH
        "IN" -> BanglaStyle.INDIA
        else -> BanglaStyle.BOTH
    }

/**
 * Hints at the phone's country, most telling first: the region the user picked for the phone's
 * language, then the SIM's, then the network's. A Bangladeshi abroad with the phone in "English
 * (US)" still has a Bangladeshi SIM. No permission needed.
 */
internal fun phoneCountries(context: Context): List<String?> {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    return listOf(Locale.getDefault().country, tm?.simCountryIso, tm?.networkCountryIso)
}
