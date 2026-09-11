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

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import ru.lazyhat.compukters.impl.compiler.NeoForgeCompilerServices
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemContext
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemContextSource
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemLease
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemLifecycle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Server callbacks inspect generations and request drains. The persistence worker never calls those callbacks;
 * it only consumes scalar requests and close barriers, and performs native I/O outside the registry monitor.
 */
internal class WorldFileSystemStoreRegistry<S : Any>(
    private val opener: (Path) -> S,
    private val flusher: (S, ComputerId, Long) -> Unit,
    private val tombstoner: (S, ComputerId) -> Unit,
    private val recoverer: (S, ComputerId) -> Unit,
    private val closer: (S) -> Unit,
    private val maximumComputers: () -> Int = { 1024 },
    private val executorFactory: (Int) -> java.util.concurrent.ExecutorService = ::persistenceExecutor,
    private val reportFailure: (Throwable) -> Unit = {},
) {
    private val entries = mutableMapOf<Path, Entry<S>>()

    @Synchronized
    fun store(worldRoot: Path): S = entry(worldRoot).store

    @Synchronized
    fun available(
        worldRoot: Path,
        computerId: ComputerId,
    ): Boolean {
        val current = entries[canonicalWorldRoot(worldRoot)] ?: return true
        checkAvailable(current)
        return computerId !in current.computers && current.computers.size < current.maximumComputers
    }

    fun lifecycle(worldRoot: Path): ComputerFileSystemLifecycle {
        val key = canonicalWorldRoot(worldRoot)
        return ComputerFileSystemLifecycle { computerId, generation, drain ->
            attach(key, computerId, generation, drain)
        }
    }

    @Synchronized
    fun save(worldRoot: Path) {
        val current = entries[canonicalWorldRoot(worldRoot)] ?: return
        if (current.stopping != null || current.failure != null) return
        current.computers.values.forEach { computer ->
            if (computer.released == null) {
                computer.generation?.invoke()?.let { requestFlush(current, computer, it) }
            }
        }
    }

    @Synchronized
    fun tombstone(
        worldRoot: Path,
        computerId: ComputerId,
    ): CompletableFuture<Void> {
        val current = entry(worldRoot)
        val computer = current.computers[computerId] ?: reserve(current, computerId)
        check(computer.recovering == null) { "computer filesystem recovery is pending: $computerId" }
        computer.tombstoned?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        computer.tombstoned = result
        release(current, computer, computer.drain ?: { CompletableFuture.completedFuture<Long?>(null) })
        return result.copy()
    }

    @Synchronized
    fun recover(
        worldRoot: Path,
        computerId: ComputerId,
    ): CompletableFuture<Void> {
        val current = entry(worldRoot)
        check(computerId !in current.computers) { "computer filesystem is still active or pending: $computerId" }
        val computer = reserve(current, computerId)
        val result = CompletableFuture<Void>()
        computer.recovering = result
        schedule(current, computer)
        return result.copy()
    }

    @Synchronized
    fun stop(worldRoot: Path): CompletableFuture<Void> {
        val key = canonicalWorldRoot(worldRoot)
        val current = entries[key] ?: return CompletableFuture.completedFuture(null)
        current.stopping?.let { return it.copy() }
        val stopped = CompletableFuture<Void>()
        current.stopping = stopped
        current.computers.values.toList().forEach { computer ->
            if (computer.recovering == null) {
                release(current, computer, computer.drain ?: { CompletableFuture.completedFuture<Long?>(null) })
            }
        }
        finishStop(current)
        return stopped.copy()
    }

    @Synchronized
    private fun attach(
        key: Path,
        computerId: ComputerId,
        generation: () -> Long?,
        drain: () -> CompletableFuture<Long?>,
    ): ComputerFileSystemLease {
        val current = entries[key] ?: error("filesystem store has not been opened for $key")
        checkAvailable(current)
        check(computerId !in current.computers) { "computer filesystem is already active or pending: $computerId" }
        val computer = reserve(current, computerId)
        computer.generation = generation
        computer.drain = drain
        return ComputerFileSystemLease { closed -> release(current, computer) { closed } }
    }

    private fun reserve(
        current: Entry<S>,
        id: ComputerId,
    ): Computer {
        check(current.computers.size < current.maximumComputers) { "filesystem computer capacity is exhausted" }
        return Computer(id).also { current.computers[id] = it }
    }

    @Synchronized
    private fun release(
        current: Entry<S>,
        computer: Computer,
        close: () -> CompletableFuture<Long?>,
    ): CompletableFuture<Void> {
        computer.released?.let { return it.copy() }
        val released = CompletableFuture<Void>()
        computer.released = released
        val closed =
            try {
                close()
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
        closed.whenComplete { generation, failure ->
            synchronized(this) {
                computer.closed = true
                if (failure != null) {
                    fail(current, computer, failure)
                } else if (current.failure != null) {
                    fail(current, computer, requireNotNull(current.failure))
                } else {
                    if (generation != null) requestFlush(current, computer, generation)
                    schedule(current, computer)
                }
                finishStop(current)
            }
        }
        return released.copy()
    }

    private fun requestFlush(
        current: Entry<S>,
        computer: Computer,
        generation: Long,
    ) {
        require(generation >= 0)
        if (generation > computer.flushedGeneration) {
            computer.flushGeneration = maxOf(computer.flushGeneration ?: generation, generation)
            schedule(current, computer)
        }
    }

    private fun schedule(
        current: Entry<S>,
        computer: Computer,
    ) {
        if (computer.scheduled || current.failure != null) return
        computer.scheduled = true
        try {
            current.executor.execute { runComputer(current, computer) }
        } catch (error: Throwable) {
            computer.scheduled = false
            fail(current, computer, error)
        }
    }

    private fun runComputer(
        current: Entry<S>,
        computer: Computer,
    ) {
        // One token per admitted identity bounds the executor queue, including while a native call is blocked.
        while (true) {
            val action =
                synchronized(this) {
                    if (current.failure != null) {
                        computer.scheduled = false
                        return
                    }
                    val generation = computer.flushGeneration
                    when {
                        generation != null -> {
                            computer.flushGeneration = null
                            Action.Flush(generation)
                        }

                        computer.recovering != null -> {
                            Action.Recover
                        }

                        computer.closed && computer.tombstoned != null && !computer.didTombstone -> {
                            Action.Tombstone
                        }

                        computer.closed -> {
                            completeComputer(current, computer)
                            return
                        }

                        else -> {
                            computer.scheduled = false
                            return
                        }
                    }
                }
            try {
                when (action) {
                    is Action.Flush -> flusher(current.store, computer.id, action.generation)
                    Action.Tombstone -> tombstoner(current.store, computer.id)
                    Action.Recover -> recoverer(current.store, computer.id)
                }
            } catch (error: Throwable) {
                synchronized(this) {
                    computer.scheduled = false
                    fail(current, computer, error)
                }
                return
            }
            synchronized(this) {
                when (action) {
                    is Action.Flush -> {
                        computer.flushedGeneration = maxOf(computer.flushedGeneration, action.generation)
                        if ((computer.flushGeneration ?: Long.MAX_VALUE) <= computer.flushedGeneration) {
                            computer.flushGeneration = null
                        }
                    }

                    Action.Tombstone -> {
                        computer.didTombstone = true
                    }

                    Action.Recover -> {
                        completeComputer(current, computer)
                        return
                    }
                }
            }
        }
    }

    private fun completeComputer(
        current: Entry<S>,
        computer: Computer,
    ) {
        computer.scheduled = false
        current.computers.remove(computer.id, computer)
        computer.released?.complete(null)
        computer.tombstoned?.complete(null)
        computer.recovering?.complete(null)
        finishStop(current)
    }

    private fun fail(
        current: Entry<S>,
        computer: Computer,
        failure: Throwable,
    ) {
        val first = current.failure == null
        if (first) current.failure = failure
        computer.released?.completeExceptionally(failure)
        computer.tombstoned?.completeExceptionally(failure)
        computer.recovering?.completeExceptionally(failure)
        if (first) {
            reportFailure(failure)
            current.computers.values.toList().forEach {
                it.released?.completeExceptionally(failure)
                it.tombstoned?.completeExceptionally(failure)
                it.recovering?.completeExceptionally(failure)
            }
        }
        finishStop(current)
    }

    private fun finishStop(current: Entry<S>) {
        val stopped = current.stopping ?: return
        current.failure?.let {
            stopped.completeExceptionally(it)
            // Keep native ownership, but do not retain an idle Java persistence thread on failed shutdown.
            current.executor.shutdown()
            return
        }
        if (current.computers.isNotEmpty() || current.closeScheduled) return
        current.closeScheduled = true
        try {
            current.executor.execute {
                try {
                    closer(current.store)
                    synchronized(this) { entries.remove(current.key, current) }
                    stopped.complete(null)
                } catch (error: Throwable) {
                    synchronized(this) { current.failure = error }
                    reportFailure(error)
                    stopped.completeExceptionally(error)
                } finally {
                    current.executor.shutdown()
                }
            }
        } catch (error: Throwable) {
            current.failure = error
            reportFailure(error)
            stopped.completeExceptionally(error)
            current.executor.shutdown()
        }
    }

    private fun entry(worldRoot: Path): Entry<S> {
        val key = canonicalWorldRoot(worldRoot)
        return entries
            .getOrPut(key) {
                val capacity = maximumComputers()
                require(capacity > 0) { "filesystem computer capacity must be positive" }
                val storageRoot = key.resolve(STORAGE_DIRECTORY)
                Files.createDirectories(storageRoot)
                Entry(key, opener(storageRoot.toRealPath()), executorFactory(capacity), capacity)
            }.also(::checkAvailable)
    }

    private fun checkAvailable(current: Entry<S>) {
        check(current.stopping == null) { "filesystem store is stopping" }
        check(current.failure == null) { "filesystem persistence failed" }
    }

    private fun canonicalWorldRoot(worldRoot: Path): Path = worldRoot.toRealPath()

    private class Entry<S : Any>(
        val key: Path,
        val store: S,
        val executor: java.util.concurrent.ExecutorService,
        val maximumComputers: Int,
    ) {
        val computers = mutableMapOf<ComputerId, Computer>()
        var stopping: CompletableFuture<Void>? = null
        var closeScheduled = false
        var failure: Throwable? = null
    }

    private class Computer(
        val id: ComputerId,
    ) {
        var generation: (() -> Long?)? = null
        var drain: (() -> CompletableFuture<Long?>)? = null
        var released: CompletableFuture<Void>? = null
        var tombstoned: CompletableFuture<Void>? = null
        var recovering: CompletableFuture<Void>? = null
        var closed = false
        var didTombstone = false
        var scheduled = false
        var flushGeneration: Long? = null
        var flushedGeneration = -1L
    }

    private sealed interface Action {
        data class Flush(
            val generation: Long,
        ) : Action

        data object Tombstone : Action

        data object Recover : Action
    }

    private companion object {
        val STORAGE_DIRECTORY: Path = Path.of("compukters", "filesystems")

        fun persistenceExecutor(capacity: Int): java.util.concurrent.ExecutorService =
            java.util.concurrent
                .ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    java.util.concurrent.ArrayBlockingQueue(capacity),
                    Thread
                        .ofPlatform()
                        .daemon()
                        .name("compukters-persistence-", 0)
                        .factory(),
                    java.util.concurrent.ThreadPoolExecutor
                        .AbortPolicy(),
                ).apply { allowCoreThreadTimeOut(true) }
    }
}

object NeoForgeWorldFileSystemStores {
    private val registry =
        WorldFileSystemStoreRegistry(
            opener = WorldFileSystemStore::open,
            flusher = WorldFileSystemStore::flush,
            tombstoner = WorldFileSystemStore::tombstone,
            recoverer = WorldFileSystemStore::recover,
            closer = WorldFileSystemStore::close,
            maximumComputers = ru.lazyhat.compukters.impl.config.CompuktersServerConfig::maximumActors,
            reportFailure = {
                ru.lazyhat.compukters.core.LOGGER
                    .error(it) { "Computer filesystem persistence failed" }
            },
        )

    val contextSource =
        object : ComputerFileSystemContextSource {
            override fun available(
                level: ServerLevel,
                computerId: ComputerId,
            ): Boolean = registry.available(worldRoot(level.server), computerId)

            override fun create(
                level: ServerLevel,
                computerId: ComputerId,
                romImage: ByteArray,
            ): ComputerFileSystemContext {
                val root = worldRoot(level.server)
                return ComputerFileSystemContext(
                    registry.store(root),
                    computerId,
                    romImage,
                    registry.lifecycle(root),
                    NeoForgeCompilerServices.router(level.server),
                    ru.lazyhat.compukters.impl.computer.NeoForgeVmActorServices
                        .service(level.server),
                )
            }

            override fun tombstone(
                level: ServerLevel,
                computerId: ComputerId,
            ) {
                registry.tombstone(worldRoot(level.server), computerId)
            }
        }

    fun recover(
        level: ServerLevel,
        computerId: ComputerId,
    ) = registry.recover(worldRoot(level.server), computerId)

    fun onLevelSave(event: LevelEvent.Save) {
        val level = event.level as? ServerLevel ?: return
        registry.save(worldRoot(level.server))
    }

    fun onServerStarting(event: ServerStartingEvent) {
        // Open and validate the world store during server startup, never during an ordinary computer tick.
        registry.store(worldRoot(event.server))
    }

    fun onServerStopping(event: ServerStoppingEvent) {
        // Shutdown only: actor close barriers do not depend on server tick/result pumping.
        registry.stop(worldRoot(event.server)).get(10, TimeUnit.SECONDS)
    }

    private fun worldRoot(server: MinecraftServer): Path = server.getWorldPath(LevelResource.ROOT)
}
