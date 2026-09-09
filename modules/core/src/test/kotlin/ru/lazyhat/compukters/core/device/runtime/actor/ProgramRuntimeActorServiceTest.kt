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

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramRuntimeActorServiceTest {
    @Test
    fun `accepted request completes only when the server pumps its result`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, 2), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            assertTrue(service.registerStandalone(endpoint))

            val future =
                service.request(endpoint) { requestId ->
                    ProgramRuntimeActorCommand.TerminalFullState(requestId)
                }
            awaitQueuedResult(service)

            assertFalse(future.isDone)
            assertEquals(1, service.pump(1))
            val reply = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNull(assertIs<ProgramRuntimeActorValue.TerminalStateValue>(reply.value).state)
        }
    }

    @Test
    fun `stale endpoint fails without waiting for a result`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(3, 4), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            val future =
                service.request(endpoint) { requestId ->
                    ProgramRuntimeActorCommand.TerminalFullState(requestId)
                }

            val failure =
                assertFailsWith<ExecutionException> {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            assertEquals(
                VmActorSubmission.STALE_ENDPOINT,
                assertIs<ProgramRuntimeActorRequestException>(failure.cause).submission,
            )

            val rejectedInput =
                service.request(endpoint) { requestId ->
                    ProgramRuntimeActorCommand.SendTerminalText(requestId, "input")
                }
            assertFailsWith<ExecutionException> {
                rejectedInput.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            assertEquals(1, service.runtimeMetrics().rejectedInputRequests)
            assertEquals(2, service.metrics().staleEndpointRejections)
        }
    }

    @Test
    fun `command factory must preserve the allocated request id`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(5, 6), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            assertFailsWith<IllegalArgumentException> {
                service.request(endpoint) {
                    ProgramRuntimeActorCommand.TerminalFullState(ProgramRuntimeRequestId(it.value + 1))
                }
            }
        }
    }

    private fun awaitQueuedResult(service: ProgramRuntimeActorService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (service.metrics().queuedResults == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(service.metrics().queuedResults > 0, "runtime actor result was not queued")
    }

    private fun testConfig(): VmActorSchedulerConfig =
        VmActorSchedulerConfig(
            workerCount = 1,
            maximumActors = 1,
            mailboxCapacity = 4,
            messagesPerTurn = 1,
            resultCapacityPerWorker = 4,
        )

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
