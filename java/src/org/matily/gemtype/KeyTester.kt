// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matily.gemtype.core.GeminiException

/**
 * Runs the "Test key" request in the app-wide scope so it survives rotation or
 * leaving the screen; the settings screen just observes [state].
 */
object KeyTester {
    sealed interface State {
        data object Idle : State
        data object Running : State
        data class Ok(val model: String, val seconds: Double) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun reset() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }

    /**
     * Tests the saved key, or, when [candidateKey] is given, that key instead: the first-run
     * setup tests what the user pasted and saves it only if the request succeeds.
     */
    fun run(context: Context, candidateKey: String? = null) {
        if (_state.value is State.Running) return
        val appContext = context.applicationContext
        _state.value = State.Running
        GemType.appScope.launch {
            _state.value = try {
                val saved = withContext(Dispatchers.IO) { GemTypeSettings.load(appContext) }
                val config = if (candidateKey == null) saved else saved.copy(apiKey = candidateKey.trim())
                val ms = GemType.client.test(config)
                if (candidateKey != null) {
                    withContext(Dispatchers.IO) { GemTypeSettings.setApiKey(appContext, config.apiKey) }
                }
                State.Ok(config.model, ms / 1000.0)
            } catch (e: GeminiException) {
                State.Failed(e.friendly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never leave the button spinning, whatever went wrong (e.g. Keystore failure).
                State.Failed("Something went wrong. Try again.")
            }
        }
    }
}
