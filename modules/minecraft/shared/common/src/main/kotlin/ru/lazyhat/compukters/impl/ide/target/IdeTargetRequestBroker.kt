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

import java.util.concurrent.CompletableFuture

class IdeTargetRequestBroker(
    private val send: (IdeTargetRequestEnvelope) -> Unit,
    private val maximumPendingRequests: Int = DEFAULT_MAXIMUM_PENDING_REQUESTS,
) : IdeTargetRequestChannel {
    private val lock = Any()
    private val pending = mutableMapOf<Long, CompletableFuture<IdeTargetReply>>()
    private var nextRequestId = 1L
    private var closed = false

    init {
        require(maximumPendingRequests > 0) { "maximum pending request count must be positive" }
    }

    override fun request(request: IdeTargetRequest): CompletableFuture<IdeTargetReply> {
        val registration =
            synchronized(lock) {
                if (closed) return failed("IDE target connection is closed")
                if (pending.size >= maximumPendingRequests) return failed("Too many pending IDE target requests")
                val requestId = allocateRequestId()
                val future = CompletableFuture<IdeTargetReply>()
                pending[requestId] = future
                Registration(IdeTargetRequestEnvelope(requestId, request), future)
            }
        try {
            send(registration.payload)
        } catch (error: Exception) {
            val removed = synchronized(lock) { pending.remove(registration.payload.requestId, registration.future) }
            if (removed) registration.future.completeExceptionally(error)
        }
        return registration.future
    }

    fun receive(envelope: IdeTargetReplyEnvelope) {
        val future = synchronized(lock) { pending.remove(envelope.requestId) } ?: return
        future.complete(envelope.reply)
    }

    override fun disconnect() {
        val abandoned =
            synchronized(lock) {
                if (closed) return
                closed = true
                pending.values.toList().also { pending.clear() }
            }
        val failure = IdeTargetConnectionException("IDE target connection was disconnected")
        abandoned.forEach { future -> future.completeExceptionally(failure) }
    }

    private fun allocateRequestId(): Long {
        repeat(maximumPendingRequests + 1) {
            val candidate = nextRequestId
            nextRequestId = if (candidate == Long.MAX_VALUE) 1L else candidate + 1
            if (candidate !in pending) return candidate
        }
        error("no IDE target request ID is available")
    }

    private data class Registration(
        val payload: IdeTargetRequestEnvelope,
        val future: CompletableFuture<IdeTargetReply>,
    )

    private companion object {
        const val DEFAULT_MAXIMUM_PENDING_REQUESTS = 64

        fun failed(message: String): CompletableFuture<IdeTargetReply> =
            CompletableFuture.failedFuture(IdeTargetConnectionException(message))
    }
}

class IdeTargetConnectionException(
    message: String,
) : IllegalStateException(message)
