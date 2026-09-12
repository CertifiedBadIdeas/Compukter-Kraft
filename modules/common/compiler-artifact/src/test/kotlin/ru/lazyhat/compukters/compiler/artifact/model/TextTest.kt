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

package ru.lazyhat.compukters.compiler.artifact.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextTest {
    @Test
    fun `metadata rejects an isolated surrogate instead of replacing it`() {
        assertFailsWith<IllegalArgumentException> { MetadataText.of("\ud800") }
    }

    @Test
    fun `metadata exposes exact strict UTF-8 bytes`() {
        assertContentEquals(
            byteArrayOf(0x61, 0xc3.toByte(), 0xa9.toByte()),
            MetadataText.of("aé").toByteArray(),
        )
    }

    @Test
    fun `literal preserves every UTF-16 code unit pattern`() {
        val literal = Utf16Literal.of(0xd800, 0x0000, 0xdc00)

        assertEquals(3, literal.size)
        assertContentEquals(
            byteArrayOf(0x00, 0xd8.toByte(), 0x00, 0x00, 0x00, 0xdc.toByte()),
            literal.toLittleEndianByteArray(),
        )
    }

    @Test
    fun `literal copies Kotlin String code units without charset conversion`() {
        val source = "\ud800x"
        assertContentEquals(
            byteArrayOf(0x00, 0xd8.toByte(), 0x78, 0x00),
            Utf16Literal.fromString(source).toLittleEndianByteArray(),
        )
    }
}
