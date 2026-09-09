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

package ru.lazyhat.compukters.impl.fs

import org.junit.jupiter.api.io.TempDir
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.nio.file.Path
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeoForgeWorldFileSystemStoresTest {
    @TempDir
    lateinit var world: Path

    @Test
    fun `store opens once at the exact canonical path`() {
        val fixture = Fixture()
        fixture.registry.store(world.resolve(".").normalize())
        assertEquals(listOf(world.toRealPath().resolve("compukters/filesystems").toRealPath()), fixture.opened)
        fixture.stop()
    }

    @Test
    fun `save coalesces generations and stop flushes final generation before closing`() {
        val fixture = Fixture()
        var generation = 1L
        fixture.attach(generation = { generation }, drain = {
            fixture.events += "drain"
            completed(9)
        })
        repeat(8) {
            generation = it.toLong() + 1
            fixture.registry.save(world)
        }
        assertEquals(1, fixture.executor.tasks.size)
        assertTrue(fixture.events.isEmpty())
        fixture.executor.runAll()
        assertEquals(listOf("flush:8"), fixture.events)
        fixture.stop()
        assertEquals(listOf("flush:8", "drain", "flush:9", "close"), fixture.events)
    }

    @Test
    fun `unload retains ownership through final flush and tombstone`() {
        val fixture = Fixture()
        val closed = CompletableFuture<Long?>()
        val lease = fixture.attach(drain = { error("close already requested") })
        val released = lease.release(closed)
        val deleted = fixture.registry.tombstone(world, ID)
        assertFalse(fixture.registry.available(world, ID))
        assertFailsWith<IllegalStateException> { fixture.attach() }
        closed.complete(7)
        assertFalse(released.isDone)
        assertFalse(deleted.isDone)
        assertTrue(fixture.events.isEmpty())
        fixture.executor.runAll()
        released.join()
        deleted.join()
        assertEquals(listOf("flush:7", "tombstone"), fixture.events)
        assertTrue(fixture.registry.available(world, ID))
        val recovered = fixture.registry.recover(world, ID)
        assertFalse(recovered.isDone)
        assertFalse(fixture.registry.available(world, ID))
        fixture.executor.runAll()
        recovered.join()
        fixture.attach()
        fixture.stop()
    }

    @Test
    fun `stop initiates all drains and cancellation cannot bypass ownership`() {
        val fixture = Fixture()
        val first = CompletableFuture<Long?>()
        val second = CompletableFuture<Long?>()
        fixture.attach(drain = {
            fixture.events += "drain1"
            first
        })
        fixture.attach(SECOND, drain = {
            fixture.events += "drain2"
            second
        })
        val stopped = fixture.registry.stop(world)
        stopped.cancel(false)
        val duplicate = fixture.registry.stop(world)
        assertEquals(listOf("drain1", "drain2"), fixture.events)
        assertFailsWith<IllegalStateException> { fixture.registry.store(world) }
        second.complete(2)
        fixture.executor.runAll()
        assertFalse(duplicate.isDone)
        first.complete(1)
        fixture.executor.runAll()
        duplicate.join()
        assertEquals(listOf("drain1", "drain2", "flush:2", "flush:1", "close"), fixture.events)
        assertTrue(fixture.executor.isShutdown)
        fixture.registry.stop(world).join()
        assertEquals(1, fixture.events.count { it == "close" })
    }

    @Test
    fun `failed native operation is reported and prevents unsafe close or reattachment`() {
        val fixture = Fixture(flush = { error("disk failed") })
        val lease = fixture.attach()
        val released = lease.release(completed(3))
        fixture.executor.runAll()
        assertTrue(released.isCompletedExceptionally)
        assertEquals(1, fixture.failures.size)
        assertTrue(fixture.registry.stop(world).isCompletedExceptionally)
        assertFalse("close" in fixture.events)
        assertFailsWith<IllegalStateException> { fixture.registry.store(world) }
        assertTrue(fixture.executor.isShutdown)
    }

    @Test
    fun `failed VM close is reported even without a stop request`() {
        val fixture = Fixture()
        val lease = fixture.attach()
        val released = lease.release(CompletableFuture.failedFuture(IllegalStateException("VM still owns storage")))
        assertTrue(released.isCompletedExceptionally)
        assertEquals(1, fixture.failures.size)
        assertTrue(fixture.registry.stop(world).isCompletedExceptionally)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `capacity reserves one token per identity and frees it only after I O`() {
        val fixture = Fixture(capacity = 1)
        val deleted = fixture.registry.tombstone(world, ID)
        assertFailsWith<IllegalStateException> { fixture.attach(SECOND) }
        assertFalse(fixture.registry.available(world, SECOND))
        assertEquals(1, fixture.executor.tasks.size)
        fixture.executor.runAll()
        deleted.join()
        fixture.attach(SECOND)
        fixture.stop()
    }

    @Test
    fun `blocked native flush does not hold registry monitor or lose a later save`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val generations = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val caller = Thread.currentThread()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { Any() },
                flusher = { _, _, generation ->
                    assertTrue(Thread.currentThread() !== caller)
                    generations += generation
                    if (generation == 1L) {
                        entered.countDown()
                        check(unblock.await(5, TimeUnit.SECONDS))
                    }
                },
                tombstoner = { _, _ -> },
                recoverer = { _, _ -> },
                closer = { assertTrue(Thread.currentThread() !== caller) },
            )
        registry.store(world)
        var generation = 1L
        registry.lifecycle(world).attach(ID, { generation }, { completed(4) })
        try {
            registry.save(world)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            generation = 2
            registry.save(world)
            generation = 3
            registry.save(world)
            assertFalse(registry.available(world, ID))
            val stopped = registry.stop(world)
            assertFalse(stopped.isDone)
            unblock.countDown()
            stopped.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(1L, 4L), generations)
        } finally {
            unblock.countDown()
            registry.stop(world).get(5, TimeUnit.SECONDS)
        }
    }

    private inner class Fixture(
        capacity: Int = 1024,
        flush: ((Long) -> Unit)? = null,
    ) {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val opened = mutableListOf<Path>()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { path ->
                    opened.add(path)
                    Any()
                },
                flusher = { _, _, generation ->
                    flush?.invoke(generation)
                    events += "flush:$generation"
                },
                tombstoner = { _, _ -> events += "tombstone" },
                recoverer = { _, _ -> events += "recover" },
                closer = { events += "close" },
                maximumComputers = capacity,
                executorFactory = { executor },
                reportFailure = { failures += it },
            )

        init {
            registry.store(world)
        }

        fun attach(
            id: ComputerId = ID,
            generation: () -> Long? = { null },
            drain: () -> CompletableFuture<Long?> = { completed(null) },
        ) = registry.lifecycle(world).attach(id, generation, drain)

        fun stop() {
            val stopped = registry.stop(world)
            executor.runAll()
            stopped.join()
        }
    }

    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            check(!stopped)
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }

    private companion object {
        val ID = ComputerId.fromLongs(1, 2)
        val SECOND = ComputerId.fromLongs(3, 4)

        fun completed(generation: Long?): CompletableFuture<Long?> = CompletableFuture.completedFuture(generation)
    }
}
