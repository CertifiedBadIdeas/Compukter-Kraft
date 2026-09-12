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
 */

package ru.lazyhat.compukters.impl.ide.target

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeTargetRequestBrokerTest {
    @Test
    fun `out of order replies complete only their correlated request once`() {
        val sent = mutableListOf<IdeTargetRequestPayload>()
        val broker = IdeTargetRequestBroker(sent::add)
        val first = broker.request(IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(1))))
        val second = broker.request(IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(2))))

        assertEquals(listOf(1L, 2L), sent.map(IdeTargetRequestPayload::requestId))
        broker.receive(IdeTargetReplyPayload(2, IdeTargetReply.Alive))
        assertEquals(IdeTargetReply.Alive, second.join())
        assertFalse(first.isDone)
        broker.receive(IdeTargetReplyPayload(2, IdeTargetReply.Detached))
        assertEquals(IdeTargetReply.Alive, second.join())
        broker.receive(IdeTargetReplyPayload(1, IdeTargetReply.Detached))
        assertEquals(IdeTargetReply.Detached, first.join())
    }

    @Test
    fun `pending limit and disconnect fail requests without sending more payloads`() {
        val sent = mutableListOf<IdeTargetRequestPayload>()
        val broker = IdeTargetRequestBroker(sent::add, maximumPendingRequests = 1)
        val pending = broker.request(IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(1))))
        val rejected = broker.request(IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(2))))

        assertEquals(1, sent.size)
        assertIsFailure(rejected)
        broker.disconnect()
        assertIsFailure(pending)
        assertTrue(broker.request(IdeTargetRequest.Attach(BinaryValue.of(byteArrayOf(3)))).isCompletedExceptionally)
        broker.disconnect()
    }

    private fun assertIsFailure(future: java.util.concurrent.CompletableFuture<*>) {
        assertTrue(future.isCompletedExceptionally)
        assertFailsWith<CompletionException> { future.join() }
    }
}
