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
import ru.lazyhat.compukters.core.device.runtime.program.SoundCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundRequest
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Owns runtime actors and is the only supported cross-thread entry point to their native sessions. */
class ProgramRuntimeActorService(
    config: VmActorSchedulerConfig = VmActorSchedulerConfig(),
) : AutoCloseable {
    private val scheduler =
        VmActorScheduler<ProgramRuntimeActorMessage, ProgramRuntimeTickPermit, ProgramRuntimeActorReply>(config)
    private val pending = ConcurrentHashMap<RequestAddress, PendingRequest>()
    private val deferredWorldRequests = ConcurrentHashMap.newKeySet<VmActorEndpoint>()
    private val nextRequestId = AtomicLong()
    private val totalDeferredWorldRequests = AtomicLong()
    private val hostContinuationSamples = AtomicLong()
    private val totalHostContinuationDelayTicks = AtomicLong()
    private val maximumHostContinuationDelayTicks = AtomicLong()
    private val rejectedInputRequests = AtomicLong()
    private val lastPumpEvents = AtomicInteger()
    private val lastPumpNanos = AtomicLong()

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
        val soundPort = ActorSoundHostPort()
        val host =
            ProgramRuntimeHost(
                store = store,
                computerId = endpoint.computerId,
                romImage = romImage,
                tickBudget = tickBudget,
                compilerRouter = compilerRouter,
                redstoneHostPort = port,
                soundHostPort = soundPort,
                initialRedstoneOutput = initialRedstoneOutput,
            )
        return attach(endpoint, host, port, soundPort)
    }

    internal fun attach(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
        port: ActorRedstoneHostPort? = null,
        soundPort: ActorSoundHostPort? = null,
    ): ProgramRuntimeActorLease? {
        val processor = ProgramRuntimeActorProcessor(host, port, soundPort)
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
        val pendingRequest = PendingRequest(future, completesWorldRequest = false)
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

    /** Atomically admits the world effects observed for [worldTick] and one bounded VM turn behind them. */
    fun turn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect> = emptyList(),
    ): CompletableFuture<ProgramRuntimeActorReply> {
        val requestId = ProgramRuntimeRequestId(nextRequestId.updateAndGet(::incrementRequestId))
        val address = RequestAddress(endpoint, requestId)
        val future = CompletableFuture<ProgramRuntimeActorReply>()
        val pendingRequest =
            PendingRequest(
                future,
                effects.any {
                    it is ProgramRuntimeActorEffect.CompleteRedstoneOutput || it is ProgramRuntimeActorEffect.CompleteSound
                },
            )
        check(pending.putIfAbsent(address, pendingRequest) == null) { "runtime request id collision" }
        val submission = scheduler.submitWithPermit(endpoint, effects, ProgramRuntimeTickPermit(requestId, worldTick))
        if (submission != VmActorSubmission.ACCEPTED) {
            pending.remove(address, pendingRequest)
            if (effects.any { it is ProgramRuntimeActorEffect.RedstoneInput }) rejectedInputRequests.incrementAndGet()
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
        val startedAt = System.nanoTime()
        val events = scheduler.drainEvents(maximumEvents)
        events.forEach { event ->
            when (event) {
                is VmActorEvent.Result -> {
                    val reply = event.value
                    val request = pending.remove(RequestAddress(event.endpoint, reply.requestId)) ?: return@forEach
                    if (request.completesWorldRequest) deferredWorldRequests.remove(event.endpoint)
                    if (
                        (
                            reply.value is ProgramRuntimeActorValue.RedstoneOutputRequested ||
                                reply.value is ProgramRuntimeActorValue.SoundRequested
                        ) &&
                        deferredWorldRequests.add(event.endpoint)
                    ) {
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
        lastPumpEvents.set(events.size)
        lastPumpNanos.set((System.nanoTime() - startedAt).coerceAtLeast(0))
        return events.size
    }

    fun metrics(): VmActorSchedulerMetrics = scheduler.metrics()

    internal fun recordHostContinuationDelay(delayTicks: Long) {
        require(delayTicks >= 0) { "host continuation delay must not be negative" }
        hostContinuationSamples.incrementAndGet()
        totalHostContinuationDelayTicks.addAndGet(delayTicks)
        maximumHostContinuationDelayTicks.accumulateAndGet(delayTicks, ::maxOf)
    }

    fun runtimeMetrics(): ProgramRuntimeActorMetrics =
        ProgramRuntimeActorMetrics(
            scheduler = scheduler.metrics(),
            pendingRequests = pending.size,
            deferredWorldRequests = deferredWorldRequests.size,
            totalDeferredWorldRequests = totalDeferredWorldRequests.get(),
            hostContinuationSamples = hostContinuationSamples.get(),
            totalHostContinuationDelayTicks = totalHostContinuationDelayTicks.get(),
            maximumHostContinuationDelayTicks = maximumHostContinuationDelayTicks.get(),
            rejectedInputRequests = rejectedInputRequests.get(),
            lastPumpEvents = lastPumpEvents.get(),
            lastPumpNanos = lastPumpNanos.get(),
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
                this is ProgramRuntimeActorCommand.SubmitCanonicalLine
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

internal class ActorSoundHostPort : SoundHostPort {
    private var requestedSounds: List<SoundRequest>? = null

    override fun emit(requests: List<SoundRequest>): SoundCommitResult {
        check(requestedSounds == null) { "sound actor already owns a deferred request batch" }
        requestedSounds = requests.toList()
        return SoundCommitResult.Deferred
    }

    fun takeRequestedSounds(): List<SoundRequest>? = requestedSounds.also { requestedSounds = null }
}
