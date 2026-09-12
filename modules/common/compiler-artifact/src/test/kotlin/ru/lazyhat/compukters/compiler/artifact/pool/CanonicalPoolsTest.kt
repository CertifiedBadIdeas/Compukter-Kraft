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

package ru.lazyhat.compukters.compiler.artifact.pool

import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalPoolsTest {
    @Test
    fun `metadata freeze is independent of insertion order and deduplicates`() {
        val firstBuilder = MetadataPoolBuilder()
        val entry = firstBuilder.intern(MetadataText.of("entry"))
        val app = firstBuilder.intern(MetadataText.of("app"))
        val duplicateEntry = firstBuilder.intern(MetadataText.of("entry"))

        val secondBuilder = MetadataPoolBuilder()
        secondBuilder.intern(MetadataText.of("app"))
        secondBuilder.intern(MetadataText.of("entry"))

        val first = firstBuilder.freeze()
        val second = secondBuilder.freeze()

        assertEquals(listOf("app", "entry"), first.records.map { it.toString() })
        assertEquals(first.records, second.records)
        assertEquals(1u, first.idOf(entry).value)
        assertEquals(0u, first.idOf(app).value)
        assertEquals(first.idOf(entry), first.idOf(duplicateEntry))
    }

    @Test
    fun `UTF-16 freeze sorts exact little-endian bytes`() {
        val builder = Utf16LiteralPoolBuilder()
        val one = builder.intern(Utf16Literal.of(0x0001))
        val byteFirst = builder.intern(Utf16Literal.of(0x0100))
        val empty = builder.intern(Utf16Literal.of())
        val surrogate = builder.intern(Utf16Literal.of(0xd800, 0x0000))
        val duplicate = builder.intern(Utf16Literal.of(0x0001))

        val frozen = builder.freeze()

        assertEquals(4, frozen.records.size)
        assertEquals(0u, frozen.idOf(empty).value)
        assertEquals(1u, frozen.idOf(byteFirst).value)
        assertEquals(2u, frozen.idOf(surrogate).value)
        assertEquals(3u, frozen.idOf(one).value)
        assertEquals(frozen.idOf(one), frozen.idOf(duplicate))
        assertContentEquals(
            byteArrayOf(0x00, 0xd8.toByte(), 0x00, 0x00),
            frozen.records[frozen.idOf(surrogate).value.toInt()].toLittleEndianByteArray(),
        )
    }

    @Test
    fun `key cannot be resolved by another builder`() {
        val first = MetadataPoolBuilder()
        val key = first.intern(MetadataText.of("app"))
        val second = MetadataPoolBuilder()
        second.intern(MetadataText.of("app"))

        assertFailsWith<IllegalArgumentException> { second.freeze().idOf(key) }
    }
}
