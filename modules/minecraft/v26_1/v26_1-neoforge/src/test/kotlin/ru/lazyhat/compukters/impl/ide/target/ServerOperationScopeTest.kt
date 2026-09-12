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

import ru.lazyhat.compukters.impl.network.ServerOperationScope
import ru.lazyhat.compukters.impl.network.awaitServerResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerOperationScopeTest {
    @Test
    fun `suspended requests remain bounded and release capacity on completion`() {
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        val completion = CompletableFuture<Int>()
        ServerOperationScope(maximumPending = 2, maximumPendingPerPlayer = 1).use { operations ->
            val first = assertNotNull(operations.submit(player) { completion.awaitServerResult() })
            assertFalse(first.isDone)
            assertNull(operations.submit(player) { 2 })
            val secondCompletion = CompletableFuture<Int>()
            val second = assertNotNull(operations.submit(other) { secondCompletion.awaitServerResult() })
            assertNull(operations.submit(UUID.randomUUID()) { 3 })
            completion.complete(7)
            assertEquals(7, first.join())
            assertEquals(9, assertNotNull(operations.submit(player) { 9 }).join())
            secondCompletion.complete(8)
            assertEquals(8, second.join())
        }
    }

    @Test
    fun `cancelling a reply cannot bypass the suspended operation limit`() {
        val player = UUID.randomUUID()
        val completion = CompletableFuture<Int>()
        ServerOperationScope(maximumPending = 1).use { operations ->
            val pending = assertNotNull(operations.submit(player) { completion.awaitServerResult() })
            pending.cancel(false)
            assertNull(operations.submit(player) { 2 })
            completion.complete(1)
            assertNotNull(operations.submit(player) { 3 })
        }
    }

    @Test
    fun `closing resolves replies and prevents new requests while late completion is harmless`() {
        val player = UUID.randomUUID()
        val completion = CompletableFuture<Int>()
        val operations = ServerOperationScope()
        val pending = assertNotNull(operations.submit(player) { completion.awaitServerResult() })
        operations.close()
        assertTrue(pending.isCompletedExceptionally)
        assertNull(operations.submit(player) { 2 })
        completion.complete(1)
        assertTrue(pending.isCompletedExceptionally)
    }
}
