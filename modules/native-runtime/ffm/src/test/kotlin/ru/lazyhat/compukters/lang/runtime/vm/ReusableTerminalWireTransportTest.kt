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

package ru.lazyhat.compukters.lang.runtime.vm

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ReusableTerminalWireTransportTest {
    @Test
    fun `one transport reuses native scratch while separate transports remain isolated`() {
        val firstAddresses = mutableListOf<Pair<Long, Long>>()
        val secondAddresses = mutableListOf<Pair<Long, Long>>()
        val full = fullStateWire(revision = 4)
        val unchanged = unchangedWire(revision = 4)
        val first = transport(firstAddresses, full, unchanged)
        val second = transport(secondAddresses, full, unchanged)

        assertEquals(4, first.fullState(7).revision)
        assertEquals(4, first.fullState(7).revision)
        assertEquals(TerminalUpdate.Unchanged(4), first.changesSince(7, 4))
        assertEquals(TerminalUpdate.Unchanged(4), first.changesSince(7, 4))
        assertEquals(4, second.fullState(8).revision)

        assertEquals(1, firstAddresses.map(Pair<Long, Long>::first).distinct().size)
        assertEquals(1, firstAddresses.map(Pair<Long, Long>::second).distinct().size)
        assertNotEquals(firstAddresses.first().first, secondAddresses.first().first)

        first.close()
        first.close()
        second.close()
        assertFailsWith<IllegalStateException> { first.fullState(7) }
    }

    private fun transport(
        addresses: MutableList<Pair<Long, Long>>,
        full: ByteArray,
        unchanged: ByteArray,
    ): ReusableTerminalWireTransport =
        ReusableTerminalWireTransport(
            maximumBytes = 8 * 1024,
            fullStateCall =
                TerminalFullStateCall { _, output, _, written ->
                    writeResult(addresses, output, written, full)
                },
            changesSinceCall =
                TerminalChangesSinceCall { _, _, output, _, written ->
                    writeResult(addresses, output, written, unchanged)
                },
        )

    private fun writeResult(
        addresses: MutableList<Pair<Long, Long>>,
        output: MemorySegment,
        written: MemorySegment,
        bytes: ByteArray,
    ): Int {
        addresses += output.address() to written.address()
        MemorySegment.copy(bytes, 0, output, ValueLayout.JAVA_BYTE, 0, bytes.size)
        written.set(ValueLayout.JAVA_LONG, 0, bytes.size.toLong())
        return 0
    }

    private fun unchangedWire(revision: Long): ByteArray =
        ByteBuffer
            .allocate(1 + Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .putLong(revision)
            .array()

    private fun fullStateWire(revision: Long): ByteArray =
        ByteBuffer
            .allocate(1 + Long.SIZE_BYTES + 2 * Short.SIZE_BYTES + Int.SIZE_BYTES + 969 * 6 + 2 * Short.SIZE_BYTES + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(2)
            .putLong(revision)
            .putShort(51)
            .putShort(19)
            .putInt(969)
            .apply {
                repeat(969) {
                    putInt(' '.code)
                    put(15)
                    put(0)
                }
            }.putShort(0)
            .putShort(0)
            .put(1)
            .array()
}
