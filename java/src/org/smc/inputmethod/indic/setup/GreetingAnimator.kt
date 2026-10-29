/*
 * Copyright 2026, Jishnu Mohan <jishnu7@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smc.inputmethod.indic.setup

/** Cross-fades through the welcome greetings, giving each its own [GreetingStyle]. */
internal class GreetingAnimator(
    private val word: GreetingTextView,
    private val greetings: Array<String>,
    private val styles: List<GreetingStyle>
) {
    private var index = 0
    private val cycler = object : Runnable {
        override fun run() {
            index = (index + 1) % greetings.size
            crossFadeTo(index)
            word.postDelayed(this, GREETING_HOLD_MS + GREETING_FADE_MS * 2)
        }
    }

    init {
        require(greetings.size == styles.size) { "one style per greeting" }
    }

    fun start() {
        index = 0
        word.animate().cancel()
        word.alpha = 1f
        show(0)
        word.removeCallbacks(cycler)
        if (greetings.size > 1) {
            word.postDelayed(cycler, GREETING_HOLD_MS + GREETING_FADE_MS)
        }
    }

    fun stop() {
        word.removeCallbacks(cycler)
        word.animate().cancel()
    }

    private fun crossFadeTo(i: Int) {
        word.animate().alpha(0f).setDuration(GREETING_FADE_MS).withEndAction {
            show(i)
            word.animate().alpha(1f).setDuration(GREETING_FADE_MS).start()
        }.start()
    }

    private fun show(i: Int) {
        word.style = styles[i]
        word.text = greetings[i]
    }

    companion object {
        private const val GREETING_HOLD_MS = 1500L
        private const val GREETING_FADE_MS = 450L
    }
}
