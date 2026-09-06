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
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind

class KotlinSmartTyping(
    private val document: EditorDocument,
    private val highlighter: IncrementalKotlinHighlighter,
) : AutoCloseable {
    private val automaticClosers = linkedMapOf<Int, Char>()
    private val subscription = document.addChangeListener(::documentChanged)
    private var closed = false

    fun type(text: String): EditorEditResult {
        if (closed || text.length != 1) return document.type(text)
        val typed = text.single()
        val caret = document.caretOffset
        if (
            document.selectionRange == null && automaticClosers[caret] == typed &&
            caret < document.length && document.charAt(caret) == typed && !isEscaped(caret)
        ) {
            automaticClosers.remove(caret)
            document.setCaret(caret + 1)
            return EditorEditResult.NoChange
        }
        val closer = PAIRS[typed] ?: return document.type(text)
        if (document.selectionRange == null && !isCodeAtCaret(caret)) return document.type(text)
        val before = document.selectionState
        val range = before.range
        val selected = document.copyRange(range)
        val inserted = "$typed$selected$closer"
        val after =
            if (range.length == 0) {
                EditorSelection(range.startUtf16 + 1, range.startUtf16 + 1)
            } else if (before.anchorUtf16 <= before.caretUtf16) {
                EditorSelection(range.startUtf16 + 1, range.endUtf16 + 1)
            } else {
                EditorSelection(range.endUtf16 + 1, range.startUtf16 + 1)
            }
        val result = document.replaceSelection(inserted, EditorHistoryKind.Atomic, after)
        if (result is EditorEditResult.Applied) automaticClosers[range.startUtf16 + inserted.length - 1] = closer
        return result
    }

    fun backspace(): EditorEditResult {
        if (closed) return document.backspace()
        val caret = document.caretOffset
        val closer = automaticClosers[caret]
        if (
            document.selectionRange == null && closer != null && caret > 0 && caret < document.length &&
            document.charAt(caret) == closer && PAIRS[document.charAt(caret - 1)] == closer
        ) {
            return document.replaceRange(EditorRange(caret - 1, caret + 1), "")
        }
        return document.backspace()
    }

    fun enter(): EditorEditResult {
        if (closed || document.selectionRange != null) return document.enter()
        val caret = document.caretOffset
        val lineIndex = document.lineOfOffset(caret)
        val line = document.line(lineIndex)
        val opener = previousNonWhitespace(line.startUtf16, caret)
        if (opener == null || document.charAt(opener) != '{' || !isCodeAt(opener)) return document.enter()
        val indent = leadingIndent(line.startUtf16, line.contentEndUtf16)
        val contentIndent = indent + " ".repeat(document.limits.tabWidth)
        val separator = document.preferredLineSeparator
        val automaticClosingBrace = automaticClosers[caret] == '}' && caret < document.length && document.charAt(caret) == '}'
        val inserted =
            if (automaticClosingBrace) {
                separator + contentIndent + separator + indent
            } else {
                separator + contentIndent
            }
        val after = EditorSelection(caret + separator.length + contentIndent.length, caret + separator.length + contentIndent.length)
        return document.replaceSelection(inserted, EditorHistoryKind.Atomic, after)
    }

    override fun close() {
        if (closed) return
        closed = true
        automaticClosers.clear()
        subscription.close()
    }

    private fun documentChanged(change: EditorChange) {
        if (change.origin != EditorChangeOrigin.User) {
            automaticClosers.clear()
            return
        }
        val delta = change.insertedCodeUnits - change.oldRange.length
        val shifted = linkedMapOf<Int, Char>()
        automaticClosers.forEach { (offset, closer) ->
            when {
                offset < change.oldRange.startUtf16 -> shifted[offset] = closer
                offset >= change.oldRange.endUtf16 -> shifted[offset + delta] = closer
            }
        }
        automaticClosers.clear()
        automaticClosers.putAll(shifted)
    }

    private fun leadingIndent(
        start: Int,
        end: Int,
    ): String {
        var offset = start
        while (offset < end && (document.charAt(offset) == ' ' || document.charAt(offset) == '\t')) offset++
        return document.copyRange(EditorRange(start, offset))
    }

    private fun previousNonWhitespace(
        start: Int,
        end: Int,
    ): Int? {
        var offset = end - 1
        while (offset >= start && document.charAt(offset).isWhitespace()) offset--
        return offset.takeIf { it >= start }
    }

    private fun isCodeAtCaret(offset: Int): Boolean = lexicalKindAt(offset)?.let { it !in NON_CODE_KINDS } ?: true

    private fun isCodeAt(offset: Int): Boolean = lexicalKindAt(offset)?.let { it !in NON_CODE_KINDS } ?: true

    private fun lexicalKindAt(offset: Int): KotlinLexicalKind? {
        if (document.length == 0) return null
        val bounded = offset.coerceAtMost(document.length - 1)
        val lineIndex = document.lineOfOffset(bounded)
        val lineStart = document.line(lineIndex).startUtf16
        val relative = offset - lineStart
        val line = highlighter.snapshot().lines[lineIndex]
        line.spans.firstOrNull { span -> relative >= span.startUtf16 && relative < span.endUtf16 }?.let { return it.kind }
        if (relative != line.sourceLengthUtf16) return null
        val trailing = line.spans.lastOrNull()?.takeIf { it.endUtf16 == relative } ?: return null
        return when (trailing.kind) {
            KotlinLexicalKind.LineComment -> trailing.kind
            KotlinLexicalKind.BlockComment -> trailing.kind.takeIf { line.endState.blockCommentDepth > 0 }
            KotlinLexicalKind.MultilineString -> trailing.kind.takeIf { line.endState.inMultilineString }
            KotlinLexicalKind.String -> trailing.kind.takeUnless { document.charAt(offset - 1) == '"' }
            KotlinLexicalKind.Character -> trailing.kind.takeUnless { document.charAt(offset - 1) == '\'' }
            else -> null
        }
    }

    private fun isEscaped(offset: Int): Boolean {
        var cursor = offset - 1
        var slashes = 0
        while (cursor >= 0 && document.charAt(cursor) == '\\') {
            slashes++
            cursor--
        }
        return slashes % 2 != 0
    }

    private companion object {
        val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
        val NON_CODE_KINDS =
            setOf(
                KotlinLexicalKind.String,
                KotlinLexicalKind.Character,
                KotlinLexicalKind.MultilineString,
                KotlinLexicalKind.LineComment,
                KotlinLexicalKind.BlockComment,
            )
    }
}
