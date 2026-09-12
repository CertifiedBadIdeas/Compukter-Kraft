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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeRenderGeometryTest {
    @Test
    fun `normal scaled viewport produces exact half-open panels and code cells`() {
        val geometry = geometry()

        assertEquals(IdeRect(0, 0, 960, 540), geometry.panel)
        assertEquals(IdeRect(940, 0, 960, 540), geometry.toolStripe)
        assertEquals(IdeRect(0, 0, 940, 24), geometry.header)
        assertEquals(IdeRect(0, 24, 940, 46), geometry.toolbar)
        assertEquals(IdeRect(0, 522, 940, 540), geometry.status)
        assertEquals(IdeRect(0, 46, 180, 522), geometry.tree)
        assertEquals(IdeRect(180, 46, 181, 522), geometry.treeSplitter)
        assertEquals(IdeRect(181, 46, 940, 401), geometry.editor)
        assertEquals(IdeRect(181, 401, 940, 402), geometry.diagnosticsSplitter)
        assertEquals(IdeRect(181, 402, 940, 522), geometry.diagnostics)
        assertEquals(126, geometry.codeColumns)
        assertEquals(35, geometry.codeRows)
        assertEquals(0, geometry.panel.left % 2, "1920 physical pixels at GUI scale 2 maps deterministically to 960 GUI pixels")
    }

    @Test
    fun `small viewport collapses diagnostics then hides tree`() {
        val collapsed =
            IdeRenderGeometry.compute(500, 240, 96, 64, diagnosticsExpanded = true, treeVisible = true, font = TerminalFontProfile.DINA)
        assertTrue(collapsed.supported)
        assertFalse(collapsed.diagnosticsExpanded)
        assertTrue(collapsed.treeVisible)
        assertTrue(collapsed.editor.width >= IdeRenderGeometry.MINIMUM_EDITOR_WIDTH)
        assertTrue(collapsed.editor.height >= IdeRenderGeometry.MINIMUM_EDITOR_HEIGHT)

        val hidden =
            IdeRenderGeometry.compute(300, 200, 96, 64, diagnosticsExpanded = true, treeVisible = true, font = TerminalFontProfile.DINA)
        assertTrue(hidden.supported)
        assertFalse(hidden.diagnosticsExpanded)
        assertFalse(hidden.treeVisible)
        assertEquals(null, hidden.tree)
        assertEquals(null, hidden.treeSplitter)

        val unsupported =
            IdeRenderGeometry.compute(200, 170, 96, 64, diagnosticsExpanded = true, treeVisible = true, font = TerminalFontProfile.DINA)
        assertFalse(unsupported.supported)
        assertIs<IdeGeometryFallback.Unsupported>(unsupported.fallback)
        assertTrue(unsupported.unsupportedMessage.isNotBlank())
    }

    @Test
    fun `explicitly hidden panels remain hidden and all fonts derive their own row count`() {
        TerminalFontProfile.ALL.forEach { font ->
            val geometry =
                IdeRenderGeometry.compute(960, 540, 180, 120, diagnosticsExpanded = false, treeVisible = false, font = font)
            assertFalse(geometry.treeVisible)
            assertFalse(geometry.diagnosticsExpanded)
            assertEquals(geometry.editor.width / font.cellWidth, geometry.codeColumns)
            assertEquals(geometry.editor.height / font.cellHeight, geometry.codeRows)
        }
    }

    @Test
    fun `splitter projections clamp before persistence`() {
        val geometry = geometry()

        assertEquals(IdeRenderGeometry.MINIMUM_TREE_WIDTH, geometry.treeWidthAt(-10_000))
        assertEquals(699, geometry.treeWidthAt(10_000))
        assertEquals(IdeRenderGeometry.MINIMUM_DIAGNOSTICS_HEIGHT, geometry.diagnosticsHeightAt(10_000))
        assertEquals(355, geometry.diagnosticsHeightAt(-10_000))
        assertEquals(233, geometry.treeWidthAt(geometry.content.left + 233))
        assertEquals(151, geometry.diagnosticsHeightAt(geometry.content.bottom - 151))
    }

    @Test
    fun `completion popup chooses below then above and remains clipped to editor`() {
        val geometry = geometry()
        val below = geometry.completionPopup(IdeRect(300, 100, 306, 110), 260, 100)
        assertEquals(CompletionPopupPlacement.Below, below.placement)
        assertEquals(IdeRect(300, 110, 560, 210), below.bounds)

        val above = geometry.completionPopup(IdeRect(900, 350, 906, 360), 260, 100)
        assertEquals(CompletionPopupPlacement.Above, above.placement)
        assertEquals(IdeRect(680, 250, 940, 350), above.bounds)
    }

    @Test
    fun `anchored semantic popup stays inside editor at both edge anchors`() {
        val geometry = geometry()
        val below = geometry.anchoredPopup(IdeRect(181, 46, 187, 56), 300, 90)
        val above = geometry.anchoredPopup(IdeRect(934, 391, 940, 401), 300, 90)

        assertEquals(AnchoredPopupPlacement.Below, below.placement)
        assertEquals(IdeRect(181, 56, 481, 146), below.bounds)
        assertEquals(AnchoredPopupPlacement.Above, above.placement)
        assertEquals(IdeRect(640, 301, 940, 391), above.bounds)
    }

    private fun geometry() =
        IdeRenderGeometry.compute(
            viewportWidth = 960,
            viewportHeight = 540,
            treeWidth = 180,
            diagnosticsHeight = 120,
            diagnosticsExpanded = true,
            treeVisible = true,
            font = TerminalFontProfile.DINA,
        )
}
