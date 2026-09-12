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

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState

internal object TerminalGridRenderer {
    fun draw(
        graphics: GuiGraphicsExtractor,
        minecraftFont: Font,
        state: TerminalState,
        fontProfile: TerminalFontProfile,
        geometry: TerminalGridGeometry,
        nowMillis: Long,
    ) {
        drawBackgroundRuns(graphics, state, geometry)
        drawGlyphs(graphics, minecraftFont, state, fontProfile, geometry)
        if (TerminalRenderGeometry.drawCursor(state.cursorVisible, nowMillis)) {
            val cursor = geometry.cursor(state.cursor)
            graphics.fill(cursor.left, cursor.top, cursor.right, cursor.bottom, CURSOR_COLOR)
        }
    }

    private fun drawBackgroundRuns(
        graphics: GuiGraphicsExtractor,
        state: TerminalState,
        geometry: TerminalGridGeometry,
    ) {
        repeat(state.height) { y ->
            var start = 0
            while (start < state.width) {
                val background = cell(state, start, y).background
                var end = start + 1
                while (end < state.width && cell(state, end, y).background == background) end++
                if (background != 0) {
                    val first = geometry.cell(start, y)
                    val last = geometry.cell(end - 1, y)
                    graphics.fill(first.left, first.top, last.right, first.bottom, TerminalRenderGeometry.paletteColor(background))
                }
                start = end
            }
        }
    }

    private fun drawGlyphs(
        graphics: GuiGraphicsExtractor,
        minecraftFont: Font,
        state: TerminalState,
        fontProfile: TerminalFontProfile,
        geometry: TerminalGridGeometry,
    ) {
        val clip = geometry.glyphClip
        graphics.enableScissor(clip.left, clip.top, clip.right, clip.bottom)
        try {
            repeat(state.height) { y ->
                repeat(state.width) cellLoop@{ x ->
                    val cell = cell(state, x, y)
                    if (cell.codePoint == ' '.code) return@cellLoop
                    val renderedCodePoint = fontProfile.renderCodePoint(cell.codePoint)
                    val glyph =
                        Component
                            .literal(String(Character.toChars(renderedCodePoint)))
                            .withStyle { style ->
                                style
                                    .withFont(fontProfile.fontDescription)
                                    .withColor(TerminalRenderGeometry.paletteColor(cell.foreground))
                            }
                    val bounds = geometry.cell(x, y)
                    graphics.text(
                        minecraftFont,
                        glyph,
                        bounds.left,
                        bounds.top + fontProfile.glyphDrawOffsetY,
                        TerminalRenderGeometry.paletteColor(cell.foreground),
                        false,
                    )
                }
            }
        } finally {
            graphics.disableScissor()
        }
    }

    private fun cell(
        state: TerminalState,
        x: Int,
        y: Int,
    ): TerminalCell = state.cells[y * state.width + x]

    private val CURSOR_COLOR = 0xFFFFFFFF.toInt()
}
