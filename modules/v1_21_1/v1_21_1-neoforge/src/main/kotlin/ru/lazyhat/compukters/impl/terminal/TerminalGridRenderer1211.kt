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

@file:Suppress("ktlint:standard:filename")

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState

internal object TerminalGridRenderer {
    fun draw(
        graphics: GuiGraphics,
        minecraftFont: Font,
        state: TerminalState,
        fontProfile: TerminalFontProfile,
        geometry: TerminalGridGeometry,
        nowMillis: Long,
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
        val clip = geometry.glyphClip
        graphics.enableScissor(clip.left, clip.top, clip.right, clip.bottom)
        try {
            repeat(state.height) { y ->
                repeat(state.width) cellLoop@{ x ->
                    val cell = cell(state, x, y)
                    if (cell.codePoint == ' '.code) return@cellLoop
                    val glyph =
                        Component
                            .literal(String(Character.toChars(fontProfile.renderCodePoint(cell.codePoint))))
                            .withStyle { style ->
                                style
                                    .withFont(fontProfile.fontDescription)
                                    .withColor(TerminalRenderGeometry.paletteColor(cell.foreground))
                            }
                    val bounds = geometry.cell(x, y)
                    graphics.drawString(
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
        if (TerminalRenderGeometry.drawCursor(state.cursorVisible, nowMillis)) {
            val cursor = geometry.cursor(state.cursor)
            graphics.fill(cursor.left, cursor.top, cursor.right, cursor.bottom, 0xFFFFFFFF.toInt())
        }
    }

    private fun cell(
        state: TerminalState,
        x: Int,
        y: Int,
    ): TerminalCell = state.cells[y * state.width + x]
}
