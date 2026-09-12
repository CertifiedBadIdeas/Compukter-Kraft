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

import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalRenderGeometryTest {
    @Test
    fun `grid geometry can be positioned independently from terminal screen chrome`() {
        val grid = TerminalGridGeometry(17, 23, TerminalFontProfile.DINA)

        assertEquals(TerminalRect(17, 23, 323, 213), grid.bounds)
        assertEquals(TerminalRect(17, 23, 23, 33), grid.cell(0, 0))
        assertEquals(TerminalRect(317, 203, 323, 213), grid.cell(50, 18))
        assertEquals(TerminalRect(29, 62, 35, 63), grid.cursor(TerminalPosition(2, 3)))
    }

    @Test
    fun `compact panel keeps one fixed 51 by 19 grid centered after resize`() {
        val small = TerminalRenderGeometry(640, 360, TerminalFontProfile.DEFAULT)
        val large = TerminalRenderGeometry(1_280, 720, TerminalFontProfile.DEFAULT)

        assertEquals(51, small.columns)
        assertEquals(19, small.rows)
        assertEquals(306, small.grid.width)
        assertEquals(247, small.grid.height)
        assertEquals(322, small.panel.width)
        assertEquals(288, small.panel.height)
        assertEquals(TerminalRect(159, 36, 481, 324), small.panel)
        assertEquals(TerminalRect(167, 54, 473, 301), small.grid)
        assertEquals(TerminalRect(167, 303, 473, 316), small.footer)
        assertEquals(TerminalRect(167, 54, 173, 67), small.cell(0, 0))
        assertEquals(
            TerminalRect(467, 288, 473, 301),
            small.cell(50, 18),
        )
        assertEquals(small.panel.width, large.panel.width)
        assertEquals(small.panel.height, large.panel.height)
    }

    @Test
    fun `all glyphs share one whole grid clip under root scaling`() {
        val geometry = TerminalRenderGeometry(640, 360, TerminalFontProfile.COZETTE)

        assertTrue(geometry.glyphClip.left <= geometry.grid.left)
        assertTrue(geometry.glyphClip.top <= geometry.grid.top)
        assertTrue(geometry.glyphClip.right >= geometry.grid.right)
        assertTrue(geometry.glyphClip.bottom >= geometry.grid.bottom)
    }

    @Test
    fun `small viewport preserves scale and centers the overflowing panel`() {
        val geometry = TerminalRenderGeometry(300, 180, TerminalFontProfile.DEFAULT)

        assertEquals(TerminalRect(-11, -54, 311, 234), geometry.panel)
        assertEquals(TerminalRect(-3, -36, 303, 211), geometry.grid)
        assertEquals(TerminalRect(-3, 213, 303, 226), geometry.footer)
        assertEquals(-3, geometry.titleX)
        assertEquals(-49, geometry.titleY)
    }

    @Test
    fun `compact font reduces panel height and keeps toolbar inside title row`() {
        val cozette = TerminalRenderGeometry(640, 360, TerminalFontProfile.COZETTE)
        val dina = TerminalRenderGeometry(640, 360, TerminalFontProfile.DINA)

        assertEquals(cozette.columns, dina.columns)
        assertEquals(cozette.rows, dina.rows)
        assertEquals(57, cozette.panel.height - dina.panel.height)
        assertEquals(TerminalRect(159, 64, 481, 295), dina.panel)
        assertEquals(TerminalRect(297, 66, 373, 80), dina.ideButton)
        assertEquals(TerminalRect(377, 66, 473, 80), dina.fontButton)
        assertEquals(4, dina.fontButton.left - dina.ideButton.right)
        assertTrue(dina.ideButton.left >= dina.panel.left)
        assertTrue(dina.ideButton.right <= dina.panel.right)
        assertTrue(dina.ideButton.top >= dina.panel.top)
        assertTrue(dina.ideButton.bottom <= dina.grid.top)
        assertTrue(dina.fontButton.left >= dina.panel.left)
        assertTrue(dina.fontButton.right <= dina.panel.right)
        assertTrue(dina.fontButton.top >= dina.panel.top)
        assertTrue(dina.fontButton.bottom <= dina.grid.top)
        assertEquals(dina.grid.bottom + TerminalRenderGeometry.FOOTER_GAP, dina.footer.top)
        assertEquals(TerminalRenderGeometry.FOOTER_HEIGHT, dina.footer.height)
        assertTrue(dina.footer.bottom <= dina.panel.bottom - TerminalRenderGeometry.PANEL_PADDING)
    }

    @Test
    fun `palette mapping and cursor projection are exact and pure`() {
        val geometry = TerminalRenderGeometry(640, 360, TerminalFontProfile.DEFAULT)
        assertEquals(
            listOf(
                0xFF000000.toInt(),
                0xFFAA0000.toInt(),
                0xFF00AA00.toInt(),
                0xFFAA5500.toInt(),
                0xFF0000AA.toInt(),
                0xFFAA00AA.toInt(),
                0xFF00AAAA.toInt(),
                0xFFAAAAAA.toInt(),
                0xFF555555.toInt(),
                0xFFFF5555.toInt(),
                0xFF55FF55.toInt(),
                0xFFFFFF55.toInt(),
                0xFF5555FF.toInt(),
                0xFFFF55FF.toInt(),
                0xFF55FFFF.toInt(),
                0xFFFFFFFF.toInt(),
            ),
            (0..15).map(TerminalRenderGeometry::paletteColor),
        )
        val cursor = geometry.cursor(TerminalPosition(2, 3))
        val cell = geometry.cell(2, 3)
        assertEquals(TerminalRect(cell.left, cell.bottom - 1, cell.right, cell.bottom), cursor)
        assertTrue(TerminalRenderGeometry.drawCursor(authoritativeVisible = true, milliseconds = 0))
        assertFalse(TerminalRenderGeometry.drawCursor(authoritativeVisible = true, milliseconds = 500))
        assertFalse(TerminalRenderGeometry.drawCursor(authoritativeVisible = false, milliseconds = 0))
    }
}
