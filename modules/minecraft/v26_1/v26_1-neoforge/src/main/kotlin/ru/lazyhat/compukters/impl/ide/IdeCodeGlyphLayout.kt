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

internal data class IdeCodeGlyph(
    val value: String,
    val x: Int,
)

internal object IdeCodeGlyphLayout {
    fun layout(
        value: String,
        startX: Int,
        font: TerminalFontProfile,
    ): List<IdeCodeGlyph> {
        val glyphs = ArrayList<IdeCodeGlyph>(value.length)
        var offset = 0
        var column = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (codePoint != ' '.code) {
                val rendered = font.renderCodePoint(codePoint)
                glyphs += IdeCodeGlyph(String(Character.toChars(rendered)), startX + column * font.cellWidth)
            }
            offset += Character.charCount(codePoint)
            column++
        }
        return glyphs
    }
}
