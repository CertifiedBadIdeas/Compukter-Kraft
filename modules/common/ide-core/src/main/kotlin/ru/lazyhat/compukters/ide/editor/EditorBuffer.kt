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

internal class EditorBuffer(
    initial: String,
    private val limits: EditorLimits,
) {
    private var content: CharArray
    private var gapStart: Int
    private var gapEnd: Int

    var length: Int = initial.length
        private set

    var utf8ByteLength: Int
        private set

    init {
        val initialBytes = requireNotNull(Utf16.strictUtf8Length(initial)) { "editor text must be well-formed UTF-16" }
        require(initial.length <= limits.maxCodeUnits) { "editor text exceeds code-unit limit" }
        require(initialBytes <= limits.maxUtf8Bytes) { "editor text exceeds UTF-8 byte limit" }
        val desiredCapacity =
            minOf(
                limits.maxCodeUnits.toLong(),
                initial.length.toLong() + limits.initialGapCodeUnits.toLong(),
            ).toInt()
        content = CharArray(desiredCapacity)
        initial.toCharArray(content, destinationOffset = 0)
        gapStart = initial.length
        gapEnd = content.size
        utf8ByteLength = initialBytes
    }

    fun charAt(index: Int): Char {
        if (index !in 0..<length) throw IndexOutOfBoundsException("editor offset $index is outside 0..<$length")
        return content[physicalIndex(index)]
    }

    fun copyRange(
        start: Int,
        end: Int,
    ): CharArray {
        requireReadableRange(start, end)
        return CharArray(end - start) { index -> charAt(start + index) }
    }

    fun contentEquals(value: String): Boolean {
        if (value.length != length) return false
        repeat(length) { index -> if (charAt(index) != value[index]) return false }
        return true
    }

    fun materialize(): String = copyRange(0, length).concatToString()

    fun replace(
        start: Int,
        end: Int,
        replacement: String,
    ): BufferReplaceResult {
        if (!isValidRange(start, end) || !isScalarBoundary(start) || !isScalarBoundary(end)) {
            return BufferReplaceResult.Rejected(EditorRejection.InvalidRange)
        }
        val replacementBytes =
            Utf16.strictUtf8Length(replacement)
                ?: return BufferReplaceResult.Rejected(EditorRejection.InvalidUtf16)
        val removed = end - start
        val resultingLength = length.toLong() - removed + replacement.length
        if (resultingLength > limits.maxCodeUnits) {
            return BufferReplaceResult.Rejected(EditorRejection.CodeUnitLimit)
        }
        val removedBytes = Utf16.strictUtf8Length(removed) { index -> charAt(start + index) }
        val resultingBytes = utf8ByteLength.toLong() - removedBytes + replacementBytes
        if (resultingBytes > limits.maxUtf8Bytes) {
            return BufferReplaceResult.Rejected(EditorRejection.Utf8ByteLimit)
        }

        moveGap(start)
        gapEnd += removed
        length -= removed
        ensureGap(replacement.length)
        replacement.toCharArray(content, destinationOffset = gapStart)
        gapStart += replacement.length
        length += replacement.length
        utf8ByteLength = resultingBytes.toInt()
        return BufferReplaceResult.Applied
    }

    fun previousScalarBoundary(offset: Int): Int {
        requireOffset(offset)
        if (offset == 0) return 0
        return if (
            offset >= 2 &&
            Character.isHighSurrogate(charAt(offset - 2)) &&
            Character.isLowSurrogate(charAt(offset - 1))
        ) {
            offset - 2
        } else {
            offset - 1
        }
    }

    fun nextScalarBoundary(offset: Int): Int {
        requireOffset(offset)
        if (offset == length) return length
        return if (
            offset + 1 < length &&
            Character.isHighSurrogate(charAt(offset)) &&
            Character.isLowSurrogate(charAt(offset + 1))
        ) {
            offset + 2
        } else {
            offset + 1
        }
    }

    private fun physicalIndex(logicalIndex: Int): Int = if (logicalIndex < gapStart) logicalIndex else logicalIndex + gapEnd - gapStart

    private fun moveGap(target: Int) {
        when {
            target < gapStart -> {
                val count = gapStart - target
                content.copyInto(content, destinationOffset = gapEnd - count, startIndex = target, endIndex = gapStart)
                gapStart = target
                gapEnd -= count
            }

            target > gapStart -> {
                val count = target - gapStart
                content.copyInto(content, destinationOffset = gapStart, startIndex = gapEnd, endIndex = gapEnd + count)
                gapStart += count
                gapEnd += count
            }
        }
    }

    private fun ensureGap(required: Int) {
        val currentGap = gapEnd - gapStart
        if (currentGap >= required) return
        val requiredCapacity = length + required
        var newCapacity = maxOf(content.size.toLong() * 2, requiredCapacity.toLong(), 1L)
        newCapacity = minOf(newCapacity, limits.maxCodeUnits.toLong())
        check(newCapacity >= requiredCapacity)
        val replacement = CharArray(newCapacity.toInt())
        content.copyInto(replacement, endIndex = gapStart)
        val rightCount = content.size - gapEnd
        val newGapEnd = replacement.size - rightCount
        content.copyInto(replacement, destinationOffset = newGapEnd, startIndex = gapEnd)
        content = replacement
        gapEnd = newGapEnd
    }

    private fun isValidRange(
        start: Int,
        end: Int,
    ): Boolean = start >= 0 && end >= start && end <= length

    private fun isScalarBoundary(offset: Int): Boolean =
        offset == 0 ||
            offset == length ||
            !(Character.isHighSurrogate(charAt(offset - 1)) && Character.isLowSurrogate(charAt(offset)))

    private fun requireReadableRange(
        start: Int,
        end: Int,
    ) {
        if (!isValidRange(start, end)) throw IndexOutOfBoundsException("editor range $start..<$end is outside 0..<$length")
    }

    private fun requireOffset(offset: Int) {
        if (offset !in 0..length) throw IndexOutOfBoundsException("editor offset $offset is outside 0..$length")
    }
}
