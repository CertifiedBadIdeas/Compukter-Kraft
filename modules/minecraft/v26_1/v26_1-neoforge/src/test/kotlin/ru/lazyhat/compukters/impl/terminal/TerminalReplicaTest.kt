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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalReplicaTest {
    @Test
    fun `revision mismatch rejects delta without mutation`() {
        val replica = TerminalReplica(state(3))
        val before = replica.state

        assertFalse(replica.apply(delta(2, 4, TerminalChange.Reset)))
        assertEquals(before, replica.state)
    }

    @Test
    fun `valid replacement copies and publishes the complete state`() {
        val replica = TerminalReplica(state(3))
        val replacement = state(9).copy(cursor = TerminalPosition(4, 5), cursorVisible = false)

        assertTrue(replica.replace(replacement))
        assertEquals(replacement, replica.state)
        assertFalse(replica.state === replacement)
        assertFalse(replica.state.cells === replacement.cells)
    }

    @Test
    fun `delta applies scroll before patch in exact order`() {
        val cells = MutableList(CELL_COUNT) { cell(' ') }
        cells[0] = cell('A')
        cells[WIDTH] = cell('B')
        val replica = TerminalReplica(state(1, cells))
        val update =
            delta(
                1,
                2,
                TerminalChange.Scroll(1, cell('.')),
                TerminalChange.Patch(0, listOf(cell('X'))),
            )

        assertTrue(replica.apply(update))
        assertEquals(cell('X'), replica.state.cells[0])
        assertEquals(cell('.'), replica.state.cells[(HEIGHT - 1) * WIDTH])
        assertEquals(2, replica.state.revision)
    }

    @Test
    fun `invalid later change rejects the whole delta atomically`() {
        val replica = TerminalReplica(state(1))
        val before = replica.state
        val update =
            delta(
                1,
                2,
                TerminalChange.Patch(0, listOf(cell('X'))),
                TerminalChange.Fill(50, 18, 2, 1, cell('Y')),
            )

        assertFalse(replica.apply(update))
        assertEquals(before, replica.state)
    }

    @Test
    fun `invalid full state is rejected`() {
        val invalid = state(0).copy(cells = listOf(cell('x')))
        assertFailsWith<IllegalArgumentException> {
            TerminalReplica(invalid)
        }
    }

    private companion object {
        const val WIDTH = 51
        const val HEIGHT = 19
        const val CELL_COUNT = WIDTH * HEIGHT

        fun cell(value: Char): TerminalCell = TerminalCell(value.code, 15, 0)

        fun state(
            revision: Long,
            cells: List<TerminalCell> = List(CELL_COUNT) { cell(' ') },
        ): TerminalState = TerminalState(revision, WIDTH, HEIGHT, cells, TerminalPosition(0, 0), true)

        fun delta(
            base: Long,
            target: Long,
            vararg changes: TerminalChange,
        ): TerminalUpdate.Delta = TerminalUpdate.Delta(base, target, changes.toList())
    }
}
