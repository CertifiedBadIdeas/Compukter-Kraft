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

class TerminalReplica(
    initial: TerminalState,
) {
    var state: TerminalState = validatedCopy(initial)
        private set

    fun replace(state: TerminalState): Boolean {
        val replacement = runCatching { validatedCopy(state) }.getOrNull() ?: return false
        this.state = replacement
        return true
    }

    fun apply(delta: TerminalUpdate.Delta): Boolean {
        if (delta.baseRevision != state.revision) return false
        val next = runCatching { applyAtomically(state, delta) }.getOrNull() ?: return false
        state = next
        return true
    }

    private fun applyAtomically(
        current: TerminalState,
        delta: TerminalUpdate.Delta,
    ): TerminalState {
        TerminalProtocol.validateDelta(delta)
        val cells = current.cells.toMutableList()
        var cursor = current.cursor
        var cursorVisible = current.cursorVisible
        delta.changes.forEach { change ->
            when (change) {
                is TerminalChange.Patch -> {
                    change.cells.forEachIndexed { offset, cell -> cells[change.start + offset] = cell }
                }

                is TerminalChange.Fill -> {
                    repeat(change.height) { y ->
                        repeat(change.width) { x ->
                            cells[(change.y + y) * TerminalProtocol.WIDTH + change.x + x] = change.cell
                        }
                    }
                }

                is TerminalChange.Scroll -> {
                    val shift = change.rows * TerminalProtocol.WIDTH
                    repeat(TerminalProtocol.CELL_COUNT - shift) { index -> cells[index] = cells[index + shift] }
                    for (index in TerminalProtocol.CELL_COUNT - shift until TerminalProtocol.CELL_COUNT) {
                        cells[index] = change.fill
                    }
                }

                is TerminalChange.Cursor -> {
                    cursor = change.position
                    cursorVisible = change.visible
                }

                TerminalChange.Reset -> {
                    cells.fill(DEFAULT_CELL)
                    cursor = TerminalPosition(0, 0)
                    cursorVisible = true
                }
            }
        }
        return current
            .copy(
                revision = delta.targetRevision,
                cells = cells.toList(),
                cursor = cursor,
                cursorVisible = cursorVisible,
            ).also(TerminalProtocol::validateState)
    }

    private companion object {
        val DEFAULT_CELL = TerminalCell(' '.code, 15, 0)

        fun validatedCopy(state: TerminalState): TerminalState {
            TerminalProtocol.validateState(state)
            return state.copy(cells = state.cells.toList())
        }
    }
}
