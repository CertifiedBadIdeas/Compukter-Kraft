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

package ru.lazyhat.compukters.impl.ide.target

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Bounds suspended requests as well as actively executing requests. Confined to the server thread. */
internal class IdeServerOperations(
    private val maximumPending: Int = 256,
    private val maximumPendingPerPlayer: Int = 4,
) : AutoCloseable {
    private val pending = mutableMapOf<UUID, MutableSet<CompletableFuture<*>>>()
    private var count = 0
    var closed = false
        private set

    init {
        require(maximumPending > 0 && maximumPendingPerPlayer > 0)
    }

    fun <T> submit(
        player: UUID,
        operation: suspend () -> T,
    ): CompletableFuture<T>? {
        if (closed || count >= maximumPending || (pending[player]?.size ?: 0) >= maximumPendingPerPlayer) return null
        val result = CompletableFuture<T>()
        pending.getOrPut(player) { mutableSetOf() }.add(result)
        count++
        serverOperation(operation).whenComplete { value, failure ->
            if (pending[player]?.remove(result) == true) {
                count--
                if (pending[player]?.isEmpty() == true) pending.remove(player)
            }
            if (failure == null) result.complete(value) else result.completeExceptionally(failure)
        }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        val requests = pending.values.flatMap { it.toList() }
        pending.clear()
        count = 0
        requests.forEach { it.completeExceptionally(IllegalStateException("IDE server operations closed")) }
    }
}
