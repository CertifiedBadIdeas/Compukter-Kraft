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

internal data class EditorLine(
    val startUtf16: Int,
    val contentEndUtf16: Int,
    val separatorEndUtf16: Int,
)

internal class EditorLineIndex(
    private val buffer: EditorBuffer,
    private val tabWidth: Int,
) {
    private val lines = mutableListOf<EditorLine>()

    val lineCount: Int
        get() = lines.size

    val preferredSeparator: String
        get() {
            lines.forEach { line ->
                when (line.separatorEndUtf16 - line.contentEndUtf16) {
                    2 -> return "\r\n"
                    1 -> return buffer.charAt(line.contentEndUtf16).toString()
                }
            }
            return "\n"
        }

    init {
        rebuild()
    }

    fun rebuild() {
        lines.clear()
        appendFrom(0)
    }

    fun rebuildFrom(lineIndex: Int) {
        require(lineIndex in lines.indices)
        val lineStart = lines[lineIndex].startUtf16
        lines.subList(lineIndex, lines.size).clear()
        appendFrom(lineStart)
    }

    private fun appendFrom(startUtf16: Int) {
        var lineStart = startUtf16
        var offset = startUtf16
        while (offset < buffer.length) {
            when (buffer.charAt(offset)) {
                '\r' -> {
                    val separatorEnd =
                        if (offset + 1 < buffer.length && buffer.charAt(offset + 1) == '\n') offset + 2 else offset + 1
                    lines += EditorLine(lineStart, offset, separatorEnd)
                    lineStart = separatorEnd
                    offset = separatorEnd
                }

                '\n' -> {
                    lines += EditorLine(lineStart, offset, offset + 1)
                    lineStart = offset + 1
                    offset++
                }

                else -> {
                    offset = buffer.nextScalarBoundary(offset)
                }
            }
        }
        lines += EditorLine(lineStart, buffer.length, buffer.length)
    }

    fun line(index: Int): EditorLine = lines[index]

    fun lineOfOffset(offset: Int): Int {
        require(offset in 0..buffer.length)
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val line = lines[middle]
            when {
                offset < line.startUtf16 -> high = middle - 1
                offset >= line.separatorEndUtf16 && middle < lines.lastIndex -> low = middle + 1
                else -> return middle
            }
        }
        return lines.lastIndex
    }

    fun visualColumn(offset: Int): Int {
        val line = lines[lineOfOffset(offset)]
        val end = minOf(offset, line.contentEndUtf16)
        var cursor = line.startUtf16
        var column = 0
        while (cursor < end) {
            val char = buffer.charAt(cursor)
            if (char == '\t') {
                column += tabWidth - column % tabWidth
                cursor++
            } else {
                column++
                cursor = buffer.nextScalarBoundary(cursor)
            }
        }
        return column
    }

    fun offsetAtVisualColumn(
        lineIndex: Int,
        targetColumn: Int,
    ): Int {
        require(targetColumn >= 0)
        val line = lines[lineIndex]
        var cursor = line.startUtf16
        var column = 0
        while (cursor < line.contentEndUtf16) {
            val char = buffer.charAt(cursor)
            val nextCursor = if (char == '\t') cursor + 1 else buffer.nextScalarBoundary(cursor)
            val nextColumn = if (char == '\t') column + tabWidth - column % tabWidth else column + 1
            if (nextColumn > targetColumn) break
            cursor = nextCursor
            column = nextColumn
        }
        return cursor
    }

    fun visualWidth(lineIndex: Int): Int = visualColumn(lines[lineIndex].contentEndUtf16)

    fun affectedLines(range: EditorRange): IntRange {
        val first = lineOfOffset(range.startUtf16)
        val last = lineOfOffset(range.endUtf16)
        return first..last
    }
}
