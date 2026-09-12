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

import net.minecraft.core.Direction
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComputerRedstoneTest {
    @Test
    fun `north facing local sides map to the pinned world vector`() {
        assertEquals(Direction.NORTH, worldDirection(Direction.NORTH, LocalRedstoneSide.FRONT))
        assertEquals(Direction.SOUTH, worldDirection(Direction.NORTH, LocalRedstoneSide.BACK))
        assertEquals(Direction.WEST, worldDirection(Direction.NORTH, LocalRedstoneSide.LEFT))
        assertEquals(Direction.EAST, worldDirection(Direction.NORTH, LocalRedstoneSide.RIGHT))
        assertEquals(Direction.UP, worldDirection(Direction.NORTH, LocalRedstoneSide.TOP))
        assertEquals(Direction.DOWN, worldDirection(Direction.NORTH, LocalRedstoneSide.BOTTOM))
    }

    @Test
    fun `all horizontal mappings round trip and vanilla query direction is inverted`() {
        Direction.Plane.HORIZONTAL.forEach { facing ->
            LocalRedstoneSide.entries.forEach { side ->
                val emitted = worldDirection(facing, side)
                assertEquals(side, localSide(facing, emitted))
                assertEquals(side, emittedSideForQuery(facing, emitted.opposite))
            }
        }
        assertFailsWith<IllegalArgumentException> { worldDirection(Direction.UP, LocalRedstoneSide.FRONT) }
    }

    @Test
    fun `rotation remaps world faces without changing packed local fields`() {
        val packed =
            LocalRedstoneSide.entries.fold(0) { register, side ->
                RedstoneWire.replaceOutput(register, side.ordinal, side.ordinal + 1)
            }

        LocalRedstoneSide.entries.forEach { side ->
            assertEquals(
                redstoneOutputLevel(packed, side),
                redstoneOutputLevel(packed, localSide(Direction.EAST, worldDirection(Direction.EAST, side))),
            )
        }
        assertEquals(Direction.NORTH, worldDirection(Direction.NORTH, LocalRedstoneSide.FRONT))
        assertEquals(Direction.EAST, worldDirection(Direction.EAST, LocalRedstoneSide.FRONT))
    }

    @Test
    fun `direct flag gates only direct output while preserving weak level`() {
        val weak = RedstoneWire.replaceOutput(0, LocalRedstoneSide.LEFT.ordinal, 9)
        val direct = RedstoneWire.replaceOutput(0, LocalRedstoneSide.LEFT.ordinal, 9 or RedstoneWire.DIRECT_MASK)

        assertEquals(9, redstoneOutputLevel(weak, LocalRedstoneSide.LEFT))
        assertEquals(0, redstoneDirectOutputLevel(weak, LocalRedstoneSide.LEFT))
        assertEquals(9, redstoneOutputLevel(direct, LocalRedstoneSide.LEFT))
        assertEquals(9, redstoneDirectOutputLevel(direct, LocalRedstoneSide.LEFT))
    }
}
