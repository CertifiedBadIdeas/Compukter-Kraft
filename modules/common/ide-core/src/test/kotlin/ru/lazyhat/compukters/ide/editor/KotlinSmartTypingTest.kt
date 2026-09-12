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

import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinSmartTypingTest {
    @Test
    fun `pairs wrap and tracked closers remain distinct from ordinary source`() {
        fixture("call") { document, typing ->
            assertTrue(document.setCaret(document.length))
            assertIs<EditorEditResult.Applied>(typing.type("("))
            assertEquals("call()", document.materialize())
            assertEquals(5, document.caretOffset)

            assertIs<EditorEditResult.Applied>(typing.type("😀"))
            assertEquals(EditorEditResult.NoChange, typing.type(")"))
            assertEquals("call(😀)", document.materialize())
            assertEquals(document.length, document.caretOffset)
        }

        fixture(")") { document, typing ->
            assertIs<EditorEditResult.Applied>(typing.type(")"))
            assertEquals("))", document.materialize())
        }

        fixture("привет😀") { document, typing ->
            assertTrue(document.setCaret(0))
            assertTrue(document.setCaret(document.length, extendSelection = true))
            assertIs<EditorEditResult.Applied>(typing.type("["))
            assertEquals("[привет😀]", document.materialize())
            assertEquals("привет😀", document.copySelection())
        }
    }

    @Test
    fun `paired backspace and undo are atomic`() {
        fixture("") { document, typing ->
            assertIs<EditorEditResult.Applied>(typing.type("{"))
            assertEquals("{}", document.materialize())
            assertEquals(1, document.undoEntryCount)

            assertIs<EditorEditResult.Applied>(typing.backspace())
            assertEquals("", document.materialize())
            assertEquals(2, document.undoEntryCount)
            assertIs<EditorEditResult.Applied>(document.undo())
            assertEquals("{}", document.materialize())
            assertIs<EditorEditResult.Applied>(document.undo())
            assertEquals("", document.materialize())
        }
    }

    @Test
    fun `pairing is suppressed inside strings and comments`() {
        fixture("val text = \"value\"") { document, typing ->
            assertTrue(document.setCaret(document.materialize().indexOf("value") + 2))
            assertIs<EditorEditResult.Applied>(typing.type("("))
            assertEquals("val text = \"va(lue\"", document.materialize())
        }

        fixture("// note") { document, typing ->
            assertTrue(document.setCaret(document.length))
            assertIs<EditorEditResult.Applied>(typing.type("["))
            assertEquals("// note[", document.materialize())
        }
    }

    @Test
    fun `structural enter preserves CRLF and splits an automatic brace pair`() {
        fixture("fun main() {\r\n    ") { document, typing ->
            assertTrue(document.setCaret(document.length))
            assertIs<EditorEditResult.Applied>(typing.type("{"))
            assertIs<EditorEditResult.Applied>(typing.enter())

            assertEquals("fun main() {\r\n    {\r\n        \r\n    }", document.materialize())
            assertEquals("fun main() {\r\n    {\r\n        ".length, document.caretOffset)
            assertNull(document.selectionRange)
            assertIs<EditorEditResult.Applied>(document.undo())
            assertEquals("fun main() {\r\n    {}", document.materialize())
        }
    }

    @Test
    fun `rejected structural edit retains its automatic pair`() {
        val document = EditorDocument("", EditorLimits(maxCodeUnits = 2, maxUtf8Bytes = 2))
        val highlighter = IncrementalKotlinHighlighter(document)
        KotlinSmartTyping(document, highlighter).use { typing ->
            assertIs<EditorEditResult.Applied>(typing.type("{"))
            assertEquals(EditorEditResult.Rejected(EditorRejection.CodeUnitLimit), typing.enter())
            assertIs<EditorEditResult.Applied>(typing.backspace())
            assertEquals("", document.materialize())
        }
        highlighter.close()
    }

    private fun fixture(
        source: String,
        block: (EditorDocument, KotlinSmartTyping) -> Unit,
    ) {
        val document = EditorDocument(source)
        val highlighter = IncrementalKotlinHighlighter(document)
        KotlinSmartTyping(document, highlighter).use { typing -> block(document, typing) }
        highlighter.close()
    }
}
