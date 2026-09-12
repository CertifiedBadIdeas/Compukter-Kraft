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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs independently addressed stateful processors without ever executing one processor concurrently.
 *
 * Commands and results cross thread boundaries and must therefore be immutable or privately owned by the receiver.
 * An accepted command remains owned by this scheduler until it is processed, unless its processor itself fails.
 */
class VmActorScheduler<C : Any, P : Any, R : Any>(
    private val config: VmActorSchedulerConfig = VmActorSchedulerConfig(),
) : AutoCloseable {
    private val registryLock = Any()
    private val actors = ConcurrentHashMap<ComputerId, ActorCell<C, P, R>>()
    private val readyLanes =
        List(config.workerCount) {
            ArrayBlockingQueue<ActorCell<C, P, R>>(config.maximumActors)
        }
    private val resultLanes =
        // Keep an actor's results ordered even when a different worker steals its next turn.
        List(config.workerCount) {
            ArrayBlockingQueue<QueuedEvent<R>>(config.resultCapacityPerWorker)
        }
    private val accepting = AtomicBoolean(true)
    private val workersRunning = AtomicBoolean(true)
    private val registeredActors = AtomicInteger()
    private val scheduledActors = AtomicInteger()
    private val queuedMessages = AtomicInteger()
    private val pendingPermits = AtomicInteger()
    private val busyWorkers = AtomicInteger()
    private val acceptedMessages = AtomicLong()
    private val processedMessages = AtomicLong()
    private val acceptedPermits = AtomicLong()
    private val processedPermits = AtomicLong()
    private val totalQueueLatencyNanos = AtomicLong()
    private val maximumQueueLatencyNanos = AtomicLong()
    private val totalExecutionNanos = AtomicLong()
    private val maximumExecutionNanos = AtomicLong()
    private val mailboxFullRejections = AtomicLong()
    private val permitPendingRejections = AtomicLong()
    private val staleEndpointRejections = AtomicLong()
    private val closedRejections = AtomicLong()
    private val drainedEvents = AtomicLong()
    private val totalResultLatencyNanos = AtomicLong()
    private val maximumResultLatencyNanos = AtomicLong()
    private val drainCursor = AtomicInteger()
    private val workers =
        List(config.workerCount) { index ->
            Thread
                .ofPlatform()
                .daemon(true)
                .name("compukters-vm-worker-$index")
                .unstarted { workerLoop(index) }
                .also(Thread::start)
        }

    fun register(
        endpoint: VmActorEndpoint,
        processor: VmActorProcessor<C, P, R>,
    ): Boolean =
        synchronized(registryLock) {
            if (!accepting.get() || registeredActors.get() >= config.maximumActors) return@synchronized false
            if (actors.containsKey(endpoint.computerId)) return@synchronized false
            val homeLane = Math.floorMod(endpoint.computerId.hashCode(), config.workerCount)
            actors[endpoint.computerId] = ActorCell(endpoint, processor, homeLane)
            registeredActors.incrementAndGet()
            true
        }

    fun submit(
        endpoint: VmActorEndpoint,
        command: C,
    ): VmActorSubmission {
        if (!accepting.get()) {
            closedRejections.incrementAndGet()
            return VmActorSubmission.CLOSED
        }
        val actor =
            actors[endpoint.computerId] ?: run {
                staleEndpointRejections.incrementAndGet()
                return VmActorSubmission.STALE_ENDPOINT
            }
        var schedule = false
        synchronized(actor.lock) {
            if (actor.endpoint != endpoint || actor.closing || actor.closed) {
                staleEndpointRejections.incrementAndGet()
                return VmActorSubmission.STALE_ENDPOINT
            }
            if (actor.mailbox.size >= config.mailboxCapacity) {
                mailboxFullRejections.incrementAndGet()
                return VmActorSubmission.MAILBOX_FULL
            }
            actor.mailbox.addLast(QueuedCommand(++actor.mailboxSequence, command, System.nanoTime()))
            queuedMessages.incrementAndGet()
            acceptedMessages.incrementAndGet()
            if (!actor.scheduled) {
                actor.scheduled = true
                scheduledActors.incrementAndGet()
                schedule = true
            }
        }
        if (schedule) enqueue(actor)
        return VmActorSubmission.ACCEPTED
    }

    /**
     * Atomically accepts [commands] and one execution [permit]. The permit runs after every mailbox entry accepted
     * through this call and before any entry accepted later.
     */
    fun submitWithPermit(
        endpoint: VmActorEndpoint,
        commands: List<C>,
        permit: P,
    ): VmActorSubmission {
        if (!accepting.get()) {
            closedRejections.incrementAndGet()
            return VmActorSubmission.CLOSED
        }
        val actor =
            actors[endpoint.computerId] ?: run {
                staleEndpointRejections.incrementAndGet()
                return VmActorSubmission.STALE_ENDPOINT
            }
        var schedule = false
        synchronized(actor.lock) {
            if (actor.endpoint != endpoint || actor.closing || actor.closed) {
                staleEndpointRejections.incrementAndGet()
                return VmActorSubmission.STALE_ENDPOINT
            }
            if (actor.pendingPermit != null) {
                permitPendingRejections.incrementAndGet()
                return VmActorSubmission.PERMIT_PENDING
            }
            if (commands.size > config.mailboxCapacity - actor.mailbox.size) {
                mailboxFullRejections.incrementAndGet()
                return VmActorSubmission.MAILBOX_FULL
            }
            val enqueuedAt = System.nanoTime()
            commands.forEach { command ->
                actor.mailbox.addLast(QueuedCommand(++actor.mailboxSequence, command, enqueuedAt))
            }
            actor.pendingPermit = QueuedPermit(actor.mailboxSequence, permit, enqueuedAt)
            queuedMessages.addAndGet(commands.size)
            pendingPermits.incrementAndGet()
            acceptedMessages.addAndGet(commands.size.toLong())
            acceptedPermits.incrementAndGet()
            if (!actor.scheduled) {
                actor.scheduled = true
                scheduledActors.incrementAndGet()
                schedule = true
            }
        }
        if (schedule) enqueue(actor)
        return VmActorSubmission.ACCEPTED
    }

    fun unregister(endpoint: VmActorEndpoint): CompletableFuture<Boolean> {
        val actor = actors[endpoint.computerId] ?: return CompletableFuture.completedFuture(false)
        var schedule = false
        synchronized(actor.lock) {
            if (actor.endpoint != endpoint || actor.closed) return CompletableFuture.completedFuture(false)
            if (!actor.closing) {
                actor.closing = true
                if (!actor.scheduled) {
                    actor.scheduled = true
                    scheduledActors.incrementAndGet()
                    schedule = true
                }
            }
        }
        if (schedule) enqueue(actor)
        return actor.closeBarrier
    }

    fun drainEvents(maximumEvents: Int): List<VmActorEvent<R>> {
        require(maximumEvents >= 0) { "maximum events must not be negative" }
        if (maximumEvents == 0) return emptyList()
        val drained = ArrayList<VmActorEvent<R>>(maximumEvents)
        var emptyLanes = 0
        var laneIndex = Math.floorMod(drainCursor.getAndIncrement(), resultLanes.size)
        var latencyTotal = 0L
        var latencyMaximum = 0L
        while (drained.size < maximumEvents && emptyLanes < resultLanes.size) {
            val queued = resultLanes[laneIndex].poll()
            if (queued == null) {
                emptyLanes++
            } else {
                val latency = (System.nanoTime() - queued.completedAtNanos).coerceAtLeast(0)
                latencyTotal += latency
                latencyMaximum = maxOf(latencyMaximum, latency)
                drained += queued.event
                emptyLanes = 0
            }
            laneIndex = (laneIndex + 1) % resultLanes.size
        }
        drainedEvents.addAndGet(drained.size.toLong())
        totalResultLatencyNanos.addAndGet(latencyTotal)
        maximumResultLatencyNanos.accumulateAndGet(latencyMaximum) { previous, current -> maxOf(previous, current) }
        return drained
    }

    fun metrics(): VmActorSchedulerMetrics =
        VmActorSchedulerMetrics(
            maximumActors = config.maximumActors,
            registeredActors = registeredActors.get(),
            scheduledActors = scheduledActors.get(),
            queuedMessages = queuedMessages.get(),
            pendingPermits = pendingPermits.get(),
            busyWorkers = busyWorkers.get(),
            queuedResults = resultLanes.sumOf { it.size },
            acceptedMessages = acceptedMessages.get(),
            processedMessages = processedMessages.get(),
            acceptedPermits = acceptedPermits.get(),
            processedPermits = processedPermits.get(),
            totalQueueLatencyNanos = totalQueueLatencyNanos.get(),
            maximumQueueLatencyNanos = maximumQueueLatencyNanos.get(),
            totalExecutionNanos = totalExecutionNanos.get(),
            maximumExecutionNanos = maximumExecutionNanos.get(),
            mailboxFullRejections = mailboxFullRejections.get(),
            permitPendingRejections = permitPendingRejections.get(),
            staleEndpointRejections = staleEndpointRejections.get(),
            closedRejections = closedRejections.get(),
            drainedEvents = drainedEvents.get(),
            totalResultLatencyNanos = totalResultLatencyNanos.get(),
            maximumResultLatencyNanos = maximumResultLatencyNanos.get(),
        )

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        val barriers = actors.values.map { requestClose(it) }
        resultLanes.forEach(ArrayBlockingQueue<QueuedEvent<R>>::clear)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownTimeoutMillis)
        try {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            CompletableFuture
                .allOf(*barriers.toTypedArray())
                .get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            // Worker interruption below is the bounded fallback for a processor that ignored its turn budget.
        } catch (_: Exception) {
            // An individual actor failure has already been isolated and reported through its barrier/event.
        } finally {
            workersRunning.set(false)
            workers.forEach(Thread::interrupt)
            workers.forEach { worker ->
                val remainingMillis =
                    TimeUnit.NANOSECONDS
                        .toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
                if (remainingMillis > 0) worker.join(remainingMillis)
            }
        }
    }

    private fun requestClose(actor: ActorCell<C, P, R>): CompletableFuture<Boolean> {
        var schedule = false
        synchronized(actor.lock) {
            if (!actor.closing && !actor.closed) {
                actor.closing = true
                if (!actor.scheduled) {
                    actor.scheduled = true
                    scheduledActors.incrementAndGet()
                    schedule = true
                }
            }
        }
        if (schedule) enqueue(actor)
        return actor.closeBarrier
    }

    private fun workerLoop(workerIndex: Int) {
        while (workersRunning.get()) {
            try {
                val actor = nextActor(workerIndex) ?: continue
                busyWorkers.incrementAndGet()
                try {
                    runTurn(actor)
                } finally {
                    busyWorkers.decrementAndGet()
                }
            } catch (_: InterruptedException) {
                if (!workersRunning.get()) return
            }
        }
    }

    private fun nextActor(workerIndex: Int): ActorCell<C, P, R>? {
        readyLanes[workerIndex].poll()?.let { return it }
        repeat(readyLanes.size - 1) { offset ->
            val lane = (workerIndex + offset + 1) % readyLanes.size
            readyLanes[lane].poll()?.let { return it }
        }
        return readyLanes[workerIndex].poll(config.idlePollMillis, TimeUnit.MILLISECONDS)
    }

    private fun runTurn(actor: ActorCell<C, P, R>) {
        repeat(config.messagesPerTurn) {
            val action =
                synchronized(actor.lock) {
                    val command = actor.mailbox.firstOrNull()
                    val permit = actor.pendingPermit
                    if (command != null && (permit == null || command.sequence <= permit.fenceSequence)) {
                        actor.mailbox.removeFirst()
                        queuedMessages.decrementAndGet()
                        ActorAction(command = command)
                    } else if (permit != null && !permit.executing) {
                        permit.executing = true
                        ActorAction(permit = permit)
                    } else {
                        null
                    }
                } ?: return@repeat
            val queueLatency = (System.nanoTime() - action.enqueuedAtNanos).coerceAtLeast(0)
            totalQueueLatencyNanos.addAndGet(queueLatency)
            maximumQueueLatencyNanos.accumulateAndGet(queueLatency) { previous, current -> maxOf(previous, current) }
            val startedAt = System.nanoTime()
            val result =
                try {
                    val command = action.command
                    if (command != null) {
                        actor.processor.process(command.command)
                    } else {
                        actor.processor.advance(requireNotNull(action.permit).permit)
                    }
                } catch (cause: Throwable) {
                    failActor(actor, cause)
                    return
                } finally {
                    val elapsed = (System.nanoTime() - startedAt).coerceAtLeast(0)
                    if (action.command != null) {
                        processedMessages.incrementAndGet()
                    } else {
                        action.permit?.let { permit ->
                            synchronized(actor.lock) {
                                if (actor.pendingPermit === permit) {
                                    actor.pendingPermit = null
                                    pendingPermits.decrementAndGet()
                                }
                            }
                            processedPermits.incrementAndGet()
                        }
                    }
                    totalExecutionNanos.addAndGet(elapsed)
                    maximumExecutionNanos.accumulateAndGet(elapsed) { previous, current -> maxOf(previous, current) }
                }
            if (result != null) {
                val sequence = ++actor.resultSequence
                publish(actor.homeLane, VmActorEvent.Result(actor.endpoint, sequence, result))
            }
        }

        var close = false
        var reschedule = false
        synchronized(actor.lock) {
            if (actor.closed) return
            if (actor.mailbox.isNotEmpty() || actor.pendingPermit != null) {
                reschedule = true
            } else if (actor.closing) {
                close = true
            } else {
                actor.scheduled = false
                scheduledActors.decrementAndGet()
            }
        }
        when {
            close -> closeActor(actor)
            reschedule -> enqueue(actor)
        }
    }

    private fun closeActor(actor: ActorCell<C, P, R>) {
        synchronized(actor.lock) {
            if (actor.closed) return
            actor.closed = true
            actor.scheduled = false
            scheduledActors.decrementAndGet()
        }
        val failure = runCatching(actor.processor::close).exceptionOrNull()
        removeActor(actor)
        if (failure == null) {
            actor.closeBarrier.complete(true)
        } else {
            actor.closeBarrier.completeExceptionally(failure)
            publish(actor.homeLane, VmActorEvent.Failed(actor.endpoint, failure))
        }
    }

    private fun failActor(
        actor: ActorCell<C, P, R>,
        cause: Throwable,
    ) {
        synchronized(actor.lock) {
            if (actor.closed) return
            queuedMessages.addAndGet(-actor.mailbox.size)
            actor.mailbox.clear()
            if (actor.pendingPermit != null) {
                actor.pendingPermit = null
                pendingPermits.decrementAndGet()
            }
            actor.closing = true
            actor.closed = true
            actor.scheduled = false
            scheduledActors.decrementAndGet()
        }
        runCatching(actor.processor::close).exceptionOrNull()?.let(cause::addSuppressed)
        removeActor(actor)
        actor.closeBarrier.completeExceptionally(cause)
        publish(actor.homeLane, VmActorEvent.Failed(actor.endpoint, cause))
    }

    private fun removeActor(actor: ActorCell<C, P, R>) {
        synchronized(registryLock) {
            if (actors.remove(actor.endpoint.computerId, actor)) registeredActors.decrementAndGet()
        }
    }

    private fun enqueue(actor: ActorCell<C, P, R>) {
        check(readyLanes[actor.homeLane].offer(actor)) {
            "bounded ready lane cannot fill while each registered actor owns at most one token"
        }
    }

    private fun publish(
        workerIndex: Int,
        event: VmActorEvent<R>,
    ) {
        val queued = QueuedEvent(event, System.nanoTime())
        while (accepting.get()) {
            if (resultLanes[workerIndex].offer(queued, config.idlePollMillis, TimeUnit.MILLISECONDS)) return
        }
    }

    private class ActorCell<C : Any, P : Any, R : Any>(
        val endpoint: VmActorEndpoint,
        val processor: VmActorProcessor<C, P, R>,
        val homeLane: Int,
    ) {
        val lock = Any()
        val mailbox = ArrayDeque<QueuedCommand<C>>()
        val closeBarrier = CompletableFuture<Boolean>()
        var scheduled = false
        var closing = false
        var closed = false
        var resultSequence = 0L
        var mailboxSequence = 0L
        var pendingPermit: QueuedPermit<P>? = null
    }

    private data class QueuedCommand<C : Any>(
        val sequence: Long,
        val command: C,
        val enqueuedAtNanos: Long,
    )

    private class QueuedPermit<P : Any>(
        val fenceSequence: Long,
        val permit: P,
        val enqueuedAtNanos: Long,
    ) {
        var executing = false
    }

    private data class ActorAction<C : Any, P : Any>(
        val command: QueuedCommand<C>? = null,
        val permit: QueuedPermit<P>? = null,
    ) {
        val enqueuedAtNanos: Long = command?.enqueuedAtNanos ?: requireNotNull(permit).enqueuedAtNanos
    }

    private data class QueuedEvent<R : Any>(
        val event: VmActorEvent<R>,
        val completedAtNanos: Long,
    )
}
