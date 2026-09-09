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

/**
 * Runs independently addressed stateful processors without ever executing one processor concurrently.
 *
 * Commands and results cross thread boundaries and must therefore be immutable or privately owned by the receiver.
 * An accepted command remains owned by this scheduler until it is processed, unless its processor itself fails.
 */
class VmActorScheduler<C : Any, R : Any>(
    private val config: VmActorSchedulerConfig = VmActorSchedulerConfig(),
) : AutoCloseable {
    private val registryLock = Any()
    private val actors = ConcurrentHashMap<ComputerId, ActorCell<C, R>>()
    private val readyLanes =
        List(config.workerCount) {
            ArrayBlockingQueue<ActorCell<C, R>>(config.maximumActors)
        }
    private val resultLanes =
        // Keep an actor's results ordered even when a different worker steals its next turn.
        List(config.workerCount) {
            ArrayBlockingQueue<VmActorEvent<R>>(config.resultCapacityPerWorker)
        }
    private val accepting = AtomicBoolean(true)
    private val workersRunning = AtomicBoolean(true)
    private val registeredActors = AtomicInteger()
    private val scheduledActors = AtomicInteger()
    private val queuedMessages = AtomicInteger()
    private val busyWorkers = AtomicInteger()
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
        processor: VmActorProcessor<C, R>,
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
        if (!accepting.get()) return VmActorSubmission.CLOSED
        val actor = actors[endpoint.computerId] ?: return VmActorSubmission.STALE_ENDPOINT
        var schedule = false
        synchronized(actor.lock) {
            if (actor.endpoint != endpoint || actor.closing || actor.closed) {
                return VmActorSubmission.STALE_ENDPOINT
            }
            if (actor.mailbox.size >= config.mailboxCapacity) return VmActorSubmission.MAILBOX_FULL
            actor.mailbox.addLast(command)
            queuedMessages.incrementAndGet()
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
        while (drained.size < maximumEvents && emptyLanes < resultLanes.size) {
            val event = resultLanes[laneIndex].poll()
            if (event == null) {
                emptyLanes++
            } else {
                drained += event
                emptyLanes = 0
            }
            laneIndex = (laneIndex + 1) % resultLanes.size
        }
        return drained
    }

    fun metrics(): VmActorSchedulerMetrics =
        VmActorSchedulerMetrics(
            registeredActors = registeredActors.get(),
            scheduledActors = scheduledActors.get(),
            queuedMessages = queuedMessages.get(),
            busyWorkers = busyWorkers.get(),
            queuedResults = resultLanes.sumOf { it.size },
        )

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        val barriers = actors.values.map { requestClose(it) }
        resultLanes.forEach(ArrayBlockingQueue<VmActorEvent<R>>::clear)
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

    private fun requestClose(actor: ActorCell<C, R>): CompletableFuture<Boolean> {
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

    private fun nextActor(workerIndex: Int): ActorCell<C, R>? {
        readyLanes[workerIndex].poll()?.let { return it }
        repeat(readyLanes.size - 1) { offset ->
            val lane = (workerIndex + offset + 1) % readyLanes.size
            readyLanes[lane].poll()?.let { return it }
        }
        return readyLanes[workerIndex].poll(config.idlePollMillis, TimeUnit.MILLISECONDS)
    }

    private fun runTurn(actor: ActorCell<C, R>) {
        repeat(config.messagesPerTurn) {
            val command =
                synchronized(actor.lock) {
                    actor.mailbox.removeFirstOrNull()?.also { queuedMessages.decrementAndGet() }
                } ?: return@repeat
            val result =
                try {
                    actor.processor.process(command)
                } catch (cause: Throwable) {
                    failActor(actor, cause)
                    return
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
            if (actor.mailbox.isNotEmpty()) {
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

    private fun closeActor(actor: ActorCell<C, R>) {
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
        actor: ActorCell<C, R>,
        cause: Throwable,
    ) {
        synchronized(actor.lock) {
            if (actor.closed) return
            queuedMessages.addAndGet(-actor.mailbox.size)
            actor.mailbox.clear()
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

    private fun removeActor(actor: ActorCell<C, R>) {
        synchronized(registryLock) {
            if (actors.remove(actor.endpoint.computerId, actor)) registeredActors.decrementAndGet()
        }
    }

    private fun enqueue(actor: ActorCell<C, R>) {
        check(readyLanes[actor.homeLane].offer(actor)) {
            "bounded ready lane cannot fill while each registered actor owns at most one token"
        }
    }

    private fun publish(
        workerIndex: Int,
        event: VmActorEvent<R>,
    ) {
        while (accepting.get()) {
            if (resultLanes[workerIndex].offer(event, config.idlePollMillis, TimeUnit.MILLISECONDS)) return
        }
    }

    private class ActorCell<C : Any, R : Any>(
        val endpoint: VmActorEndpoint,
        val processor: VmActorProcessor<C, R>,
        val homeLane: Int,
    ) {
        val lock = Any()
        val mailbox = ArrayDeque<C>()
        val closeBarrier = CompletableFuture<Boolean>()
        var scheduled = false
        var closing = false
        var closed = false
        var resultSequence = 0L
    }
}
