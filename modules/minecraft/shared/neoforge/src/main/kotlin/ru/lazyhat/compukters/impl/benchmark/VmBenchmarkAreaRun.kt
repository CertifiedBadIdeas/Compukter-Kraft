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

internal class VmBenchmarkAreaRun(
    private val dispatch: VmBenchmarkAreaDispatch,
    private val workload: VmBenchmarkAreaWorkload,
    private val rounds: Int,
    private val startedTick: Long,
) {
    init {
        require(rounds > 0) { "area benchmark rounds must be positive" }
        require(startedTick >= 0) { "area benchmark start tick must not be negative" }
    }

    fun snapshot(worldTick: Long): VmBenchmarkAreaSnapshot {
        require(worldTick >= startedTick) { "area benchmark world tick precedes its start" }
        var pending = 0
        var accepted = 0
        var rejected = 0
        var active = 0
        var completed = 0
        var unavailable = 0
        dispatch.deliveries.forEach { delivery ->
            if (!delivery.accepted.isDone) {
                pending++
                return@forEach
            }
            if (delivery.accepted.getNow(false) != true) {
                rejected++
                return@forEach
            }
            accepted++
            when (delivery.computer.benchmarkState()) {
                VmBenchmarkAreaComputerState.ACTIVE -> active++
                VmBenchmarkAreaComputerState.WAITING_FOR_INPUT -> completed++
                VmBenchmarkAreaComputerState.UNAVAILABLE -> unavailable++
            }
        }
        val status =
            if (pending == 0 && active == 0) {
                VmBenchmarkAreaStatus.COMPLETED
            } else {
                VmBenchmarkAreaStatus.RUNNING
            }
        return VmBenchmarkAreaSnapshot(
            status = status,
            workload = workload,
            rounds = rounds,
            scheduledComputers = dispatch.scheduledComputers,
            pendingComputers = pending,
            acceptedComputers = accepted,
            rejectedComputers = rejected,
            activeComputers = active,
            completedComputers = completed,
            unavailableComputers = unavailable,
            limitedComputers = dispatch.limitedComputers,
            elapsedTicks = worldTick - startedTick,
            expectedWorldRequests = accepted.toLong() * rounds * workload.worldRequestsPerRound,
        )
    }
}

internal data class VmBenchmarkAreaSnapshot(
    val status: VmBenchmarkAreaStatus,
    val workload: VmBenchmarkAreaWorkload,
    val rounds: Int,
    val scheduledComputers: Int,
    val pendingComputers: Int,
    val acceptedComputers: Int,
    val rejectedComputers: Int,
    val activeComputers: Int,
    val completedComputers: Int,
    val unavailableComputers: Int,
    val limitedComputers: Int,
    val elapsedTicks: Long,
    val expectedWorldRequests: Long,
)

internal enum class VmBenchmarkAreaStatus {
    RUNNING,
    COMPLETED,
}
