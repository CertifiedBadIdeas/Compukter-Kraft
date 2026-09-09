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

import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.util.concurrent.CompletableFuture

/** Owns runtime actors and is the only supported cross-thread entry point to their native sessions. */
class ProgramRuntimeActorService(
    config: VmActorSchedulerConfig = VmActorSchedulerConfig(),
) : AutoCloseable {
    private val scheduler = VmActorScheduler<ProgramRuntimeActorCommand, ProgramRuntimeActorReply>(config)

    fun registerStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
    ): Boolean = register(endpoint, ProgramRuntimeHost(tickBudget))

    fun registerBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        initialRedstoneOutput: Int = 0,
    ): Boolean =
        register(
            endpoint,
            ProgramRuntimeHost(
                store = store,
                computerId = endpoint.computerId,
                romImage = romImage,
                tickBudget = tickBudget,
                compilerRouter = compilerRouter,
                initialRedstoneOutput = initialRedstoneOutput,
            ),
        )

    fun submit(
        endpoint: VmActorEndpoint,
        command: ProgramRuntimeActorCommand,
    ): VmActorSubmission = scheduler.submit(endpoint, command)

    fun unregister(endpoint: VmActorEndpoint): CompletableFuture<Boolean> = scheduler.unregister(endpoint)

    fun drainEvents(maximumEvents: Int): List<VmActorEvent<ProgramRuntimeActorReply>> = scheduler.drainEvents(maximumEvents)

    fun metrics(): VmActorSchedulerMetrics = scheduler.metrics()

    override fun close() = scheduler.close()

    private fun register(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
    ): Boolean = scheduler.register(endpoint, ProgramRuntimeActorProcessor(host))
}
