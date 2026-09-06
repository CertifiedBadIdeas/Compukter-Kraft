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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinLineLexerTest {
    @Test
    fun `classifies the immediate lexical surface with non-overlapping spans`() {
        val source = "@Ann fun Main(value: Int) = 1.5e+2f + \"a\\nb\" + 'x' // tail"
        val line = scan(source)

        assertEquals(
            setOf(
                KotlinLexicalKind.Annotation,
                KotlinLexicalKind.TypeLike,
                KotlinLexicalKind.Keyword,
                KotlinLexicalKind.Identifier,
                KotlinLexicalKind.Number,
                KotlinLexicalKind.String,
                KotlinLexicalKind.Escape,
                KotlinLexicalKind.Character,
                KotlinLexicalKind.LineComment,
                KotlinLexicalKind.Operator,
            ),
            line.spans.mapTo(linkedSetOf()) { it.kind },
        )
        line.spans.zipWithNext().forEach { (left, right) -> assertTrue(left.endUtf16 <= right.startUtf16) }
        assertEquals(KotlinLexicalState(), line.endState)
    }

    @Test
    fun `tracks nested block comments between lines and resumes Kotlin`() {
        val document = EditorDocument("val x = /* outer /* nested\nstill */ end */ val y = 1")
        val first = KotlinLineLexer.scan(document, 0, KotlinLexicalState())
        val second = KotlinLineLexer.scan(document, 1, first.endState)

        assertEquals(2, first.endState.blockCommentDepth)
        assertEquals(KotlinLexicalState(), second.endState)
        assertEquals(KotlinLexicalKind.BlockComment, first.spans.last().kind)
        assertEquals(KotlinLexicalKind.BlockComment, second.spans.first().kind)
        assertTrue(second.spans.any { it.kind == KotlinLexicalKind.Keyword })
        assertTrue(second.spans.any { it.kind == KotlinLexicalKind.Number })
    }

    @Test
    fun `tracks triple strings while ordinary malformed literals stop at the line`() {
        val multiline = EditorDocument("val s = \"\"\"hello\nworld\"\"\" + 1")
        val first = KotlinLineLexer.scan(multiline, 0, KotlinLexicalState())
        val second = KotlinLineLexer.scan(multiline, 1, first.endState)
        assertTrue(first.endState.inMultilineString)
        assertFalse(second.endState.inMultilineString)
        assertEquals(KotlinLexicalKind.MultilineString, first.spans.last().kind)
        assertEquals(KotlinLexicalKind.MultilineString, second.spans.first().kind)

        val malformed = scan("val a = \"unterminated")
        assertEquals(KotlinLexicalState(), malformed.endState)
        assertEquals(KotlinLexicalKind.String, malformed.spans.last().kind)
    }

    @Test
    fun `lexes Kotlin expressions inside quoted string templates`() {
        val source = """println("${'$'}{values.size}: ${'$'}{values[1]}")"""
        val line = scan(source)

        assertEquals(
            listOf(
                "println" to KotlinLexicalKind.Identifier,
                "(" to KotlinLexicalKind.Operator,
                "\"" to KotlinLexicalKind.String,
                "${'$'}{" to KotlinLexicalKind.Operator,
                "values" to KotlinLexicalKind.Identifier,
                "." to KotlinLexicalKind.Operator,
                "size" to KotlinLexicalKind.Identifier,
                "}" to KotlinLexicalKind.Operator,
                ": " to KotlinLexicalKind.String,
                "${'$'}{" to KotlinLexicalKind.Operator,
                "values" to KotlinLexicalKind.Identifier,
                "[" to KotlinLexicalKind.Operator,
                "1" to KotlinLexicalKind.Number,
                "]" to KotlinLexicalKind.Operator,
                "}" to KotlinLexicalKind.Operator,
                "\"" to KotlinLexicalKind.String,
                ")" to KotlinLexicalKind.Operator,
            ),
            line.spans.map { source.substring(it.startUtf16, it.endUtf16) to it.kind },
        )
    }

    @Test
    fun `lexes short identifiers inside quoted string templates`() {
        val source = "\"${'$'}name\""

        assertEquals(
            listOf(
                "\"" to KotlinLexicalKind.String,
                "${'$'}" to KotlinLexicalKind.Operator,
                "name" to KotlinLexicalKind.Identifier,
                "\"" to KotlinLexicalKind.String,
            ),
            scan(source).spans.map { source.substring(it.startUtf16, it.endUtf16) to it.kind },
        )
    }

    @Test
    fun `excludes line separators and respects keyword and identifier boundaries`() {
        val document = EditorDocument("whenish `fun` when\r\nnext")
        val first = KotlinLineLexer.scan(document, 0, KotlinLexicalState())

        assertEquals(18, first.sourceLengthUtf16)
        assertEquals(
            listOf(KotlinLexicalKind.Identifier, KotlinLexicalKind.Identifier, KotlinLexicalKind.Keyword),
            first.spans.map { it.kind },
        )
        assertTrue(first.spans.all { it.endUtf16 <= first.sourceLengthUtf16 })
    }

    private fun scan(source: String): KotlinLexicalLine = KotlinLineLexer.scan(EditorDocument(source), 0, KotlinLexicalState())
}
