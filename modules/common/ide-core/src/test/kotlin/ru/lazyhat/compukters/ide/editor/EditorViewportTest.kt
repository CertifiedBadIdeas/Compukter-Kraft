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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorViewportTest {
    @Test
    fun `dimensions are positive and scrolling is independently clamped`() {
        assertFailsWith<IllegalArgumentException> { EditorViewport(0, 4) }
        assertFailsWith<IllegalArgumentException> { EditorViewport(4, 0) }

        val editor = EditorDocument("short\n0123456789\nlast")
        val viewport = EditorViewport(rows = 2, columns = 4)

        viewport.scrollLines(editor, 99)
        assertEquals(1, viewport.firstLine)
        assertEquals(0, viewport.firstVisualColumn)

        viewport.scrollColumns(editor, 99)
        assertEquals(1, viewport.firstLine)
        assertEquals(6, viewport.firstVisualColumn)

        viewport.scrollLines(editor, -99)
        viewport.scrollColumns(editor, -99)
        assertEquals(0, viewport.firstLine)
        assertEquals(0, viewport.firstVisualColumn)
        assertEquals(0..1, viewport.visibleLines(editor))
        assertEquals(0..3, viewport.visibleColumns())
    }

    @Test
    fun `resizing and revealing the caret move only the required axes`() {
        val editor = EditorDocument("zero\none\ntwo\n0123456789")
        val viewport = EditorViewport(rows = 2, columns = 4)

        assertTrue(editor.setCaret(editor.length))
        viewport.revealCaret(editor)
        assertEquals(2, viewport.firstLine)
        assertEquals(6, viewport.firstVisualColumn)

        viewport.resize(editor, rows = 4, columns = 10)
        assertEquals(0, viewport.firstLine)
        assertEquals(0, viewport.firstVisualColumn)
        assertEquals(0L, editor.revision)
        assertEquals(0, editor.undoEntryCount)
    }

    @Test
    fun `mouse mapping understands tabs scalars and clips after line end`() {
        val editor = EditorDocument("\tA😀Z\nx")
        val viewport = EditorViewport(rows = 2, columns = 8)

        assertTrue(viewport.placeCaret(editor, row = 0, column = 4))
        assertEquals(1, editor.caretOffset)
        assertTrue(viewport.placeCaret(editor, row = 0, column = 6))
        assertEquals(4, editor.caretOffset)
        assertTrue(viewport.placeCaret(editor, row = 1, column = 7))
        assertEquals(editor.length, editor.caretOffset)
        assertFalse(viewport.placeCaret(editor, row = -1, column = 0))
        assertFalse(viewport.placeCaret(editor, row = 2, column = 0))
    }

    @Test
    fun `page navigation preserves visual column and supports selection`() {
        val editor = EditorDocument("0000\n1\n2222\n3333\n4444")
        val viewport = EditorViewport(rows = 2, columns = 3)

        assertTrue(editor.setCaret(3))
        assertTrue(viewport.pageDown(editor))
        assertEquals(10, editor.caretOffset)
        assertEquals(1, viewport.firstLine)
        assertTrue(viewport.pageDown(editor, extendSelection = true))
        assertEquals(EditorRange(10, 20), editor.selectionRange)
        assertTrue(viewport.pageUp(editor))
        assertEquals(10, editor.caretOffset)
        assertEquals(0L, editor.revision)
        assertEquals(0, editor.undoEntryCount)
    }
}
