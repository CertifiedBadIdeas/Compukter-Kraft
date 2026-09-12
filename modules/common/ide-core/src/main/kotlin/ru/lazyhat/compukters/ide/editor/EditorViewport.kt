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

class EditorViewport(
    rows: Int,
    columns: Int,
) {
    var rows: Int = requirePositive(rows, "viewport rows")
        private set

    var columns: Int = requirePositive(columns, "viewport columns")
        private set

    var firstLine: Int = 0
        private set

    var firstVisualColumn: Int = 0
        private set

    fun resize(
        document: EditorDocument,
        rows: Int,
        columns: Int,
    ) {
        this.rows = requirePositive(rows, "viewport rows")
        this.columns = requirePositive(columns, "viewport columns")
        clampOrigins(document)
    }

    fun scrollLines(
        document: EditorDocument,
        delta: Int,
    ) {
        firstLine = saturatedAdd(firstLine, delta).coerceIn(0, maxFirstLine(document))
    }

    fun scrollColumns(
        document: EditorDocument,
        delta: Int,
    ) {
        firstVisualColumn = saturatedAdd(firstVisualColumn, delta).coerceIn(0, maxFirstVisualColumn(document))
    }

    fun revealCaret(document: EditorDocument) {
        val caretLine = document.lineOfOffset(document.caretOffset)
        firstLine =
            when {
                caretLine < firstLine -> caretLine
                caretLine >= firstLine + rows -> caretLine - rows + 1
                else -> firstLine
            }

        val caretColumn = document.caretVisualColumn
        firstVisualColumn =
            when {
                caretColumn < firstVisualColumn -> caretColumn
                caretColumn >= firstVisualColumn + columns -> caretColumn - columns + 1
                else -> firstVisualColumn
            }
        clampOrigins(document)
    }

    fun placeCaret(
        document: EditorDocument,
        row: Int,
        column: Int,
        extendSelection: Boolean = false,
    ): Boolean {
        if (row !in 0 until rows || column !in 0 until columns) return false
        val line = firstLine + row
        if (line !in 0 until document.lineCount) return false
        val target = document.offsetAtVisualColumn(line, firstVisualColumn + column)
        return document.setCaret(target, extendSelection)
    }

    fun pageUp(
        document: EditorDocument,
        extendSelection: Boolean = false,
    ): Boolean = page(document, -rows, extendSelection)

    fun pageDown(
        document: EditorDocument,
        extendSelection: Boolean = false,
    ): Boolean = page(document, rows, extendSelection)

    fun visibleLines(document: EditorDocument): IntRange {
        clampOrigins(document)
        return firstLine..minOf(document.lineCount - 1, firstLine + rows - 1)
    }

    fun visibleColumns(): IntRange = firstVisualColumn..firstVisualColumn + columns - 1

    private fun page(
        document: EditorDocument,
        deltaLines: Int,
        extendSelection: Boolean,
    ): Boolean {
        val currentLine = document.lineOfOffset(document.caretOffset)
        val targetLine = saturatedAdd(currentLine, deltaLines).coerceIn(0, document.lineCount - 1)
        if (targetLine == currentLine) return false
        val target = document.offsetAtVisualColumn(targetLine, document.caretVisualColumn)
        check(document.setCaret(target, extendSelection))
        revealCaret(document)
        return true
    }

    private fun clampOrigins(document: EditorDocument) {
        firstLine = firstLine.coerceAtMost(maxFirstLine(document))
        firstVisualColumn = firstVisualColumn.coerceAtMost(maxFirstVisualColumn(document))
    }

    private fun maxFirstLine(document: EditorDocument): Int = (document.lineCount - rows).coerceAtLeast(0)

    private fun maxFirstVisualColumn(document: EditorDocument): Int {
        var maximum = 0
        repeat(document.lineCount) { line -> maximum = maxOf(maximum, document.lineVisualWidth(line)) }
        return (maximum - columns).coerceAtLeast(0)
    }

    private companion object {
        fun requirePositive(
            value: Int,
            name: String,
        ): Int {
            require(value > 0) { "$name must be positive" }
            return value
        }

        fun saturatedAdd(
            value: Int,
            delta: Int,
        ): Int = (value.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
