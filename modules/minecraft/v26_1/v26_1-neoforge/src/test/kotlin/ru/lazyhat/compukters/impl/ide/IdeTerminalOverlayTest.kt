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

import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import ru.lazyhat.compukters.impl.terminal.TerminalReplica
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeTerminalOverlayTest {
    @Test
    fun `Cozette overlay keeps the exact grid without a dedicated footer`() {
        val content = IdeRect(0, 0, 640, 360)

        val overlay = IdeTerminalOverlayGeometry.compute(content, TerminalFontProfile.COZETTE)

        assertTrue(overlay.supported)
        assertEquals(279, overlay.panel.height)
        assertEquals(51 * 6, overlay.grid?.width)
        assertEquals(19 * 13, overlay.grid?.height)
    }

    @Test
    fun `terminal lifecycle feedback uses the title without exposing revision`() {
        val token = UUID.fromString("d3354610-5460-4546-8546-000000000001")
        val replica = TerminalReplica(terminalState(revision = 42))
        val active =
            IdeTargetTerminalState.Active(
                token,
                7,
                replica,
            )

        assertEquals("Target terminal", terminalOverlayTitle(active))
        assertEquals("Terminal unavailable", terminalOverlayTitle(IdeTargetTerminalState.Closed))
        assertEquals("Opening target terminal...", terminalOverlayTitle(IdeTargetTerminalState.Opening(1)))
        assertEquals(
            "Resynchronizing target terminal...",
            terminalOverlayTitle(IdeTargetTerminalState.Resyncing(token, 7, replica)),
        )
        assertEquals(
            "connection lost · Click to retry",
            terminalOverlayTitle(IdeTargetTerminalState.Failed("connection lost", retryable = true)),
        )
        assertEquals(
            "access denied",
            terminalOverlayTitle(IdeTargetTerminalState.Failed("access denied", retryable = false)),
        )
    }

    @Test
    fun `every font keeps the exact guest grid and anchors the panel over immutable content`() {
        TerminalFontProfile.ALL.forEach { font ->
            val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, font)
            val editor = geometry.editor

            val overlay = IdeTerminalOverlayGeometry.compute(geometry.content, font)

            assertTrue(overlay.supported)
            assertEquals(geometry.content.right, overlay.panel.right)
            assertEquals(geometry.toolStripe.left, overlay.panel.right)
            assertEquals(51 * font.cellWidth, overlay.grid?.width)
            assertEquals(19 * font.cellHeight, overlay.grid?.height)
            assertTrue(overlay.panel.top > geometry.content.top)
            assertTrue(overlay.panel.bottom < geometry.content.bottom)
            assertEquals(editor, geometry.editor)
        }
    }

    @Test
    fun `small content produces a bounded unavailable surface instead of a cropped grid`() {
        val content = IdeRect(10, 20, 210, 140)

        val overlay = IdeTerminalOverlayGeometry.compute(content, TerminalFontProfile.COZETTE)

        assertFalse(overlay.supported)
        assertEquals(content, overlay.panel)
        assertNull(overlay.grid)
        assertTrue(overlay.messageBounds.left >= content.left)
        assertTrue(overlay.messageBounds.right <= content.right)
        assertEquals("Viewport is too small for the target terminal", overlay.unsupportedMessage)
    }

    private fun terminalState(revision: Long): TerminalState =
        TerminalState(
            revision,
            51,
            19,
            List(51 * 19) { TerminalCell(' '.code, 15, 0) },
            TerminalPosition(0, 0),
            true,
        )
}
