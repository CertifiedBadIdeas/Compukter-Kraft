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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntityType
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStateSink
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComputerBlockTest {
    @Test
    fun `computer defaults north and placement faces its front toward the player`() {
        assertEquals(Direction.NORTH, COMPUTER_DEFAULT_FACING)
        assertEquals(Direction.SOUTH, placementFacing(Direction.NORTH))
        assertEquals(Direction.WEST, placementFacing(Direction.EAST))
    }

    @Test
    fun `computer rotation and mirror transform its horizontal facing`() {
        assertEquals(Direction.SOUTH, rotateComputerFacing(Direction.EAST, Rotation.CLOCKWISE_90))
        assertEquals(Direction.WEST, mirrorComputerFacing(Direction.EAST, Mirror.FRONT_BACK))
    }

    @Test
    fun `ticker is absent on client and for a different block entity type`() {
        assertNull(computerTickerFor(isClientSide = true, actualType = TEST_TYPE, expectedType = TEST_TYPE))
        assertNull(
            computerTickerFor(
                isClientSide = false,
                actualType = BlockEntityType.BLAST_FURNACE,
                expectedType = TEST_TYPE,
            ),
        )
    }

    @Test
    fun `server ticker delegates exactly one block entity tick`() {
        val carrier = CountingCarrier()
        val entity = fixtureEntity(carrier)
        requireNotNull(computerTickerFor(isClientSide = false, actualType = TEST_TYPE, expectedType = TEST_TYPE))

        tickComputerEntity(entity)

        assertEquals(1, carrier.turnOnCalls)
        assertEquals(1, carrier.serverTickCalls)
    }

    private fun fixtureEntity(carrier: CountingCarrier): ComputerBlockEntity =
        ComputerBlockEntity(
            TEST_TYPE,
            BlockPos.ZERO,
            Blocks.FURNACE.defaultBlockState(),
            ComputerCarrierFactory { _, _, stateSink, _, _, _ ->
                carrier.attach(stateSink)
            },
        )

    private class CountingCarrier : ComputerCarrier {
        override var state: ProgramComputerState = neverStarted()
            private set
        var turnOnCalls = 0
        var serverTickCalls = 0
        private lateinit var stateSink: ProgramComputerStateSink

        fun attach(stateSink: ProgramComputerStateSink): ComputerCarrier {
            this.stateSink = stateSink
            return this
        }

        override fun turnOn(): ProgramComputerState {
            turnOnCalls++
            state = ProgramComputerState.Running
            stateSink.publishState(0, state)
            return state
        }

        override fun serverTick(worldTick: Long): ProgramComputerState {
            serverTickCalls++
            return state
        }

        override fun terminalFullState(): TerminalState? = null

        override fun terminalChangesSince(revision: Long): TerminalUpdate? = null

        override fun sendTerminalKey(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier>,
        ): Boolean = false

        override fun sendTerminalText(value: String): Boolean = false

        override fun filesystemGeneration(): Long? = null

        override fun submitRedstoneInput(packet: Int): Boolean = true

        override fun reboot(): ProgramComputerState = turnOn()

        override fun shutdown() = Unit

        override fun close() = Unit
    }

    companion object {
        @Suppress("unused")
        private val MINECRAFT_BOOTSTRAP =
            run {
                SharedConstants.tryDetectVersion()
                Bootstrap.bootStrap()
            }

        @Suppress("UNCHECKED_CAST")
        private val TEST_TYPE = BlockEntityType.FURNACE as BlockEntityType<ComputerBlockEntity>

        private fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
    }
}
