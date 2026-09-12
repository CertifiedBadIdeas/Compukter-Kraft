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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.resources.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalFontProfileTest {
    @Test
    fun `default profile exposes fixed Cozette metrics and resource`() {
        val profile = TerminalFontProfile.DEFAULT

        assertEquals("cozette", profile.id)
        assertEquals(
            Identifier.fromNamespaceAndPath("compukters", "terminal/cozette"),
            profile.fontDescription.id(),
        )
        assertEquals(6, profile.cellWidth)
        assertEquals(13, profile.cellHeight)
        assertEquals(10, profile.ascent)
        assertEquals(3, profile.glyphDrawOffsetY)
        assertEquals(0xFFFD, profile.replacementCodePoint)
    }

    @Test
    fun `fallback is deterministic and never indexes an unsupported code point`() {
        val profile = TerminalFontProfile.DEFAULT

        assertTrue(profile.supports('Ж'.code))
        assertFalse(profile.supports(0x1F680))
        assertEquals('Ж'.code, profile.renderCodePoint('Ж'.code))
        assertEquals(0xFFFD, profile.renderCodePoint(0x1F680))
        assertEquals(0xFFFD, profile.renderCodePoint(-1))
    }

    @Test
    fun `catalog resolves stable IDs and cycles in presentation order`() {
        assertEquals(TerminalFontProfile.COZETTE, TerminalFontProfile.fromId("cozette"))
        assertEquals(TerminalFontProfile.DINA, TerminalFontProfile.fromId("dina"))
        assertEquals(TerminalFontProfile.PROGGY_TINY, TerminalFontProfile.fromId("proggy_tiny"))
        assertEquals(TerminalFontProfile.DEFAULT, TerminalFontProfile.fromId("missing"))
        assertEquals(TerminalFontProfile.DEFAULT, TerminalFontProfile.fromId(null))
        assertEquals(TerminalFontProfile.DINA, TerminalFontProfile.COZETTE.next())
        assertEquals(TerminalFontProfile.PROGGY_TINY, TerminalFontProfile.DINA.next())
        assertEquals(TerminalFontProfile.COZETTE, TerminalFontProfile.PROGGY_TINY.next())
    }

    @Test
    fun `compact profiles use fixed six by ten cells and honest native fallback`() {
        listOf(TerminalFontProfile.DINA, TerminalFontProfile.PROGGY_TINY).forEach { profile ->
            assertEquals(6, profile.cellWidth)
            assertEquals(10, profile.cellHeight)
            assertEquals(8, profile.ascent)
            assertEquals(1, profile.glyphDrawOffsetY)
            assertEquals('?'.code, profile.replacementCodePoint)
            assertTrue(profile.supports('?'.code))
            assertFalse(profile.supports('Ж'.code))
            assertEquals('?'.code, profile.renderCodePoint('Ж'.code))
        }
    }
}
