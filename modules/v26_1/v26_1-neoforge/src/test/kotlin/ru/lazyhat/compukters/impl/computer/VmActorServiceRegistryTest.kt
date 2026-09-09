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

package ru.lazyhat.compukters.impl.computer

import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VmActorServiceRegistryTest {
    @Test
    fun `idle server ticks allocate no worker service and stop forbids reopening`() {
        var opened = 0
        val registry =
            VmActorServiceRegistry<Any>({}, {
                opened++
                service()
            })
        val server = Any()
        registry.start(server)
        repeat(10) { assertEquals(0, registry.tick(server)) }
        assertEquals(0, opened)
        registry.stop(server)
        registry.stop(server)
        assertFailsWith<IllegalStateException> { registry.service(server) }
        assertEquals(0, opened)
    }

    @Test
    fun `each server owns one service and ticks deliver at most the configured budget`() {
        val owner = Thread.currentThread()
        val registry = VmActorServiceRegistry<Any>({ check(Thread.currentThread() === owner) }, ::service, 1)
        val first = Any()
        val second = Any()
        registry.start(first)
        registry.start(second)
        try {
            val runtime = registry.service(first)
            assertSame(runtime, registry.service(first))
            val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, 1), 1)
            assertTrue(runtime.registerStandalone(endpoint))
            val firstReply = runtime.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)
            val secondReply = runtime.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)
            var callbackThread: Thread? = null
            firstReply.thenRun { callbackThread = Thread.currentThread() }
            awaitResults(runtime, 2)
            assertFalse(firstReply.isDone)
            assertEquals(0, registry.tick(second))
            assertEquals(1, registry.tick(first))
            assertTrue(firstReply.isDone)
            assertFalse(secondReply.isDone)
            assertSame(owner, callbackThread)
            assertEquals(1, registry.tick(first))
            assertTrue(secondReply.isDone)
        } finally {
            registry.stop(first)
            registry.stop(second)
        }
        assertFailsWith<IllegalStateException> { registry.service(first) }
    }

    @Test
    fun `stop completes pending requests and rejects callback reopening`() {
        val registry = VmActorServiceRegistry<Any>({}, ::service)
        val server = Any()
        registry.start(server)
        val runtime = registry.service(server)
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(2, 2), 1)
        assertTrue(runtime.registerStandalone(endpoint))
        val reply = runtime.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)
        awaitResults(runtime, 1)
        var reopenRejected = false
        reply.whenComplete { _, _ ->
            reopenRejected = runCatching { registry.service(server) }.isFailure
        }
        registry.stop(server)
        assertTrue(reply.isCompletedExceptionally)
        assertTrue(reopenRejected)
        assertEquals(0, runtime.metrics().registeredActors)
    }

    private fun service() =
        ProgramRuntimeActorService(
            VmActorSchedulerConfig(workerCount = 1, resultCapacityPerWorker = 4),
        )

    private fun awaitResults(
        service: ProgramRuntimeActorService,
        count: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (service.metrics().queuedResults < count && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals(count, service.metrics().queuedResults)
    }
}
