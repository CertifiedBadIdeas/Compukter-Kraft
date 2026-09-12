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

import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate

object TerminalModel {
    const val WIDTH = 51
    const val HEIGHT = 19
    const val CELL_COUNT = WIDTH * HEIGHT
    const val MAXIMUM_TEXT_CODE_UNITS = 4_096
    const val MAXIMUM_CHANGES = 4_096
    const val MAXIMUM_ENCODED_DELTA_CELLS = 8_192
    private const val PALETTE_SIZE = 16

    fun validateState(state: TerminalState) {
        require(state.revision >= 0) { "terminal revision must not be negative" }
        require(state.width == WIDTH && state.height == HEIGHT) { "unsupported terminal dimensions" }
        require(state.cells.size == CELL_COUNT) { "invalid terminal cell count" }
        state.cells.forEach(::validateCell)
        validatePosition(state.cursor)
    }

    fun validateDelta(delta: TerminalUpdate.Delta) {
        require(delta.baseRevision >= 0 && delta.targetRevision > delta.baseRevision) { "invalid terminal delta revisions" }
        require(delta.changes.size <= MAXIMUM_CHANGES) { "too many terminal changes" }
        delta.changes.forEach(::validateChange)
        val encodedCells =
            delta.changes.sumOf { change ->
                when (change) {
                    is TerminalChange.Patch -> change.cells.size
                    is TerminalChange.Fill, is TerminalChange.Scroll -> 1
                    is TerminalChange.Cursor, TerminalChange.Reset -> 0
                }
            }
        require(encodedCells <= MAXIMUM_ENCODED_DELTA_CELLS) { "terminal delta cell payload is too large" }
    }

    fun validateChange(change: TerminalChange) {
        when (change) {
            is TerminalChange.Patch -> {
                require(change.cells.isNotEmpty() && change.start in 0 until CELL_COUNT) { "invalid terminal patch" }
                require(change.cells.size <= CELL_COUNT - change.start) { "invalid terminal patch" }
                change.cells.forEach(::validateCell)
            }

            is TerminalChange.Fill -> {
                require(change.width > 0 && change.height > 0) { "invalid terminal fill" }
                require(change.x in 0 until WIDTH && change.y in 0 until HEIGHT) { "invalid terminal fill" }
                require(change.width <= WIDTH - change.x && change.height <= HEIGHT - change.y) { "invalid terminal fill" }
                validateCell(change.cell)
            }

            is TerminalChange.Scroll -> {
                require(change.rows in 1..HEIGHT) { "invalid terminal scroll" }
                validateCell(change.fill)
            }

            is TerminalChange.Cursor -> {
                validatePosition(change.position)
            }

            TerminalChange.Reset -> {
                Unit
            }
        }
    }

    fun validateCell(cell: TerminalCell) {
        require(isScalar(cell.codePoint)) { "invalid terminal Unicode scalar" }
        require(cell.foreground in 0 until PALETTE_SIZE && cell.background in 0 until PALETTE_SIZE) {
            "invalid terminal palette index"
        }
    }

    fun validatePosition(position: TerminalPosition) {
        require(position.x in 0 until WIDTH && position.y in 0 until HEIGHT) { "invalid terminal position" }
    }

    fun requireAtomicText(text: String) {
        require(text.length <= MAXIMUM_TEXT_CODE_UNITS) { "terminal text is too long" }
        val scalars = text.codePoints().toArray()
        require(scalars.size <= MAXIMUM_TEXT_CODE_UNITS) { "terminal text has too many scalars" }
        require(scalars.all(::isScalar)) { "terminal text contains an invalid Unicode scalar" }
    }

    private fun isScalar(value: Int): Boolean =
        Character.isValidCodePoint(value) && value !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
}
