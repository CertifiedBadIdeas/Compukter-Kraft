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

package ru.lazyhat.compukters.core.device.runtime.program.integration

import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeRequestId
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEvent
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProgramRuntimeActorIntegrationTest {
    @Test
    fun `compiled Kotlin artifact runs through a worker-owned actor`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(30, 40), 1)
        var nextRequestId = 0L
        ProgramRuntimeActorService(
            VmActorSchedulerConfig(
                workerCount = 1,
                maximumActors = 1,
                mailboxCapacity = 8,
                messagesPerTurn = 1,
                resultCapacityPerWorker = 8,
            ),
        ).use { scheduler ->
            assertEquals(true, scheduler.registerStandalone(endpoint, ProgramTickBudget(64, 64, 4)))
            val started =
                scheduler.request(
                    endpoint,
                    ProgramRuntimeActorCommand.Start(ProgramRuntimeRequestId(++nextRequestId), artifact),
                )
            assertEquals(ProgramStartResult.Started, assertIs<ProgramRuntimeActorValue.Start>(started.value).result)

            var state = started.state
            var worldTick = 0L
            while (state != ProgramRuntimeState.WaitingForInput && worldTick < MAXIMUM_TICKS) {
                val advanced =
                    scheduler.request(
                        endpoint,
                        ProgramRuntimeActorCommand.Advance(
                            ProgramRuntimeRequestId(++nextRequestId),
                            worldTick++,
                        ),
                    )
                state = advanced.state
            }
            assertEquals(ProgramRuntimeState.WaitingForInput, state)

            val terminal =
                scheduler.request(
                    endpoint,
                    ProgramRuntimeActorCommand.TerminalFullState(ProgramRuntimeRequestId(++nextRequestId)),
                )
            assertEquals(">\n", terminalText(requireNotNull(assertIs<ProgramRuntimeActorValue.TerminalStateValue>(terminal.value).state)))
            assertEquals(true, scheduler.unregister(endpoint).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun ProgramRuntimeActorService.request(
        endpoint: VmActorEndpoint,
        command: ProgramRuntimeActorCommand,
    ): ProgramRuntimeActorReply {
        assertEquals(VmActorSubmission.ACCEPTED, submit(endpoint, command))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            drainEvents(1).singleOrNull()?.let { event ->
                return assertIs<VmActorEvent.Result<ProgramRuntimeActorReply>>(event).value
            }
            Thread.onSpinWait()
        }
        error("runtime actor reply timed out")
    }

    private fun terminalText(state: TerminalState): String =
        buildString {
            repeat(state.height) { y ->
                val row = StringBuilder()
                repeat(state.width) { x -> row.appendCodePoint(state.cells[y * state.width + x].codePoint) }
                append(row.toString().trimEnd()).append('\n')
            }
        }.trimEnd('\n') + '\n'

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "missing required system property $name" }

    private companion object {
        const val MAXIMUM_TICKS = 10_000L
        const val TIMEOUT_SECONDS = 5L
    }
}
