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

package ru.lazyhat.compukters.compiler.artifact.write

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinarySinkTest {
    @Test
    fun `fixed integers are little endian`() {
        val sink = BinarySink(32)
        sink.writeU16(0x1234u)
        sink.writeU32(0x89ab_cdefu)
        sink.writeU64(0x0102_0304_0506_0708u)

        assertContentEquals(
            byteArrayOf(
                0x34,
                0x12,
                0xef.toByte(),
                0xcd.toByte(),
                0xab.toByte(),
                0x89.toByte(),
                0x08,
                0x07,
                0x06,
                0x05,
                0x04,
                0x03,
                0x02,
                0x01,
            ),
            sink.toByteArray(),
        )
    }

    @Test
    fun `ULEB128 is shortest for every u32 boundary`() {
        val sink = BinarySink(32)
        listOf(0u, 127u, 128u, 0x7fff_ffffu, UInt.MAX_VALUE).forEach(sink::writeUleb128)

        assertContentEquals(
            byteArrayOf(
                0x00,
                0x7f,
                0x80.toByte(),
                0x01,
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0x07,
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0x0f,
            ),
            sink.toByteArray(),
        )
    }

    @Test
    fun `indexed envelope is aligned and contains canonical offsets`() {
        val encoded = encodeIndexed(listOf(byteArrayOf(1, 2), byteArrayOf(3)), 64)

        assertEquals(35, encoded.size)
        assertContentEquals(byteArrayOf(2, 0, 0, 0), encoded.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0), encoded.copyOfRange(16, 28))
        assertContentEquals(byteArrayOf(1, 2, 3), encoded.copyOfRange(32, 35))
        assertContentEquals(ByteArray(24), encodeIndexed(emptyList(), 24))
    }

    @Test
    fun `sink rejects growth before exceeding its bound`() {
        val sink = BinarySink(3)
        sink.writeU16(1u)

        val failure = assertFailsWith<ArtifactEncodingException> { sink.writeU16(2u) }
        assertEquals(ArtifactWriteErrorCode.LIMIT_EXCEEDED, failure.code)
        assertContentEquals(byteArrayOf(1, 0), sink.toByteArray())
    }
}
