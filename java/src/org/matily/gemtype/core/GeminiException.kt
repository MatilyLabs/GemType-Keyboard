// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

class GeminiException(val kind: Kind, detail: String? = null) : Exception(detail ?: kind.name) {
    enum class Kind { NO_API_KEY, RATE_LIMITED, API_KEY_ERROR, MODEL_UNAVAILABLE, BUSY, TIMEOUT, HTTP, EMPTY_RESPONSE, NETWORK }

    /** One line a person can act on. */
    val friendly: String
        get() = when (kind) {
            Kind.NO_API_KEY -> "Add your Gemini API key in GemType settings first."
            Kind.RATE_LIMITED -> "Gemini rate limit reached. Wait a few seconds and try again."
            Kind.API_KEY_ERROR ->
                if (message.orEmpty().contains("API key not valid", ignoreCase = true)) {
                    "That API key isn't valid. Copy it again from aistudio.google.com."
                } else "Gemini rejected the request: $message"
            Kind.MODEL_UNAVAILABLE -> "This model isn't available to your key. Pick another model. ($message)"
            Kind.BUSY -> "Gemini is busy right now. Try again in a moment, or switch to Gemini 3.5 Flash-Lite."
            Kind.TIMEOUT -> "Gemini took too long to answer. Try again, or switch to Gemini 3.5 Flash-Lite."
            Kind.HTTP -> "Gemini returned an error ($message). Try again."
            Kind.EMPTY_RESPONSE -> "Gemini returned an empty answer. Try again."
            Kind.NETWORK -> "Couldn't reach Gemini. Check your connection."
        }
}
