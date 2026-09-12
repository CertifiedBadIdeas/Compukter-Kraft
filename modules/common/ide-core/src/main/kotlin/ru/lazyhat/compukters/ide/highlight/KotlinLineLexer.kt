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

object KotlinLineLexer {
    fun scan(
        document: EditorDocument,
        lineIndex: Int,
        startState: KotlinLexicalState,
    ): KotlinLexicalLine {
        val line = document.line(lineIndex)
        val length = line.contentEndUtf16 - line.startUtf16
        val input = LineInput(length) { offset -> document.charAt(line.startUtf16 + offset) }
        return Scanner(input, startState).scan()
    }

    private class Scanner(
        private val input: LineInput,
        private val startState: KotlinLexicalState,
    ) {
        private val spans = mutableListOf<LexicalSpan>()
        private var offset = 0
        private var state = startState

        fun scan(): KotlinLexicalLine {
            if (state.blockCommentDepth > 0) scanBlockComment(state.blockCommentDepth)
            if (offset < input.length && state.inMultilineString) scanMultilineString()
            while (offset < input.length) scanToken()
            return KotlinLexicalLine(input.length, input.fingerprint(), startState, state, spans.toList())
        }

        private fun scanToken() {
            val char = input[offset]
            when {
                char.isWhitespace() -> offset++
                input.matches(offset, "//") -> add(offset, input.length, KotlinLexicalKind.LineComment).also { offset = input.length }
                input.matches(offset, "/*") -> scanBlockComment(0)
                input.matches(offset, "\"\"\"") -> scanMultilineString()
                char == '"' -> scanQuoted('"', KotlinLexicalKind.String)
                char == '\'' -> scanQuoted('\'', KotlinLexicalKind.Character)
                char == '`' -> scanBacktickedIdentifier()
                char == '@' -> add(offset, ++offset, KotlinLexicalKind.Annotation)
                isIdentifierStart(offset) -> scanIdentifier()
                char.isDigit() -> scanNumber()
                else -> add(offset, ++offset, KotlinLexicalKind.Operator)
            }
        }

        private fun scanBlockComment(initialDepth: Int) {
            val start = offset
            var depth = initialDepth
            if (depth == 0) {
                depth = 1
                offset += 2
            }
            while (offset < input.length && depth > 0) {
                when {
                    input.matches(offset, "/*") -> {
                        depth++
                        offset += 2
                    }

                    input.matches(offset, "*/") -> {
                        depth--
                        offset += 2
                    }

                    else -> {
                        offset++
                    }
                }
            }
            add(start, offset, KotlinLexicalKind.BlockComment)
            state = KotlinLexicalState(blockCommentDepth = depth)
        }

        private fun scanMultilineString() {
            val start = offset
            if (!state.inMultilineString) offset += 3
            while (offset < input.length && !input.matches(offset, "\"\"\"")) offset++
            if (offset < input.length) {
                offset += 3
                state = KotlinLexicalState()
            } else {
                state = KotlinLexicalState(inMultilineString = true)
            }
            add(start, offset, KotlinLexicalKind.MultilineString)
        }

        private fun scanQuoted(
            quote: Char,
            kind: KotlinLexicalKind,
        ) {
            var segmentStart = offset++
            while (offset < input.length) {
                when (input[offset]) {
                    '\\' -> {
                        add(segmentStart, offset, kind)
                        val escapeStart = offset++
                        if (offset < input.length) offset++
                        add(escapeStart, offset, KotlinLexicalKind.Escape)
                        segmentStart = offset
                    }

                    quote -> {
                        offset++
                        add(segmentStart, offset, kind)
                        return
                    }

                    '$' -> {
                        if (kind == KotlinLexicalKind.String && scanStringTemplate(segmentStart)) {
                            segmentStart = offset
                        } else {
                            offset++
                        }
                    }

                    else -> {
                        offset++
                    }
                }
            }
            add(segmentStart, offset, kind)
        }

        private fun scanStringTemplate(segmentStart: Int): Boolean {
            if (input[offset] != '$') return false
            if (input.matches(offset, "${'$'}{")) {
                add(segmentStart, offset, KotlinLexicalKind.String)
                val opening = offset
                offset += 2
                add(opening, offset, KotlinLexicalKind.Escape)
                scanTemplateExpression()
                return true
            }
            val identifierStart = offset + 1
            if (identifierStart >= input.length || !isIdentifierStart(identifierStart)) return false
            add(segmentStart, offset, KotlinLexicalKind.String)
            add(offset, identifierStart, KotlinLexicalKind.Escape)
            offset = identifierStart
            scanIdentifier()
            return true
        }

        private fun scanTemplateExpression() {
            var braceDepth = 1
            while (offset < input.length && braceDepth > 0) {
                when (input[offset]) {
                    '{' -> {
                        add(offset, ++offset, KotlinLexicalKind.Operator)
                        braceDepth++
                    }

                    '}' -> {
                        add(
                            offset,
                            ++offset,
                            if (braceDepth == 1) KotlinLexicalKind.Escape else KotlinLexicalKind.Operator,
                        )
                        braceDepth--
                    }

                    else -> {
                        scanToken()
                    }
                }
            }
        }

        private fun scanBacktickedIdentifier() {
            val start = offset++
            while (offset < input.length && input[offset] != '`') offset++
            if (offset < input.length) offset++
            add(start, offset, KotlinLexicalKind.Identifier)
        }

        private fun scanIdentifier() {
            val start = offset
            offset += input.scalarWidth(offset)
            while (offset < input.length && isIdentifierPart(offset)) offset += input.scalarWidth(offset)
            val text = input.string(start, offset)
            val kind =
                when {
                    text in KEYWORDS -> KotlinLexicalKind.Keyword
                    Character.isUpperCase(input.codePointAt(start)) -> KotlinLexicalKind.TypeLike
                    else -> KotlinLexicalKind.Identifier
                }
            add(start, offset, kind)
        }

        private fun scanNumber() {
            val start = offset++
            var exponent = false
            while (offset < input.length) {
                val char = input[offset]
                when {
                    char.isLetterOrDigit() || char == '_' || char == '.' -> {
                        exponent = char == 'e' || char == 'E' || char == 'p' || char == 'P'
                        offset++
                    }

                    exponent && (char == '+' || char == '-') -> {
                        exponent = false
                        offset++
                    }

                    else -> {
                        break
                    }
                }
            }
            add(start, offset, KotlinLexicalKind.Number)
        }

        private fun isIdentifierStart(at: Int): Boolean = Character.isJavaIdentifierStart(input.codePointAt(at))

        private fun isIdentifierPart(at: Int): Boolean = Character.isJavaIdentifierPart(input.codePointAt(at))

        private fun add(
            start: Int,
            end: Int,
            kind: KotlinLexicalKind,
        ) {
            if (start < end) spans += LexicalSpan(start, end, kind)
        }
    }

    private class LineInput(
        val length: Int,
        private val charAt: (Int) -> Char,
    ) {
        operator fun get(index: Int): Char = charAt(index)

        fun matches(
            offset: Int,
            value: String,
        ): Boolean {
            if (offset + value.length > length) return false
            return value.indices.all { value[it] == get(offset + it) }
        }

        fun codePointAt(offset: Int): Int {
            val first = get(offset)
            return if (Character.isHighSurrogate(first) && offset + 1 < length && Character.isLowSurrogate(get(offset + 1))) {
                Character.toCodePoint(first, get(offset + 1))
            } else {
                first.code
            }
        }

        fun scalarWidth(offset: Int): Int = Character.charCount(codePointAt(offset))

        fun string(
            start: Int,
            end: Int,
        ): String = CharArray(end - start) { get(start + it) }.concatToString()

        fun fingerprint(): Long {
            var hash = -3750763034362895579L
            repeat(length) { index ->
                hash = hash xor get(index).code.toLong()
                hash *= 1099511628211L
            }
            return hash
        }
    }

    private val KEYWORDS =
        setOf(
            "as",
            "break",
            "class",
            "continue",
            "do",
            "else",
            "false",
            "for",
            "fun",
            "if",
            "in",
            "interface",
            "is",
            "null",
            "object",
            "package",
            "return",
            "super",
            "this",
            "throw",
            "true",
            "try",
            "typealias",
            "typeof",
            "val",
            "var",
            "when",
            "while",
            "by",
            "catch",
            "constructor",
            "delegate",
            "dynamic",
            "field",
            "file",
            "finally",
            "get",
            "import",
            "init",
            "param",
            "property",
            "receiver",
            "set",
            "setparam",
            "where",
            "actual",
            "abstract",
            "annotation",
            "companion",
            "const",
            "crossinline",
            "data",
            "enum",
            "expect",
            "external",
            "final",
            "infix",
            "inline",
            "inner",
            "internal",
            "lateinit",
            "noinline",
            "open",
            "operator",
            "out",
            "override",
            "private",
            "protected",
            "public",
            "reified",
            "sealed",
            "suspend",
            "tailrec",
            "vararg",
        )
}
