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

package ru.lazyhat.compukters.impl.terminal

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalChange
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalPayloadsTest {
    @Test
    fun `bounded full and ordered delta payloads round trip`() {
        val full = TerminalFullPayload(POSITION, 9, terminalState(3), openScreen = true)
        assertEquals(full, roundTrip(TerminalFullPayload.STREAM_CODEC, full))

        val delta =
            TerminalDeltaPayload(
                POSITION,
                9,
                TerminalUpdate.Delta(
                    3,
                    4,
                    listOf(
                        TerminalChange.Scroll(1, cell(' ')),
                        TerminalChange.Patch(0, listOf(cell('λ'), cell(0x1f600))),
                        TerminalChange.Cursor(TerminalPosition(2, 0), visible = true),
                    ),
                ),
            )
        assertEquals(delta, roundTrip(TerminalDeltaPayload.STREAM_CODEC, delta))

        val resources =
            TerminalResourcePayload(
                POSITION,
                9,
                TerminalResourceGauges(6_250, 40, 100, 3, 4, TerminalResourceActivity.COLLECTING, false),
            )
        assertEquals(resources, roundTrip(TerminalResourcePayload.STREAM_CODEC, resources))
    }

    @Test
    fun `stable key and atomic Unicode text payloads round trip`() {
        val key =
            TerminalKeyPayload(
                POSITION,
                9,
                TerminalKey.F12,
                TerminalKeyAction.REPEAT,
                setOf(TerminalModifier.SHIFT, TerminalModifier.CONTROL),
            )
        assertEquals(key, roundTrip(TerminalKeyPayload.STREAM_CODEC, key))
        val save = key.copy(key = TerminalKey.S)
        assertEquals(save, roundTrip(TerminalKeyPayload.STREAM_CODEC, save))
        val exit = key.copy(key = TerminalKey.X)
        assertEquals(exit, roundTrip(TerminalKeyPayload.STREAM_CODEC, exit))

        val text = TerminalTextPayload(POSITION, 9, "λ😀")
        assertEquals(text, roundTrip(TerminalTextPayload.STREAM_CODEC, text))
    }

    @Test
    fun `invalid scalar palette rectangle and count are rejected before publication`() {
        val invalidScalar = terminalState(0).copy(cells = List(CELL_COUNT) { cell(0xd800) })
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalFullPayload.STREAM_CODEC, TerminalFullPayload(POSITION, 1, invalidScalar, false))
        }
        val invalidPalette = terminalState(0).copy(cells = List(CELL_COUNT) { TerminalCell(' '.code, 16, 0) })
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalFullPayload.STREAM_CODEC, TerminalFullPayload(POSITION, 1, invalidPalette, false))
        }
        val invalidRectangle =
            TerminalDeltaPayload(
                POSITION,
                1,
                TerminalUpdate.Delta(0, 1, listOf(TerminalChange.Fill(50, 18, 2, 1, cell('x')))),
            )
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalDeltaPayload.STREAM_CODEC, invalidRectangle)
        }
        val invalidCount = terminalState(0).copy(cells = emptyList())
        assertFailsWith<IllegalArgumentException> {
            roundTrip(TerminalFullPayload.STREAM_CODEC, TerminalFullPayload(POSITION, 1, invalidCount, false))
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalResourceGauges(10_001, 0, 1, 0, 1, TerminalResourceActivity.ACTIVE, false)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalResourceGauges(null, 2, 1, 0, 1, TerminalResourceActivity.ACTIVE, false)
        }
    }

    @Test
    fun `terminal resource replica accepts only its exact machine identity`() {
        val replica = TerminalResourceReplica(initialMachineId = 9)
        val accepted = TerminalResourcePayload(POSITION, 9, gauges())
        val stale = TerminalResourcePayload(POSITION, 8, gauges().copy(vmBudgetBasisPoints = 1))

        assertTrue(replica.update(accepted.machineId, accepted.gauges))
        assertFalse(replica.update(stale.machineId, stale.gauges))
        assertEquals(accepted.gauges, replica.gauges)

        replica.replaceMachine(10)
        assertEquals(TerminalResourceGauges.unavailable(), replica.gauges)
    }

    @Test
    fun `input rate limits are isolated per viewer and reset each tick`() {
        val limiter = TerminalInputRateLimiter(maximumEventsPerTick = 2)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertTrue(limiter.accept(first, 10))
        assertTrue(limiter.accept(first, 10))
        assertFalse(limiter.accept(first, 10))
        assertTrue(limiter.accept(second, 10))
        assertTrue(limiter.accept(first, 11))
    }

    @Suppress("DEPRECATION")
    private fun <T : Any> roundTrip(
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }

    private companion object {
        const val CELL_COUNT = 51 * 19
        val POSITION = BlockPos(2, 3, 4)

        fun cell(codePoint: Int): TerminalCell = TerminalCell(codePoint, 15, 0)

        fun cell(value: Char): TerminalCell = cell(value.code)

        fun terminalState(revision: Long): TerminalState =
            TerminalState(
                revision,
                51,
                19,
                List(CELL_COUNT) { cell(' ') },
                TerminalPosition(0, 0),
                true,
            )

        fun gauges(): TerminalResourceGauges = TerminalResourceGauges(5_000, 1, 2, 3, 4, TerminalResourceActivity.ACTIVE, false)
    }
}
