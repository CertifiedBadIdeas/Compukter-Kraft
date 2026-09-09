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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VmActorSchedulerTest {
    @Test
    fun `worker migration cannot reorder actor replies`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val otherBlocked = CountDownLatch(1)
        val releaseOther = CountDownLatch(1)
        val originalBlocked = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val firstWorker = AtomicInteger(-1)
        val secondWorker = AtomicInteger(-1)
        val target = endpoint(100)

        fun workerIndex() =
            Thread
                .currentThread()
                .name
                .substringAfterLast('-')
                .toInt()

        fun blocker(
            entered: CountDownLatch,
            release: CountDownLatch,
        ) = object : VmActorProcessor<Int, Int> {
            override fun process(command: Int): Int? {
                entered.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                return null
            }
        }
        scheduler(workerCount = 2, messagesPerTurn = 1).use { scheduler ->
            try {
                assertTrue(
                    scheduler.register(
                        target,
                        processor { command: Int ->
                            if (command == 1) {
                                firstWorker.set(workerIndex())
                                firstEntered.countDown()
                                check(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            } else {
                                secondWorker.set(workerIndex())
                            }
                            command
                        },
                    ),
                )
                assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(target, 1))
                assertTrue(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(scheduler.register(endpoint(101), blocker(otherBlocked, releaseOther)))
                assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint(101), 0))
                assertTrue(otherBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                // Start draining at the second worker's lane to expose worker-based publication order.
                repeat(1 - firstWorker.get()) { assertTrue(scheduler.drainEvents(1).isEmpty()) }
                releaseFirst.countDown()
                val firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
                while (scheduler.metrics().queuedResults < 1 && System.nanoTime() < firstDeadline) Thread.onSpinWait()
                assertEquals(1, scheduler.metrics().queuedResults)
                assertTrue(scheduler.register(endpoint(102), blocker(originalBlocked, releaseOriginal)))
                assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint(102), 0))
                assertTrue(originalBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                releaseOther.countDown()
                assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(target, 2))
                val secondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
                while (scheduler.metrics().queuedResults < 2 && System.nanoTime() < secondDeadline) Thread.onSpinWait()
                assertEquals(2, scheduler.metrics().queuedResults)
                assertEquals(1 - firstWorker.get(), secondWorker.get())
                assertEquals(listOf(1, 2), scheduler.awaitResults(2).map { it.value })
            } finally {
                releaseFirst.countDown()
                releaseOther.countDown()
                releaseOriginal.countDown()
            }
        }
    }

    @Test
    fun `a burst keeps one actor single flight and preserves accepted order`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val maximumConcurrent = AtomicInteger()
        val endpoint = endpoint(1)
        scheduler(workerCount = 4, mailboxCapacity = 32, messagesPerTurn = 3).use { scheduler ->
            assertTrue(
                scheduler.register(
                    endpoint,
                    processor { command: Int ->
                        val active = concurrent.incrementAndGet()
                        maximumConcurrent.accumulateAndGet(active, ::maxOf)
                        try {
                            if (command == 0) {
                                entered.countDown()
                                assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            }
                            command * 10
                        } finally {
                            concurrent.decrementAndGet()
                        }
                    },
                ),
            )

            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint, 0))
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            (1..20).forEach { command ->
                assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint, command))
            }
            assertEquals(1, scheduler.metrics().scheduledActors)
            release.countDown()

            val results = scheduler.awaitResults(21)
            assertEquals((0..20).toList(), results.map { it.value / 10 })
            assertEquals((1L..21L).toList(), results.map { it.sequence })
            assertEquals(List(21) { endpoint }, results.map { it.endpoint })
            assertEquals(1, maximumConcurrent.get())
        }
    }

    @Test
    fun `a blocked actor applies mailbox backpressure without blocking other actors`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val otherCompleted = CountDownLatch(1)
        val first = endpoint(1)
        val second = endpoint(2)
        scheduler(workerCount = 2, mailboxCapacity = 2).use { scheduler ->
            assertTrue(
                scheduler.register(
                    first,
                    processor { command: Int ->
                        if (command == 0) {
                            blocked.countDown()
                            assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }
                        command
                    },
                ),
            )
            assertTrue(
                scheduler.register(
                    second,
                    processor { command: Int ->
                        otherCompleted.countDown()
                        command
                    },
                ),
            )

            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(first, 0))
            assertTrue(blocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(first, 1))
            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(first, 2))
            assertEquals(VmActorSubmission.MAILBOX_FULL, scheduler.submit(first, 3))

            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(second, 9))
            assertTrue(otherCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(listOf(0, 1, 2, 9), scheduler.awaitResults(4).map { it.value }.sorted())
        }
    }

    @Test
    fun `different actors can run concurrently`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val overlapped = AtomicBoolean()
        scheduler(workerCount = 2).use { scheduler ->
            listOf(endpoint(1), endpoint(2)).forEach { endpoint ->
                assertTrue(
                    scheduler.register(
                        endpoint,
                        processor { command: Int ->
                            if (active.incrementAndGet() == 2) overlapped.set(true)
                            entered.countDown()
                            try {
                                assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            } finally {
                                active.decrementAndGet()
                            }
                            command
                        },
                    ),
                )
                assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint, endpoint.computerId.hashCode()))
            }

            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            release.countDown()
            scheduler.awaitResults(2)
            assertTrue(overlapped.get())
        }
    }

    @Test
    fun `close is an ordered worker barrier and stale epochs stay rejected`() {
        val oldEndpoint = endpoint(1, epoch = 7)
        val newEndpoint = endpoint(1, epoch = 8)
        val events = mutableListOf<String>()
        scheduler(workerCount = 1).use { scheduler ->
            assertTrue(
                scheduler.register(
                    oldEndpoint,
                    object : VmActorProcessor<Int, Int> {
                        override fun process(command: Int): Int {
                            events += "command:$command:${Thread.currentThread().name}"
                            return command
                        }

                        override fun close() {
                            events += "close:${Thread.currentThread().name}"
                        }
                    },
                ),
            )
            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(oldEndpoint, 1))

            val closed = scheduler.unregister(oldEndpoint)
            assertEquals(VmActorSubmission.STALE_ENDPOINT, scheduler.submit(oldEndpoint, 2))
            assertTrue(closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, scheduler.metrics().registeredActors)
            assertTrue(events[0].startsWith("command:1:compukters-vm-worker-"))
            assertTrue(events[1].startsWith("close:compukters-vm-worker-"))
            val oldResult = scheduler.awaitResults(1).single()
            assertEquals(oldEndpoint, oldResult.endpoint)
            assertEquals(1, oldResult.value)

            assertTrue(scheduler.register(newEndpoint, processor { it }))
            assertEquals(VmActorSubmission.STALE_ENDPOINT, scheduler.submit(oldEndpoint, 3))
            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(newEndpoint, 4))
            assertEquals(4, scheduler.awaitResults(1).single().value)
        }
    }

    @Test
    fun `processor failure is reported and does not terminate its worker`() {
        val failed = endpoint(1)
        val healthy = endpoint(2)
        scheduler(workerCount = 1).use { scheduler ->
            assertTrue(scheduler.register(failed, processor<Int, Int> { error("broken actor") }))
            assertTrue(scheduler.register(healthy, processor { it * 2 }))

            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(failed, 1))
            val failure = assertIs<VmActorEvent.Failed>(scheduler.awaitEvents(1).single())
            assertEquals(failed, failure.endpoint)
            assertEquals("broken actor", failure.cause.message)

            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(healthy, 3))
            val result = assertIs<VmActorEvent.Result<Int>>(scheduler.awaitEvents(1).single())
            assertEquals(6, result.value)
        }
    }

    @Test
    fun `one thousand idle actors consume no worker turns or runnable tokens`() {
        val calls = AtomicInteger()
        scheduler(workerCount = 4, maximumActors = 1_000).use { scheduler ->
            repeat(1_000) { index ->
                assertTrue(scheduler.register(endpoint(index), processor<Int, Int> { calls.incrementAndGet() }))
            }

            val metrics = scheduler.metrics()
            assertEquals(1_000, metrics.registeredActors)
            assertEquals(0, metrics.scheduledActors)
            assertEquals(0, metrics.queuedMessages)
            assertEquals(0, metrics.busyWorkers)
            assertEquals(0, metrics.queuedResults)
            assertEquals(0, calls.get())
            assertFalse(scheduler.register(endpoint(1_001), processor { it }))
        }
    }

    private fun scheduler(
        workerCount: Int,
        maximumActors: Int = 16,
        mailboxCapacity: Int = 16,
        messagesPerTurn: Int = 4,
    ): VmActorScheduler<Int, Int> =
        VmActorScheduler(
            VmActorSchedulerConfig(
                workerCount = workerCount,
                maximumActors = maximumActors,
                mailboxCapacity = mailboxCapacity,
                messagesPerTurn = messagesPerTurn,
                resultCapacityPerWorker = 1_024,
            ),
        )

    private fun VmActorScheduler<Int, Int>.awaitResults(count: Int): List<VmActorEvent.Result<Int>> =
        awaitEvents(count).map { assertIs<VmActorEvent.Result<Int>>(it) }

    private fun VmActorScheduler<Int, Int>.awaitEvents(count: Int): List<VmActorEvent<Int>> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        val events = mutableListOf<VmActorEvent<Int>>()
        while (events.size < count && System.nanoTime() < deadline) {
            events += drainEvents(count - events.size)
            if (events.size < count) Thread.onSpinWait()
        }
        assertEquals(count, events.size, "scheduler events before timeout")
        return events
    }

    private fun endpoint(
        value: Int,
        epoch: Long = 1,
    ): VmActorEndpoint = VmActorEndpoint(ComputerId.fromLongs(0, value.toLong() + 1), epoch)

    private fun <C : Any, R : Any> processor(block: (C) -> R): VmActorProcessor<C, R> =
        object : VmActorProcessor<C, R> {
            override fun process(command: C): R = block(command)
        }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
