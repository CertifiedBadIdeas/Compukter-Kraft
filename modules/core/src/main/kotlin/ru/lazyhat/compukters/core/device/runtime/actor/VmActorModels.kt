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

data class VmActorEndpoint(
    val computerId: ComputerId,
    val epoch: Long,
) {
    init {
        require(epoch > 0) { "VM actor epoch must be positive" }
    }
}

enum class VmActorSubmission {
    ACCEPTED,
    MAILBOX_FULL,
    STALE_ENDPOINT,
    CLOSED,
}

sealed interface VmActorEvent<out R : Any> {
    val endpoint: VmActorEndpoint

    data class Result<R : Any>(
        override val endpoint: VmActorEndpoint,
        val sequence: Long,
        val value: R,
    ) : VmActorEvent<R>

    data class Failed(
        override val endpoint: VmActorEndpoint,
        val cause: Throwable,
    ) : VmActorEvent<Nothing>
}

data class VmActorSchedulerConfig(
    val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val maximumActors: Int = 1_024,
    val mailboxCapacity: Int = 64,
    val messagesPerTurn: Int = 4,
    val resultCapacityPerWorker: Int = 256,
    val idlePollMillis: Long = 25,
    val shutdownTimeoutMillis: Long = 5_000,
) {
    init {
        require(workerCount > 0) { "worker count must be positive" }
        require(maximumActors > 0) { "maximum actors must be positive" }
        require(mailboxCapacity > 0) { "mailbox capacity must be positive" }
        require(messagesPerTurn > 0) { "messages per turn must be positive" }
        require(resultCapacityPerWorker > 0) { "result capacity must be positive" }
        require(idlePollMillis > 0) { "idle poll duration must be positive" }
        require(shutdownTimeoutMillis > 0) { "shutdown timeout must be positive" }
    }
}

data class VmActorSchedulerMetrics(
    val registeredActors: Int,
    val scheduledActors: Int,
    val queuedMessages: Int,
    val busyWorkers: Int,
    val queuedResults: Int,
    val acceptedMessages: Long,
    val processedMessages: Long,
    val totalQueueLatencyNanos: Long,
    val maximumQueueLatencyNanos: Long,
    val totalExecutionNanos: Long,
    val maximumExecutionNanos: Long,
    val mailboxFullRejections: Long,
    val staleEndpointRejections: Long,
    val closedRejections: Long,
)

data class ProgramRuntimeActorMetrics(
    val scheduler: VmActorSchedulerMetrics,
    val pendingRequests: Int,
    val deferredWorldRequests: Int,
    val totalDeferredWorldRequests: Long,
    val rejectedInputRequests: Long,
)

interface VmActorProcessor<in C : Any, out R : Any> : AutoCloseable {
    fun process(command: C): R?

    override fun close() = Unit
}
