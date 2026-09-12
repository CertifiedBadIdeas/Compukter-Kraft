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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedstoneWireTest {
    @Test
    fun `input packet keeps changed mask and six four bit levels`() {
        val levels = intArrayOf(1, 2, 3, 4, 5, 15)
        val packet = RedstoneWire.packInput(0b10_0101, levels)

        assertEquals(0b10_0101, RedstoneWire.inputChangedMask(packet))
        assertEquals(0x3f, RedstoneWire.inputChangedMask(RedstoneWire.withAllInputSidesChanged(packet)))
        levels.indices.forEach { side ->
            assertEquals(levels[side], RedstoneWire.inputLevel(packet, side))
        }
        assertEquals(0, packet ushr 30)
    }

    @Test
    fun `output register keeps six five bit fields`() {
        val fields = intArrayOf(0, 1, 15, 16, 23, 31)
        val packed =
            fields.indices.fold(0) { value, side ->
                RedstoneWire.replaceOutput(value, side, fields[side])
            }

        fields.indices.forEach { side ->
            assertEquals(fields[side], RedstoneWire.output(packed, side))
        }
        assertEquals(0, packed ushr 30)
    }

    @Test
    fun `wire validators reject invalid sides fields masks and reserved bits`() {
        assertFailsWith<IllegalArgumentException> { RedstoneWire.packInput(0x40, IntArray(6)) }
        assertFailsWith<IllegalArgumentException> { RedstoneWire.inputLevel(0, -1) }
        assertFailsWith<IllegalArgumentException> { RedstoneWire.output(0, 6) }
        assertFailsWith<IllegalArgumentException> { RedstoneWire.replaceOutput(0, 0, 32) }
        assertFailsWith<IllegalArgumentException> { RedstoneWire.requireInputPacket(1 shl 30) }
        assertFailsWith<IllegalArgumentException> { RedstoneWire.requireOutputRegister(1 shl 31) }
    }
}
