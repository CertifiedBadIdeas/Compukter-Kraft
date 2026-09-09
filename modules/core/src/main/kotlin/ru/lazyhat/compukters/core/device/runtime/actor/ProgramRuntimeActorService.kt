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
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Owns runtime actors and is the only supported cross-thread entry point to their native sessions. */
class ProgramRuntimeActorService(
    config: VmActorSchedulerConfig = VmActorSchedulerConfig(),
) : AutoCloseable {
    private val scheduler = VmActorScheduler<ProgramRuntimeActorCommand, ProgramRuntimeActorReply>(config)
    private val pending = ConcurrentHashMap<RequestAddress, PendingRequest>()
    private val deferredWorldRequests = ConcurrentHashMap.newKeySet<VmActorEndpoint>()
    private val nextRequestId = AtomicLong()
    private val totalDeferredWorldRequests = AtomicLong()
    private val rejectedInputRequests = AtomicLong()

    fun registerStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
    ): Boolean = attachStandalone(endpoint, tickBudget) != null

    fun attachStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
    ): ProgramRuntimeActorLease? = attach(endpoint, ProgramRuntimeHost(tickBudget))

    fun registerBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        initialRedstoneOutput: Int = 0,
    ): Boolean = attachBootable(endpoint, store, romImage, tickBudget, compilerRouter, initialRedstoneOutput) != null

    fun attachBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        initialRedstoneOutput: Int = 0,
    ): ProgramRuntimeActorLease? {
        val port = ActorRedstoneHostPort()
        val host =
            ProgramRuntimeHost(
                store = store,
                computerId = endpoint.computerId,
                romImage = romImage,
                tickBudget = tickBudget,
                compilerRouter = compilerRouter,
                redstoneHostPort = port,
                initialRedstoneOutput = initialRedstoneOutput,
            )
        return attach(endpoint, host, port)
    }

    internal fun attach(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
        port: ActorRedstoneHostPort? = null,
    ): ProgramRuntimeActorLease? {
        val processor = ProgramRuntimeActorProcessor(host, port)
        if (!scheduler.register(endpoint, processor)) return null
        return ProgramRuntimeActorLease(endpoint, processor.closed) { scheduler.unregister(endpoint) }
    }

    fun request(
        endpoint: VmActorEndpoint,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        val requestId = ProgramRuntimeRequestId(nextRequestId.updateAndGet(::incrementRequestId))
        val preparedCommand = command(requestId)
        require(preparedCommand.requestId == requestId) { "runtime command factory returned a mismatched request id" }
        val address = RequestAddress(endpoint, requestId)
        val future = CompletableFuture<ProgramRuntimeActorReply>()
        val pendingRequest = PendingRequest(future, preparedCommand is ProgramRuntimeActorCommand.CompleteRedstoneOutput)
        check(pending.putIfAbsent(address, pendingRequest) == null) { "runtime request id collision" }
        val submission = scheduler.submit(endpoint, preparedCommand)
        if (submission != VmActorSubmission.ACCEPTED) {
            pending.remove(address, pendingRequest)
            if (preparedCommand.isInput()) rejectedInputRequests.incrementAndGet()
            future.completeExceptionally(ProgramRuntimeActorRequestException(submission))
        } else {
            future.whenComplete { _, _ ->
                if (future.isCancelled) pending.remove(address, pendingRequest)
            }
        }
        return future
    }

    fun unregister(endpoint: VmActorEndpoint): CompletableFuture<Boolean> =
        scheduler.unregister(endpoint).whenComplete { _, _ -> deferredWorldRequests.remove(endpoint) }

    fun pump(maximumEvents: Int): Int {
        val events = scheduler.drainEvents(maximumEvents)
        events.forEach { event ->
            when (event) {
                is VmActorEvent.Result -> {
                    val reply = event.value
                    val request = pending.remove(RequestAddress(event.endpoint, reply.requestId)) ?: return@forEach
                    if (request.completesWorldRequest) deferredWorldRequests.remove(event.endpoint)
                    if (reply.value is ProgramRuntimeActorValue.RedstoneOutputRequested && deferredWorldRequests.add(event.endpoint)) {
                        totalDeferredWorldRequests.incrementAndGet()
                    }
                    request.future.complete(reply)
                }

                is VmActorEvent.Failed -> {
                    val failure = ProgramRuntimeActorFailedException(event.endpoint, event.cause)
                    deferredWorldRequests.remove(event.endpoint)
                    pending.entries.removeIf { (address, request) ->
                        if (address.endpoint != event.endpoint) return@removeIf false
                        request.future.completeExceptionally(failure)
                        true
                    }
                }
            }
        }
        return events.size
    }

    fun metrics(): VmActorSchedulerMetrics = scheduler.metrics()

    fun runtimeMetrics(): ProgramRuntimeActorMetrics =
        ProgramRuntimeActorMetrics(
            scheduler = scheduler.metrics(),
            pendingRequests = pending.size,
            deferredWorldRequests = deferredWorldRequests.size,
            totalDeferredWorldRequests = totalDeferredWorldRequests.get(),
            rejectedInputRequests = rejectedInputRequests.get(),
        )

    override fun close() {
        scheduler.close()
        val failure = ProgramRuntimeActorServiceClosedException()
        pending.values.forEach { it.future.completeExceptionally(failure) }
        pending.clear()
        deferredWorldRequests.clear()
    }

    private data class RequestAddress(
        val endpoint: VmActorEndpoint,
        val requestId: ProgramRuntimeRequestId,
    )

    private data class PendingRequest(
        val future: CompletableFuture<ProgramRuntimeActorReply>,
        val completesWorldRequest: Boolean,
    )

    private companion object {
        fun incrementRequestId(previous: Long): Long = if (previous == Long.MAX_VALUE) 1 else previous + 1

        fun ProgramRuntimeActorCommand.isInput(): Boolean =
            this is ProgramRuntimeActorCommand.SendTerminalKey ||
                this is ProgramRuntimeActorCommand.SendTerminalText ||
                this is ProgramRuntimeActorCommand.SubmitCanonicalLine ||
                this is ProgramRuntimeActorCommand.SubmitRedstoneInput ||
                this is ProgramRuntimeActorCommand.CompleteRedstoneOutput
    }
}

class ProgramRuntimeActorRequestException(
    val submission: VmActorSubmission,
) : IllegalStateException("runtime actor request was not accepted: $submission")

class ProgramRuntimeActorFailedException(
    val endpoint: VmActorEndpoint,
    cause: Throwable,
) : IllegalStateException("runtime actor failed: $endpoint", cause)

class ProgramRuntimeActorServiceClosedException : IllegalStateException("runtime actor service is closed")

internal class ActorRedstoneHostPort : RedstoneHostPort {
    private var requestedOutput: Int? = null

    override fun commitOutput(packed: Int): RedstoneCommitResult {
        check(requestedOutput == null) { "redstone actor already owns a deferred output request" }
        requestedOutput = packed
        return RedstoneCommitResult.Deferred
    }

    fun takeRequestedOutput(): Int? = requestedOutput.also { requestedOutput = null }
}
