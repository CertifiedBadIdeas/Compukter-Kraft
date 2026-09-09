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

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.util.concurrent.CompletableFuture

class ComputerFileSystemContext(
    val store: WorldFileSystemStore,
    val computerId: ComputerId,
    romImage: ByteArray,
    private val lifecycle: ComputerFileSystemLifecycle,
    internal val compilerRouter: CompilerCompletionRouter? = null,
) {
    private val romImage = romImage.copyOf()

    internal fun romImage(): ByteArray = romImage.copyOf()

    internal fun attach(
        generation: () -> Long?,
        drain: () -> CompletableFuture<Long?>,
    ): ComputerFileSystemLease = lifecycle.attach(computerId, generation, drain)
}

fun interface ComputerFileSystemContextSource {
    /** False while the previous machine's persistence work still owns this identity. */
    fun available(
        level: ServerLevel,
        computerId: ComputerId,
    ): Boolean = true

    fun create(
        level: ServerLevel,
        computerId: ComputerId,
        romImage: ByteArray,
    ): ComputerFileSystemContext

    fun tombstone(
        level: ServerLevel,
        computerId: ComputerId,
    ) = Unit
}

fun interface ComputerFileSystemLifecycle {
    fun attach(
        computerId: ComputerId,
        generation: () -> Long?,
        drain: () -> CompletableFuture<Long?>,
    ): ComputerFileSystemLease
}

fun interface ComputerFileSystemLease {
    /** Retains ownership until the runtime has closed; completion may occur on a worker. */
    fun release(closed: CompletableFuture<Long?>): CompletableFuture<Void>
}
