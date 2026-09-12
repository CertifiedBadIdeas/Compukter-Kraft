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

object RedstoneWire {
    const val SIDE_COUNT = 6
    const val ALL_SIDES_MASK = 0x3f
    const val SIGNAL_MASK = 0x0f
    const val OUTPUT_MASK = 0x1f
    const val DIRECT_MASK = 0x10
    const val REGISTER_MASK = 0x3fff_ffff

    fun packInput(
        changedMask: Int,
        levels: IntArray,
    ): Int {
        require(changedMask and ALL_SIDES_MASK.inv() == 0)
        require(levels.size == SIDE_COUNT)
        return levels.indices.fold(changedMask) { packet, side ->
            require(levels[side] in 0..SIGNAL_MASK)
            packet or (levels[side] shl inputShift(side))
        }
    }

    fun requireInputPacket(packet: Int): Int = packet.also { require(it and REGISTER_MASK.inv() == 0) }

    fun inputChangedMask(packet: Int): Int = requireInputPacket(packet) and ALL_SIDES_MASK

    fun withAllInputSidesChanged(packet: Int): Int = (requireInputPacket(packet) and ALL_SIDES_MASK.inv()) or ALL_SIDES_MASK

    fun inputLevel(
        packet: Int,
        side: Int,
    ): Int = (requireInputPacket(packet) ushr inputShift(side)) and SIGNAL_MASK

    fun requireOutputRegister(packed: Int): Int = packed.also { require(it and REGISTER_MASK.inv() == 0) }

    fun output(
        packed: Int,
        side: Int,
    ): Int = (requireOutputRegister(packed) ushr outputShift(side)) and OUTPUT_MASK

    fun replaceOutput(
        packed: Int,
        side: Int,
        output: Int,
    ): Int {
        require(output in 0..OUTPUT_MASK)
        val shift = outputShift(side)
        val cleared = requireOutputRegister(packed) and (OUTPUT_MASK shl shift).inv()
        return cleared or (output shl shift)
    }

    private fun inputShift(side: Int): Int {
        require(side in 0 until SIDE_COUNT)
        return 6 + side * 4
    }

    private fun outputShift(side: Int): Int {
        require(side in 0 until SIDE_COUNT)
        return side * 5
    }
}
