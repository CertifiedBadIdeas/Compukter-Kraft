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
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeoForgeWorldFileSystemStoresTest {
    @TempDir
    lateinit var world: Path

    @Test
    fun `store root is exact and each world opens once`() {
        val opened = mutableListOf<Path>()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { path ->
                    opened.add(path)
                    FakeStore()
                },
                flusher = { _, _, _ -> },
                tombstoner = { _, _ -> },
                recoverer = { _, _ -> },
                closer = { it.closeCalls++ },
            )

        registry.store(world)
        registry.store(world.resolve(".").normalize())

        assertEquals(listOf(world.toRealPath().resolve("compukters/filesystems").toRealPath()), opened)
    }

    @Test
    fun `save flushes active generations and stop drains before closing once`() {
        val events = mutableListOf<String>()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { FakeStore() },
                flusher = { _, id, generation -> events += "flush:$id:$generation" },
                tombstoner = { _, _ -> },
                recoverer = { _, _ -> },
                closer = { store ->
                    store.closeCalls++
                    events += "close"
                },
            )
        val id = ComputerId.fromLongs(1, 2)
        val store = registry.store(world)
        val lifecycle = registry.lifecycle(world)
        var generation = 3L
        lifecycle.attach(id, { generation }, {
            events += "drain"
            CompletableFuture.completedFuture(5L)
        })

        registry.save(world)
        generation = 4
        registry.stop(world).join()
        registry.stop(world).join()

        assertEquals(listOf("flush:$id:3", "drain", "flush:$id:5", "close"), events)
        assertEquals(1, store.closeCalls)
    }

    @Test
    fun `releasing a carrier flushes it without closing world storage`() {
        val events = mutableListOf<String>()
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { FakeStore() },
                flusher = { _, _, generation -> events += "flush:$generation" },
                tombstoner = { _, _ -> },
                recoverer = { _, _ -> },
                closer = { it.closeCalls++ },
            )
        val store = registry.store(world)
        val lease = registry.lifecycle(world).attach(ComputerId.fromLongs(7, 8), { 9 }, { CompletableFuture.completedFuture(9) })

        lease.release(CompletableFuture.completedFuture(10)).join()
        registry.save(world)

        assertEquals(listOf("flush:10"), events)
        assertEquals(0, store.closeCalls)
    }

    @Test
    fun `destruction tombstones and admin recovery restores the same identity`() {
        val events = mutableListOf<String>()
        val id = ComputerId.fromLongs(11, 12)
        val registry =
            WorldFileSystemStoreRegistry(
                opener = { FakeStore() },
                flusher = { _, _, _ -> },
                tombstoner = { _, computerId -> events += "tombstone:$computerId" },
                recoverer = { _, computerId -> events += "recover:$computerId" },
                closer = { it.closeCalls++ },
            )

        registry.tombstone(world, id)
        registry.recover(world, id)

        assertEquals(listOf("tombstone:$id", "recover:$id"), events)
    }

    @Test
    fun `unload retains ownership and defers destruction until the worker closes`() {
        val events = mutableListOf<String>()
        val registry = registry(events)
        val store = registry.store(world)
        val id = ComputerId.fromLongs(1, 2)
        val closed = CompletableFuture<Long?>()
        val lifecycle = registry.lifecycle(world)
        val lease = lifecycle.attach(id, { 1 }, { error("unload already requested close") })
        val released = lease.release(closed)
        assertFalse(released.isDone)
        assertFailsWith<IllegalStateException> {
            lifecycle.attach(id, { null }, { CompletableFuture.completedFuture(null) })
        }
        registry.tombstone(world, id)
        registry.save(world)
        assertTrue(events.isEmpty())
        closed.complete(7)
        released.join()
        assertEquals(listOf("flush:7", "tombstone"), events)
        assertEquals(0, store.closeCalls)
        registry.recover(world, id)
        lifecycle.attach(id, { null }, { CompletableFuture.completedFuture(null) })
        registry.stop(world).join()
    }

    @Test
    fun `stop starts every drain and waits for all barriers without holding up its caller`() {
        val events = mutableListOf<String>()
        val registry = registry(events)
        val store = registry.store(world)
        val first = CompletableFuture<Long?>()
        val second = CompletableFuture<Long?>()
        val lifecycle = registry.lifecycle(world)
        lifecycle.attach(ComputerId.fromLongs(1, 1), { null }, {
            events += "drain1"
            first
        })
        lifecycle.attach(ComputerId.fromLongs(2, 2), { null }, {
            events += "drain2"
            second
        })
        val stopped = registry.stop(world)
        val duplicate = registry.stop(world)
        stopped.cancel(false)
        assertFalse(duplicate.isDone)
        assertEquals(listOf("drain1", "drain2"), events)
        assertFailsWith<IllegalStateException> { registry.store(world) }
        second.complete(2)
        assertEquals(0, store.closeCalls)
        first.complete(1)
        duplicate.join()
        assertEquals(listOf("drain1", "drain2", "flush:2", "flush:1", "close"), events)
        assertEquals(1, store.closeCalls)
        registry.stop(world).join()
        assertEquals(1, store.closeCalls)
    }

    @Test
    fun `failed ownership barrier prevents unsafe store close and reopening`() {
        val events = mutableListOf<String>()
        val registry = registry(events)
        val store = registry.store(world)
        val failed = CompletableFuture<Long?>()
        registry.lifecycle(world).attach(ComputerId.fromLongs(1, 1), { null }, { failed })
        val stopped = registry.stop(world)
        failed.completeExceptionally(IllegalStateException("native close failed"))
        assertTrue(stopped.isCompletedExceptionally)
        assertEquals(0, store.closeCalls)
        assertFailsWith<IllegalStateException> { registry.store(world) }
    }

    private fun registry(events: MutableList<String>) =
        WorldFileSystemStoreRegistry(
            opener = { FakeStore() },
            flusher = { _, _, generation -> events += "flush:$generation" },
            tombstoner = { _, _ -> events += "tombstone" },
            recoverer = { _, _ -> events += "recover" },
            closer = {
                it.closeCalls++
                events += "close"
            },
        )

    private class FakeStore {
        var closeCalls = 0
    }
}
