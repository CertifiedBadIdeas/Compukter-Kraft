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

import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorMetrics
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerMetrics
import ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HeadlessVmBenchmarkFleetTest {
    @Test
    fun `fleet admits available actors and records completion without owner thread execution`() {
        val runtime = FakeRuntime(capacity = 2)
        val fleet = HeadlessVmBenchmarkFleet(runtime, byteArrayOf(1), maximumActors = 3)

        assertEquals(HeadlessVmBenchmarkStart(3, 2), fleet.start(3, 7, 10))
        assertEquals(listOf(7, 7), runtime.actors.map { it.rounds })
        assertEquals(2, fleet.snapshot(1.25).activeActors)

        fleet.tick(11)
        runtime.actors[0]
            .advances
            .single()
            .complete(ProgramRuntimeState.Halted(null))
        runtime.actors[1]
            .advances
            .single()
            .complete(ProgramRuntimeState.Failed(ProgramFailure.Bridge("failure")))

        val snapshot = fleet.snapshot(1.5)
        assertEquals(HeadlessVmBenchmarkStatus.COMPLETED, snapshot.status)
        assertEquals(1, snapshot.completedActors)
        assertEquals(1, snapshot.failedActors)
        assertEquals(0, snapshot.activeActors)
        assertEquals(1, snapshot.elapsedTicks)
        assertEquals(1, runtime.actors[0].closeCalls)
        assertEquals(1, runtime.actors[1].closeCalls)
    }

    @Test
    fun `stop is nonblocking idempotent and prevents overlapping active runs`() {
        val runtime = FakeRuntime(capacity = 2, delayedClose = true)
        val fleet = HeadlessVmBenchmarkFleet(runtime, byteArrayOf(1), maximumActors = 2)
        fleet.start(2, 3, 4)
        assertFailsWith<IllegalStateException> { fleet.start(1, 1, 4) }

        assertEquals(2, fleet.stop())
        assertEquals(2, fleet.stop())
        assertEquals(HeadlessVmBenchmarkStatus.STOPPING, fleet.snapshot(0.5).status)
        assertEquals(listOf(1, 1), runtime.actors.map { it.closeCalls })

        runtime.actors.forEach { it.close.complete(Unit) }
        assertEquals(HeadlessVmBenchmarkStatus.STOPPED, fleet.snapshot(0.5).status)
        assertEquals(HeadlessVmBenchmarkStart(1, 1), fleet.start(1, 1, 5))
    }

    private class FakeRuntime(
        private val capacity: Int,
        private val delayedClose: Boolean = false,
    ) : HeadlessVmBenchmarkRuntime {
        val actors = mutableListOf<FakeActor>()

        override fun attach(endpoint: VmActorEndpoint): HeadlessVmBenchmarkActor? {
            if (actors.count { !it.closed } >= capacity) return null
            return FakeActor(delayedClose).also(actors::add)
        }

        override fun metrics(): ProgramRuntimeActorMetrics = metrics(actors.count { !it.closed })
    }

    private class FakeActor(
        delayedClose: Boolean,
    ) : HeadlessVmBenchmarkActor {
        val advances = mutableListOf<CompletableFuture<ProgramRuntimeState>>()
        val close = if (delayedClose) CompletableFuture<Unit>() else CompletableFuture.completedFuture(Unit)
        var rounds = 0
        var closeCalls = 0
        var closed = false

        override fun start(artifact: ByteArray): CompletableFuture<ProgramRuntimeState> =
            CompletableFuture.completedFuture(ProgramRuntimeState.Running)

        override fun sendRounds(rounds: Int): CompletableFuture<Boolean> {
            this.rounds = rounds
            return CompletableFuture.completedFuture(true)
        }

        override fun advance(worldTick: Long): CompletableFuture<ProgramRuntimeState> =
            CompletableFuture<ProgramRuntimeState>().also(advances::add)

        override fun closeAsync(): CompletableFuture<*> {
            closeCalls++
            closed = true
            return close
        }
    }

    private companion object {
        fun metrics(registered: Int) =
            ProgramRuntimeActorMetrics(
                scheduler =
                    VmActorSchedulerMetrics(
                        registeredActors = registered,
                        scheduledActors = 0,
                        queuedMessages = 0,
                        busyWorkers = 0,
                        queuedResults = 0,
                        acceptedMessages = 0,
                        processedMessages = 0,
                        totalQueueLatencyNanos = 0,
                        maximumQueueLatencyNanos = 0,
                        totalExecutionNanos = 0,
                        maximumExecutionNanos = 0,
                        mailboxFullRejections = 0,
                        staleEndpointRejections = 0,
                        closedRejections = 0,
                    ),
                pendingRequests = 0,
                deferredWorldRequests = 0,
                totalDeferredWorldRequests = 0,
                rejectedInputRequests = 0,
            )
    }
}
