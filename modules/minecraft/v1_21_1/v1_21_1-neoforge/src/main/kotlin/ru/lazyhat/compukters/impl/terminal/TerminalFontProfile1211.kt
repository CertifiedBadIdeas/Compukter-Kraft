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

import net.minecraft.resources.ResourceLocation

class TerminalFontProfile private constructor(
    val id: String,
    val displayName: String,
    val fontDescription: ResourceLocation,
    val cellWidth: Int,
    val cellHeight: Int,
    val ascent: Int,
    supportedCodePoints: IntArray,
    val replacementCodePoint: Int,
) {
    private val supportedCodePoints = supportedCodePoints.copyOf()

    val glyphDrawOffsetY: Int = ascent - MINECRAFT_TEXT_BASELINE

    init {
        require(cellWidth > 0 && cellHeight > 0) { "terminal font cell must be positive" }
        require(ascent in 1..cellHeight) { "terminal font ascent must fit the cell" }
        require(this.supportedCodePoints.contentEquals(this.supportedCodePoints.sortedArray())) {
            "terminal font coverage must be sorted"
        }
        require((1 until this.supportedCodePoints.size).none { this.supportedCodePoints[it - 1] == this.supportedCodePoints[it] }) {
            "terminal font coverage must not contain duplicates"
        }
        require(this.supportedCodePoints.binarySearch(replacementCodePoint) >= 0) {
            "terminal font coverage must contain its replacement glyph"
        }
    }

    fun supports(codePoint: Int): Boolean = supportedCodePoints.binarySearch(codePoint) >= 0

    fun renderCodePoint(codePoint: Int): Int = if (supports(codePoint)) codePoint else replacementCodePoint

    fun next(): TerminalFontProfile {
        val index = ALL.indexOfFirst { it === this }
        require(index >= 0) { "terminal font profile is not registered: $id" }
        return ALL[(index + 1) % ALL.size]
    }

    companion object {
        private const val MINECRAFT_TEXT_BASELINE = 7

        val COZETTE = terminalProfile("cozette", "Cozette", 6, 13, 10, COZETTE_SUPPORTED_CODE_POINTS, 0xFFFD)
        val DINA = terminalProfile("dina", "Dina", 6, 10, 8, DINA_SUPPORTED_CODE_POINTS, '?'.code)
        val PROGGY_TINY = terminalProfile("proggy_tiny", "ProggyTiny", 6, 10, 8, PROGGY_TINY_SUPPORTED_CODE_POINTS, '?'.code)
        val ALL = listOf(COZETTE, DINA, PROGGY_TINY)
        val DEFAULT = COZETTE

        fun fromId(id: String?): TerminalFontProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT

        private fun terminalProfile(
            id: String,
            displayName: String,
            cellWidth: Int,
            cellHeight: Int,
            ascent: Int,
            supportedCodePoints: IntArray,
            replacementCodePoint: Int,
        ) = TerminalFontProfile(
            id,
            displayName,
            ResourceLocation.fromNamespaceAndPath("compukters", "terminal/$id"),
            cellWidth,
            cellHeight,
            ascent,
            supportedCodePoints,
            replacementCodePoint,
        )
    }
}
