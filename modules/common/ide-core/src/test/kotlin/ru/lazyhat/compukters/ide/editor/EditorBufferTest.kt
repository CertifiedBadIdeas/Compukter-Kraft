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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorBufferTest {
    @Test
    fun `gap moves and grows while preserving exact logical text`() {
        val buffer = EditorBuffer("ab😀cd", limits(maxCodeUnits = 16, maxUtf8Bytes = 32, initialGapCodeUnits = 1))

        assertEquals(6, buffer.length)
        assertEquals(8, buffer.utf8ByteLength)
        assertEquals(BufferReplaceResult.Applied, buffer.replace(2, 4, "x"))
        assertEquals(BufferReplaceResult.Applied, buffer.replace(0, 0, "12"))
        assertEquals(BufferReplaceResult.Applied, buffer.replace(buffer.length, buffer.length, "34"))

        assertEquals("12abxcd34", buffer.materialize())
        assertEquals('a', buffer.charAt(2))
        assertContentEquals("abx".toCharArray(), buffer.copyRange(2, 5))
        assertTrue(buffer.contentEquals("12abxcd34"))
        assertFalse(buffer.contentEquals("12abxcd35"))
    }

    @Test
    fun `code unit and UTF-8 limits reject atomically`() {
        val units = EditorBuffer("😀", limits(maxCodeUnits = 3, maxUtf8Bytes = 16))
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.CodeUnitLimit),
            units.replace(2, 2, "ab"),
        )
        assertEquals("😀", units.materialize())

        val bytes = EditorBuffer("é", limits(maxCodeUnits = 8, maxUtf8Bytes = 4))
        assertEquals(BufferReplaceResult.Applied, bytes.replace(1, 1, "é"))
        assertEquals(4, bytes.utf8ByteLength)
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.Utf8ByteLimit),
            bytes.replace(2, 2, "a"),
        )
        assertEquals("éé", bytes.materialize())
        assertEquals(4, bytes.utf8ByteLength)
    }

    @Test
    fun `valid surrogate pairs are indivisible and malformed input is rejected`() {
        val buffer = EditorBuffer("a😀b", limits())

        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.InvalidRange),
            buffer.replace(2, 2, "x"),
        )
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.InvalidRange),
            buffer.replace(1, 2, ""),
        )
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.InvalidUtf16),
            buffer.replace(0, 0, charArrayOf('\uD800').concatToString()),
        )
        assertEquals("a😀b", buffer.materialize())

        assertFailsWith<IllegalArgumentException> {
            EditorBuffer(charArrayOf('\uDC00').concatToString(), limits())
        }
    }

    @Test
    fun `scalar boundaries and empty bounded buffer are exact`() {
        val buffer = EditorBuffer("a😀b", limits())
        assertEquals(0, buffer.previousScalarBoundary(0))
        assertEquals(1, buffer.previousScalarBoundary(3))
        assertEquals(3, buffer.nextScalarBoundary(1))
        assertEquals(4, buffer.nextScalarBoundary(4))

        val empty = EditorBuffer("", limits(maxCodeUnits = 0, maxUtf8Bytes = 0, initialGapCodeUnits = 0))
        assertEquals("", empty.materialize())
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.CodeUnitLimit),
            empty.replace(0, 0, "a"),
        )
    }

    @Test
    fun `invalid limits initial bounds and ranges fail deterministically`() {
        assertFailsWith<IllegalArgumentException> { EditorLimits(maxCodeUnits = -1) }
        assertFailsWith<IllegalArgumentException> { EditorLimits(maxUtf8Bytes = -1) }
        assertFailsWith<IllegalArgumentException> { EditorLimits(initialGapCodeUnits = -1) }
        assertFailsWith<IllegalArgumentException> { EditorLimits(maxUndoEntries = -1) }
        assertFailsWith<IllegalArgumentException> { EditorLimits(maxUndoCodeUnits = -1) }
        assertFailsWith<IllegalArgumentException> { EditorLimits(tabWidth = 0) }
        assertFailsWith<IllegalArgumentException> {
            EditorBuffer("abcd", limits(maxCodeUnits = 3, maxUtf8Bytes = 8))
        }
        assertFailsWith<IllegalArgumentException> {
            EditorBuffer("éé", limits(maxCodeUnits = 4, maxUtf8Bytes = 3))
        }

        val buffer = EditorBuffer("abc", limits())
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.InvalidRange),
            buffer.replace(-1, 0, ""),
        )
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.InvalidRange),
            buffer.replace(2, 1, ""),
        )
        assertEquals(
            BufferReplaceResult.Rejected(EditorRejection.InvalidRange),
            buffer.replace(0, 4, ""),
        )
        assertFailsWith<IndexOutOfBoundsException> { buffer.charAt(3) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.copyRange(0, 4) }
    }

    private fun limits(
        maxCodeUnits: Int = 32,
        maxUtf8Bytes: Int = 64,
        initialGapCodeUnits: Int = 2,
    ): EditorLimits =
        EditorLimits(
            maxCodeUnits = maxCodeUnits,
            maxUtf8Bytes = maxUtf8Bytes,
            initialGapCodeUnits = initialGapCodeUnits,
            maxUndoEntries = 8,
            maxUndoCodeUnits = 32,
            tabWidth = 4,
        )
}
