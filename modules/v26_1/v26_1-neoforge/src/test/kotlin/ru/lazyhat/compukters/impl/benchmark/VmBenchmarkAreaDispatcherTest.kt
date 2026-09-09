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

package ru.lazyhat.compukters.impl.benchmark

import net.minecraft.core.BlockPos
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VmBenchmarkAreaDispatcherTest {
    @Test
    fun `dispatch skips unloaded positions and bounds computer fanout`() {
        val submitted = mutableListOf<String>()
        val access =
            object : VmBenchmarkAreaAccess {
                override fun isLoaded(position: BlockPos) = position.x != 1

                override fun computer(position: BlockPos): VmBenchmarkAreaComputer? =
                    if (position.x == 0 || position.x == 2) {
                        VmBenchmarkAreaComputer { command ->
                            submitted += "${position.x}:$command"
                            CompletableFuture.completedFuture(position.x == 0)
                        }
                    } else {
                        null
                    }
            }
        val dispatch =
            VmBenchmarkAreaDispatcher(maximumPositions = 4, maximumComputers = 1).dispatch(
                access,
                BlockPos(0, 0, 0),
                BlockPos(3, 0, 0),
                9,
            )

        assertEquals(4, dispatch.scannedPositions)
        assertEquals(1, dispatch.unloadedPositions)
        assertEquals(2, dispatch.discoveredComputers)
        assertEquals(1, dispatch.scheduledComputers)
        assertEquals(1, dispatch.limitedComputers)
        assertEquals(listOf("0:/rom/vmbench cpu 9"), submitted)
        assertEquals(VmBenchmarkAreaResult(1, 0, 1), dispatch.completion.getNow(null))
    }

    @Test
    fun `oversized area is rejected before accessing chunks`() {
        var accesses = 0
        val access =
            object : VmBenchmarkAreaAccess {
                override fun isLoaded(position: BlockPos): Boolean {
                    accesses++
                    return true
                }

                override fun computer(position: BlockPos): VmBenchmarkAreaComputer? = null
            }

        assertFailsWith<IllegalArgumentException> {
            VmBenchmarkAreaDispatcher(maximumPositions = 8).dispatch(access, BlockPos.ZERO, BlockPos(8, 0, 0), 1)
        }
        assertEquals(0, accesses)
    }
}
