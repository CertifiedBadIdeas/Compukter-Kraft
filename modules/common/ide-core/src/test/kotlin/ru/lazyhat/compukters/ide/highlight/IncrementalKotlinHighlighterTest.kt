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

package ru.lazyhat.compukters.ide.highlight

import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorEditResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IncrementalKotlinHighlighterTest {
    @Test
    fun `edits propagate lexical state and remain identical to a full scan`() {
        val editor = EditorDocument("val a = 1\nval b = 2\nval c = 3\nval d = 4")
        val highlighter = IncrementalKotlinHighlighter(editor)
        assertCurrent(editor, highlighter)

        replace(editor, 10, 10, "\"\"\"")
        assertCurrent(editor, highlighter)
        replace(editor, 25, 25, "\"\"\"")
        assertCurrent(editor, highlighter)
        replace(editor, 0, 0, "/* outer /* nested */\n")
        assertCurrent(editor, highlighter)
        replace(editor, editor.length, editor.length, "\n*/ // done")
        assertCurrent(editor, highlighter)
        replace(editor, 5, 6, "\r\n")
        assertCurrent(editor, highlighter)
    }

    @Test
    fun `a local stable-state edit reuses the unchanged suffix`() {
        val editor = EditorDocument("val a = 1\nval b = 2\nval c = 3\nval d = 4")
        val highlighter = IncrementalKotlinHighlighter(editor)

        replace(editor, 15, 16, "renamed")

        assertCurrent(editor, highlighter)
        assertTrue(highlighter.lastReusedSuffixLines >= 2)
    }

    @Test
    fun `seeded random edits always equal the full-scan oracle`() {
        val random = Random(0xC0FFEE)
        val editor = EditorDocument("fun main() {\n    println(\"hello\")\n}\n")
        val highlighter = IncrementalKotlinHighlighter(editor)
        val fragments = listOf("a", " ", "\n", "\r\n", "/*", "*/", "//", "\"", "\"\"\"", "1e+2", "😀")

        repeat(1_000) {
            if (editor.length == 0 || random.nextBoolean()) {
                val offset = random.nextInt(editor.length + 1)
                if (editor.setCaret(offset)) assertIs<EditorEditResult.Applied>(editor.type(fragments.random(random)))
            } else {
                val offset = random.nextInt(editor.length + 1)
                if (editor.setCaret(offset)) editor.backspace()
            }
            assertCurrent(editor, highlighter)
        }
    }

    private fun assertCurrent(
        editor: EditorDocument,
        highlighter: IncrementalKotlinHighlighter,
    ) {
        val snapshot = highlighter.snapshot()
        assertEquals(editor.revision, snapshot.revision)
        assertEquals(IncrementalKotlinHighlighter.fullScan(editor), snapshot.lines)
    }

    private fun replace(
        editor: EditorDocument,
        start: Int,
        end: Int,
        text: String,
    ) {
        assertTrue(editor.setCaret(start))
        repeat(end - start) { editor.moveRight(extendSelection = true) }
        assertIs<EditorEditResult.Applied>(editor.type(text))
    }
}
