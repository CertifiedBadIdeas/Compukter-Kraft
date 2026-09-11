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

import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class VmBenchmarkAreaRunTest {
    @Test
    fun `snapshot distinguishes delivery activity completion and loss without polling the VM`() {
        val pending = CompletableFuture<Boolean>()
        val pendingComputer = FakeComputer()
        val active = FakeComputer(VmBenchmarkAreaComputerState.ACTIVE)
        val completed = FakeComputer(VmBenchmarkAreaComputerState.WAITING_FOR_INPUT)
        val unavailable = FakeComputer(VmBenchmarkAreaComputerState.UNAVAILABLE)
        val dispatch =
            dispatch(
                VmBenchmarkAreaDelivery(pendingComputer, pending),
                VmBenchmarkAreaDelivery(active, CompletableFuture.completedFuture(true)),
                VmBenchmarkAreaDelivery(completed, CompletableFuture.completedFuture(true)),
                VmBenchmarkAreaDelivery(unavailable, CompletableFuture.completedFuture(true)),
                VmBenchmarkAreaDelivery(FakeComputer(), CompletableFuture.completedFuture(false)),
            )
        val run = VmBenchmarkAreaRun(dispatch, VmBenchmarkAreaWorkload.REDSTONE, rounds = 600, startedTick = 10)

        val running = run.snapshot(30)

        assertEquals(VmBenchmarkAreaStatus.RUNNING, running.status)
        assertEquals(1, running.pendingComputers)
        assertEquals(3, running.acceptedComputers)
        assertEquals(1, running.rejectedComputers)
        assertEquals(1, running.activeComputers)
        assertEquals(1, running.completedComputers)
        assertEquals(1, running.unavailableComputers)
        assertEquals(3_600L, running.expectedWorldRequests)
        assertEquals(20, running.elapsedTicks)

        pending.complete(true)
        pendingComputer.state = VmBenchmarkAreaComputerState.WAITING_FOR_INPUT
        active.state = VmBenchmarkAreaComputerState.WAITING_FOR_INPUT

        val finished = run.snapshot(40)

        assertEquals(VmBenchmarkAreaStatus.COMPLETED, finished.status)
        assertEquals(4, finished.acceptedComputers)
        assertEquals(3, finished.completedComputers)
        assertEquals(1, finished.unavailableComputers)
        assertEquals(4_800L, finished.expectedWorldRequests)
    }

    private fun dispatch(vararg deliveries: VmBenchmarkAreaDelivery): VmBenchmarkAreaDispatch =
        VmBenchmarkAreaDispatch(
            scannedPositions = deliveries.size,
            unloadedPositions = 0,
            discoveredComputers = deliveries.size,
            scheduledComputers = deliveries.size,
            limitedComputers = 0,
            completion = CompletableFuture(),
            deliveries = deliveries.toList(),
        )

    private class FakeComputer(
        var state: VmBenchmarkAreaComputerState = VmBenchmarkAreaComputerState.ACTIVE,
    ) : VmBenchmarkAreaComputer {
        override fun submit(command: String) = CompletableFuture.completedFuture(true)

        override fun benchmarkState(): VmBenchmarkAreaComputerState = state
    }
}
