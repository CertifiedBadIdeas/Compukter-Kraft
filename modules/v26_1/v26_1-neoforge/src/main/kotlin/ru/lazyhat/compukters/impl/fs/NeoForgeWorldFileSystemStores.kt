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

internal class WorldFileSystemStoreRegistry<S : Any>(
    private val opener: (Path) -> S,
    private val flusher: (S, ComputerId, Long) -> Unit,
    private val tombstoner: (S, ComputerId) -> Unit,
    private val recoverer: (S, ComputerId) -> Unit,
    private val closer: (S) -> Unit,
) {
    private val entries = mutableMapOf<Path, Entry<S>>()

    @Synchronized
    fun store(worldRoot: Path): S = entry(worldRoot).store

    fun lifecycle(worldRoot: Path): ComputerFileSystemLifecycle {
        val key = canonicalWorldRoot(worldRoot)
        return ComputerFileSystemLifecycle { computerId, generation, drain ->
            attach(key, computerId, generation, drain)
        }
    }

    @Synchronized
    fun save(worldRoot: Path) {
        entries[canonicalWorldRoot(worldRoot)]?.flushActive()
    }

    @Synchronized
    fun tombstone(
        worldRoot: Path,
        computerId: ComputerId,
    ) {
        val current = entry(worldRoot)
        val attachment = current.active[computerId]
        if (attachment == null) {
            tombstoner(current.store, computerId)
        } else {
            attachment.tombstone = true
            release(current, attachment, attachment.drain)
        }
    }

    @Synchronized
    fun recover(
        worldRoot: Path,
        computerId: ComputerId,
    ) {
        val current = entry(worldRoot)
        check(computerId !in current.active) { "computer filesystem is still active: $computerId" }
        recoverer(current.store, computerId)
    }

    @Synchronized
    fun stop(worldRoot: Path): CompletableFuture<Void> {
        val key = canonicalWorldRoot(worldRoot)
        val current = entries[key] ?: return CompletableFuture.completedFuture(null)
        current.stopping?.let { return it.copy() }
        val stopped = CompletableFuture<Void>()
        current.stopping = stopped
        val barriers =
            current.active.values
                .toList()
                .map { release(current, it, it.drain) }
        CompletableFuture.allOf(*barriers.toTypedArray()).whenComplete { _, failure ->
            synchronized(this) {
                if (failure != null) {
                    // Never close a store whose machine ownership could not be released.
                    stopped.completeExceptionally(failure)
                } else {
                    try {
                        closer(current.store)
                        entries.remove(key, current)
                        stopped.complete(null)
                    } catch (error: Throwable) {
                        stopped.completeExceptionally(error)
                    }
                }
            }
        }
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
        check(current.stopping == null) { "filesystem store is stopping" }
        check(computerId !in current.active) { "computer filesystem is already active: $computerId" }
        val attachment = ActiveComputer(computerId, generation, drain)
        current.active[computerId] = attachment
        return ComputerFileSystemLease { closed -> release(current, attachment) { closed } }
    }

    @Synchronized
    private fun release(
        current: Entry<S>,
        attachment: ActiveComputer,
        close: () -> CompletableFuture<Long?>,
    ): CompletableFuture<Void> {
        attachment.released?.let { return it.copy() }
        val released = CompletableFuture<Void>()
        attachment.released = released
        val closed =
            try {
                close()
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
        closed.whenComplete { generation, failure ->
            synchronized(this) {
                if (failure != null) {
                    released.completeExceptionally(failure)
                } else {
                    try {
                        if (generation != null) flusher(current.store, attachment.computerId, generation)
                        if (attachment.tombstone) tombstoner(current.store, attachment.computerId)
                        current.active.remove(attachment.computerId, attachment)
                        released.complete(null)
                    } catch (error: Throwable) {
                        released.completeExceptionally(error)
                    }
                }
            }
        }
        return released.copy()
    }

    private fun entry(worldRoot: Path): Entry<S> {
        val key = canonicalWorldRoot(worldRoot)
        return entries
            .getOrPut(key) {
                val storageRoot = key.resolve(STORAGE_DIRECTORY)
                Files.createDirectories(storageRoot)
                Entry(opener(storageRoot.toRealPath()))
            }.also { check(it.stopping == null) { "filesystem store is stopping" } }
    }

    private fun canonicalWorldRoot(worldRoot: Path): Path = worldRoot.toRealPath()

    private fun Entry<S>.flushActive() {
        active.values.forEach { attachment ->
            if (attachment.released == null) {
                attachment.generation()?.let { flusher(store, attachment.computerId, it) }
            }
        }
    }

    private class Entry<S : Any>(
        val store: S,
        val active: MutableMap<ComputerId, ActiveComputer> = mutableMapOf(),
    ) {
        var stopping: CompletableFuture<Void>? = null
    }

    private class ActiveComputer(
        val computerId: ComputerId,
        val generation: () -> Long?,
        val drain: () -> CompletableFuture<Long?>,
    ) {
        var released: CompletableFuture<Void>? = null
        var tombstone = false
    }

    private companion object {
        val STORAGE_DIRECTORY: Path = Path.of("compukters", "filesystems")
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
        )

    val contextSource =
        object : ComputerFileSystemContextSource {
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

    fun onServerStopping(event: ServerStoppingEvent) {
        // Shutdown only: actor close barriers do not depend on server tick/result pumping.
        registry.stop(worldRoot(event.server)).get(10, TimeUnit.SECONDS)
    }

    private fun worldRoot(server: MinecraftServer): Path = server.getWorldPath(LevelResource.ROOT)
}
