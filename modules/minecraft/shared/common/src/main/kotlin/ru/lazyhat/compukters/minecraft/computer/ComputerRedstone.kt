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

internal enum class LocalRedstoneSide {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
}

internal fun worldDirection(
    facing: Direction,
    side: LocalRedstoneSide,
): Direction {
    require(facing.axis.isHorizontal) { "computer facing must be horizontal" }
    return when (side) {
        LocalRedstoneSide.FRONT -> facing
        LocalRedstoneSide.BACK -> facing.opposite
        LocalRedstoneSide.LEFT -> facing.counterClockWise
        LocalRedstoneSide.RIGHT -> facing.clockWise
        LocalRedstoneSide.TOP -> Direction.UP
        LocalRedstoneSide.BOTTOM -> Direction.DOWN
    }
}

internal fun localSide(
    facing: Direction,
    direction: Direction,
): LocalRedstoneSide {
    require(facing.axis.isHorizontal) { "computer facing must be horizontal" }
    return LocalRedstoneSide.entries.first { worldDirection(facing, it) == direction }
}

internal fun emittedSideForQuery(
    facing: Direction,
    queryDirection: Direction,
): LocalRedstoneSide = localSide(facing, queryDirection.opposite)

internal fun redstoneOutputField(
    packed: Int,
    side: LocalRedstoneSide,
): Int = RedstoneWire.output(packed, side.ordinal)

internal fun redstoneOutputLevel(
    packed: Int,
    side: LocalRedstoneSide,
): Int = redstoneOutputField(packed, side) and RedstoneWire.SIGNAL_MASK

internal fun redstoneDirectOutputLevel(
    packed: Int,
    side: LocalRedstoneSide,
): Int = redstoneOutputField(packed, side).let { if (it and RedstoneWire.DIRECT_MASK != 0) it and RedstoneWire.SIGNAL_MASK else 0 }
