// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionsTest {
    @Test fun startsEmpty() {
        val v = Versions<String>()
        assertEquals(0, v.size)
        assertNull(v.current)
        v.step(1)
        v.step(-1)
        assertNull(v.current)
    }

    @Test fun showsTheNewestVersionAfterAdding() {
        val v = Versions<String>()
        v.add("a")
        assertEquals("a", v.current)
        v.add("b")
        assertEquals("b", v.current)
        assertEquals(1, v.index)
        assertEquals(listOf("a", "b"), v.all)
    }

    @Test fun keepsOnlyTheLastThree() {
        val v = Versions<String>()
        listOf("a", "b", "c", "d", "e").forEach(v::add)
        assertEquals(listOf("c", "d", "e"), v.all)
        assertEquals("e", v.current)
        assertEquals(3, v.size)
    }

    @Test fun steppingStopsAtBothEnds() {
        val v = Versions<String>()
        listOf("a", "b", "c").forEach(v::add)
        v.step(-1)
        assertEquals("b", v.current)
        v.step(-1)
        v.step(-1)
        assertEquals("a", v.current)
        assertEquals(0, v.index)
        v.step(1)
        v.step(1)
        v.step(1)
        assertEquals("c", v.current)
    }

    @Test fun addingAfterSteppingBackShowsTheNewOne() {
        val v = Versions<String>()
        listOf("a", "b").forEach(v::add)
        v.step(-1)
        v.add("c")
        assertEquals("c", v.current)
        assertEquals(listOf("a", "b", "c"), v.all)
    }
}
