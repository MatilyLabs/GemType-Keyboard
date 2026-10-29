// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/** Whether a VPN is the active network right now. The app sets it; tests replace it. */
object VpnState {
    @Volatile var isActive: () -> Boolean = { false }
}

class GeminiException(val kind: Kind, detail: String? = null) : Exception(detail ?: kind.name) {
    enum class Kind {
        NO_API_KEY, RATE_LIMITED, API_KEY_ERROR, LOCATION_UNSUPPORTED, MODEL_UNAVAILABLE, BUSY, TIMEOUT, HTTP,
        EMPTY_RESPONSE, NETWORK,
    }

    /** One line a person can act on. */
    val friendly: String get() = friendly(VpnState.isActive())

    /** [friendly] for a known VPN state. A VPN is only mentioned when it can be the cause. */
    fun friendly(vpnActive: Boolean): String {
        val vpnHint = if (vpnActive) " If you're using a VPN, try turning it off." else ""
        return when (kind) {
            Kind.NO_API_KEY -> "Add your Gemini API key in GemType settings first."
            Kind.RATE_LIMITED -> "Gemini rate limit reached. Wait a few seconds and try again."
            Kind.API_KEY_ERROR ->
                if (message.orEmpty().contains("API key not valid", ignoreCase = true)) {
                    "That API key isn't valid. Copy it again from aistudio.google.com."
                } else "Gemini rejected the request: $message"
            Kind.LOCATION_UNSUPPORTED ->
                if (vpnActive) "Gemini isn't available through your VPN's location. Turn off the VPN (or pick another country) and try again."
                else "Gemini isn't available in your current location yet."
            Kind.MODEL_UNAVAILABLE -> "This model isn't available to your key. Pick another model. ($message)"
            Kind.BUSY -> "Gemini is busy right now. Try again in a moment, or switch to Gemini 3.5 Flash-Lite."
            Kind.TIMEOUT -> "Gemini took too long to answer. Try again, or switch to Gemini 3.5 Flash-Lite.$vpnHint"
            Kind.HTTP -> "Gemini returned an error ($message). Try again."
            Kind.EMPTY_RESPONSE -> "Gemini returned an empty answer. Try again."
            Kind.NETWORK -> "Couldn't reach Gemini. Check your connection.$vpnHint"
        }
    }

    /** [friendly] squeezed into two short lines, for the suggestion strip. */
    val brief: String get() = brief(VpnState.isActive())

    fun brief(vpnActive: Boolean): String =
        when (kind) {
            Kind.NO_API_KEY -> "Add your API key first."
            Kind.RATE_LIMITED -> "Rate limit reached. Wait a few seconds."
            Kind.API_KEY_ERROR ->
                if (message.orEmpty().contains("API key not valid", ignoreCase = true)) "That API key isn't valid."
                else "Gemini rejected the request."
            Kind.LOCATION_UNSUPPORTED ->
                if (vpnActive) "Not available via your VPN's location." else "Not available in your location yet."
            Kind.MODEL_UNAVAILABLE -> "Model not available. Pick another."
            Kind.BUSY -> "Gemini is busy. Try again soon."
            Kind.TIMEOUT -> if (vpnActive) "Took too long. Try turning off your VPN." else "Gemini took too long. Try again."
            Kind.HTTP -> "Gemini error. Try again."
            Kind.EMPTY_RESPONSE -> "Empty answer. Try again."
            Kind.NETWORK -> if (vpnActive) "Can't reach Gemini. Try turning off your VPN." else "Can't reach Gemini. Check your connection."
        }
}
