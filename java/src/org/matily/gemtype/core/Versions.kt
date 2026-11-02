// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

/**
 * The last [max] results of one action, oldest first, and which of them the panel shows
 * (the newest, until the user steps back with ‹ ›). No android.* imports, so it is unit-tested on the JVM.
 */
class Versions<T>(private val max: Int = MAX) {
    private val items = ArrayList<T>()

    /** Position of the version shown (0-based). */
    var index = 0
        private set

    val size: Int get() = items.size
    val current: T? get() = items.getOrNull(index)

    /** Every version, oldest first. */
    val all: List<T> get() = items.toList()

    /** Keep [version] as the newest (the oldest goes when there are more than [max]) and show it. */
    fun add(version: T) {
        items.add(version)
        if (items.size > max) items.removeAt(0)
        index = items.size - 1
    }

    /** Show the version before / after the current one; stays put at either end. */
    fun step(delta: Int) {
        index = (index + delta).coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    companion object {
        const val MAX = 3
    }
}
