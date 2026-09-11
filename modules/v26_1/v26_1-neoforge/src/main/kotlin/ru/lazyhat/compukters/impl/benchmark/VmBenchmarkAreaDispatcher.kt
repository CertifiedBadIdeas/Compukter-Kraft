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
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.util.concurrent.CompletableFuture

internal class VmBenchmarkAreaDispatcher(
    private val maximumPositions: Int = MAXIMUM_POSITIONS,
    private val maximumComputers: Int = MAXIMUM_COMPUTERS,
) {
    init {
        require(maximumPositions > 0) { "maximum benchmark positions must be positive" }
        require(maximumComputers > 0) { "maximum benchmark computers must be positive" }
    }

    fun dispatch(
        access: VmBenchmarkAreaAccess,
        first: BlockPos,
        second: BlockPos,
        rounds: Int,
        workload: VmBenchmarkAreaWorkload = VmBenchmarkAreaWorkload.CPU,
    ): VmBenchmarkAreaDispatch {
        require(rounds in 1..MAXIMUM_ROUNDS) { "benchmark rounds must be in 1..$MAXIMUM_ROUNDS" }
        val minimum = BlockPos(minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z))
        val maximum = BlockPos(maxOf(first.x, second.x), maxOf(first.y, second.y), maxOf(first.z, second.z))
        val positions = volume(minimum, maximum)
        require(positions <= maximumPositions) { "benchmark area contains $positions blocks; maximum is $maximumPositions" }

        var unloaded = 0
        var computers = 0
        var limited = 0
        val deliveries = mutableListOf<CompletableFuture<Boolean>>()
        for (y in minimum.y..maximum.y) {
            for (z in minimum.z..maximum.z) {
                for (x in minimum.x..maximum.x) {
                    val position = BlockPos(x, y, z)
                    if (!access.isLoaded(position)) {
                        unloaded++
                        continue
                    }
                    val computer = access.computer(position) ?: continue
                    computers++
                    if (deliveries.size >= maximumComputers) {
                        limited++
                        continue
                    }
                    deliveries += computer.submit(workload.command(rounds)).exceptionally { false }
                }
            }
        }
        val completion =
            CompletableFuture.allOf(*deliveries.toTypedArray()).thenApply {
                val accepted = deliveries.count { it.getNow(false) }
                VmBenchmarkAreaResult(accepted, deliveries.size - accepted, limited)
            }
        return VmBenchmarkAreaDispatch(positions.toInt(), unloaded, computers, deliveries.size, limited, completion)
    }

    private fun volume(
        minimum: BlockPos,
        maximum: BlockPos,
    ): Long {
        val width = maximum.x.toLong() - minimum.x + 1
        val height = maximum.y.toLong() - minimum.y + 1
        val depth = maximum.z.toLong() - minimum.z + 1
        if (width > maximumPositions || height > maximumPositions || depth > maximumPositions) {
            return maximumPositions.toLong() + 1
        }
        val plane = width * height
        return if (plane > maximumPositions / depth) maximumPositions.toLong() + 1 else plane * depth
    }

    private companion object {
        const val MAXIMUM_POSITIONS = 32_768
        const val MAXIMUM_COMPUTERS = 1_000
        const val MAXIMUM_ROUNDS = 1_000_000
    }
}

internal enum class VmBenchmarkAreaWorkload(
    private val argument: String,
) {
    CPU("cpu"),
    REDSTONE("redstone"),
    ;

    fun command(rounds: Int): String = "/rom/vmbench $argument $rounds"
}

internal interface VmBenchmarkAreaAccess {
    fun isLoaded(position: BlockPos): Boolean

    fun computer(position: BlockPos): VmBenchmarkAreaComputer?
}

internal fun interface VmBenchmarkAreaComputer {
    fun submit(command: String): CompletableFuture<Boolean>
}

internal class MinecraftVmBenchmarkAreaAccess(
    private val level: ServerLevel,
) : VmBenchmarkAreaAccess {
    override fun isLoaded(position: BlockPos): Boolean = level.hasChunkAt(position)

    override fun computer(position: BlockPos): VmBenchmarkAreaComputer? {
        val entity = level.getBlockEntity(position) as? ComputerBlockEntity ?: return null
        return VmBenchmarkAreaComputer { command ->
            entity.prepareTerminalAsync().thenCompose { terminal ->
                if (terminal == null) {
                    CompletableFuture.completedFuture(false)
                } else {
                    entity.submitCanonicalLineAsync(command.toCharArray())
                }
            }
        }
    }
}

internal data class VmBenchmarkAreaDispatch(
    val scannedPositions: Int,
    val unloadedPositions: Int,
    val discoveredComputers: Int,
    val scheduledComputers: Int,
    val limitedComputers: Int,
    val completion: CompletableFuture<VmBenchmarkAreaResult>,
)

internal data class VmBenchmarkAreaResult(
    val acceptedComputers: Int,
    val rejectedComputers: Int,
    val limitedComputers: Int,
)
