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

class IdeCodeGlyphLayoutTest {
    @Test
    fun `code glyphs occupy exact font cells independent of glyph advance`() {
        val font = TerminalFontProfile.DINA

        val glyphs = IdeCodeGlyphLayout.layout("Wi Ж", 17, font)

        assertEquals(listOf("W", "i", "?"), glyphs.map { it.value })
        assertEquals(listOf(17, 17 + font.cellWidth, 17 + font.cellWidth * 3), glyphs.map { it.x })
    }
}
