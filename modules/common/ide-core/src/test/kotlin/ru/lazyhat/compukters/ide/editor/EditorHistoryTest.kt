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

package ru.lazyhat.compukters.ide.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EditorHistoryTest {
    @Test
    fun `sequential typing and backspace coalesce into useful undo groups`() {
        val editor = editor(undoEntries = 8, undoUnits = 32)
        editor.type("a")
        editor.type("b")
        editor.type("c")
        assertEquals(1, editor.undoEntryCount)
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals("", editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.redo())
        assertEquals("abc", editor.materialize())

        editor.backspace()
        editor.backspace()
        assertEquals("a", editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals("abc", editor.materialize())
    }

    @Test
    fun `navigation closes typing group and new input clears redo`() {
        val editor = editor(undoEntries = 8, undoUnits = 32)
        editor.type("ab")
        editor.moveLeft()
        editor.moveRight()
        editor.type("c")
        assertEquals(2, editor.undoEntryCount)
        editor.undo()
        assertEquals("ab", editor.materialize())
        editor.type("x")
        assertEquals(EditorEditResult.NoChange, editor.redo())
        assertEquals("abx", editor.materialize())
    }

    @Test
    fun `atomic edits round trip Unicode CRLF selection and indentation`() {
        val editor = EditorDocument("😀\r\n    x", limits(8, 64))
        editor.selectAll()
        editor.paste("a\r\nb")
        editor.setCaret(editor.length)
        editor.enter()
        assertEquals("a\r\nb\r\n", editor.materialize())

        editor.undo()
        assertEquals("a\r\nb", editor.materialize())
        editor.undo()
        assertEquals("😀\r\n    x", editor.materialize())
        editor.redo()
        editor.redo()
        assertEquals("a\r\nb\r\n", editor.materialize())
    }

    @Test
    fun `history budgets reject oversized entry and evict oldest complete entries`() {
        val bounded = editor(undoEntries = 2, undoUnits = 2)
        assertEquals(
            EditorEditResult.Rejected(EditorRejection.UndoLimit),
            bounded.paste("abc"),
        )
        assertEquals("", bounded.materialize())

        bounded.paste("a")
        bounded.breakUndoGroup()
        bounded.paste("b")
        bounded.breakUndoGroup()
        bounded.paste("c")
        assertEquals(2, bounded.undoEntryCount)
        bounded.undo()
        bounded.undo()
        assertEquals("a", bounded.materialize())
        assertEquals(EditorEditResult.NoChange, bounded.undo())
    }

    private fun editor(
        undoEntries: Int,
        undoUnits: Int,
    ) = EditorDocument("", limits(undoEntries, undoUnits))

    private fun limits(
        undoEntries: Int,
        undoUnits: Int,
    ) = EditorLimits(
        maxCodeUnits = 128,
        maxUtf8Bytes = 256,
        initialGapCodeUnits = 2,
        maxUndoEntries = undoEntries,
        maxUndoCodeUnits = undoUnits,
        tabWidth = 4,
    )
}
