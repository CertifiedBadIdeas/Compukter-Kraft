/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeSplitterInteractionTest {
    @Test
    fun `splitter drag is transient until release then persists clamped layout`() {
        val saved = mutableListOf<IdeLayoutSettings>()
        val initial = IdeLayoutSettings.admit(180, 120, true)
        val interaction = IdeSplitterInteraction(initial, saved::add)
        val geometry = geometry()

        assertTrue(interaction.press(geometry.treeSplitter!!.left, 100, geometry))
        assertTrue(interaction.drag(10_000, 100, geometry))
        assertTrue(saved.isEmpty())
        assertEquals(699, interaction.layout.treeWidth)
        assertTrue(interaction.release())
        assertEquals(listOf(IdeLayoutSettings.admit(699, 120, true)), saved)
    }

    @Test
    fun `focus loss releases capture without inventing another drag`() {
        val saved = mutableListOf<IdeLayoutSettings>()
        val interaction = IdeSplitterInteraction(IdeLayoutSettings.admit(180, 120, true), saved::add)
        val geometry = geometry()
        interaction.press(300, geometry.diagnosticsSplitter!!.top, geometry)
        interaction.drag(300, geometry.content.bottom - 151, geometry)

        interaction.focusLost()

        assertFalse(interaction.captured)
        assertEquals(151, interaction.layout.diagnosticsHeight)
        assertEquals(1, saved.size)
    }

    private fun geometry() = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
}
