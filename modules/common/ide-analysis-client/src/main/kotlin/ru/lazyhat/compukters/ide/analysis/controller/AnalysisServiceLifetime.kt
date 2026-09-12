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

package ru.lazyhat.compukters.ide.analysis.controller

import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface AnalysisScheduledTask {
    fun cancel()
}

interface AnalysisTaskScheduler : AutoCloseable {
    fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): AnalysisScheduledTask
}

interface AnalysisSessionHandle : AutoCloseable {
    val client: AnalysisClient
}

interface AnalysisService : AutoCloseable {
    fun openSession(): AnalysisSessionHandle
}

class AnalysisServiceLifetime(
    private val idleDurationNanos: Long,
    private val scheduler: AnalysisTaskScheduler = ExecutorAnalysisTaskScheduler(),
    private val clientFactory: () -> AnalysisClient,
) : AnalysisService {
    private val lock = Any()
    private val sharedClient = ManagedClient()
    private var client: AnalysisClient? = null
    private var idleTask: AnalysisScheduledTask? = null
    private var idleGeneration = 0L
    private var sessions = 0
    private var closed = false

    init {
        require(idleDurationNanos >= 0) { "analysis idle duration must not be negative" }
    }

    override fun openSession(): AnalysisSessionHandle {
        synchronized(lock) {
            check(!closed) { "analysis service is closed" }
            sessions++
            idleGeneration++
            idleTask?.cancel()
            idleTask = null
        }
        return Session()
    }

    override fun close() {
        val detached: AnalysisClient?
        synchronized(lock) {
            if (closed) return
            closed = true
            sessions = 0
            idleGeneration++
            idleTask?.cancel()
            idleTask = null
            detached = client
            client = null
        }
        detached?.close()
        scheduler.close()
    }

    private fun releaseSession() {
        synchronized(lock) {
            if (closed) return
            check(sessions > 0) { "analysis session count underflow" }
            sessions--
            if (sessions != 0 || client == null) return
            val generation = ++idleGeneration
            idleTask?.cancel()
            idleTask = scheduler.schedule(idleDurationNanos) { expire(generation) }
        }
    }

    private fun expire(generation: Long) {
        val detached: AnalysisClient?
        synchronized(lock) {
            if (closed || sessions != 0 || generation != idleGeneration) return
            detached = client
            client = null
            idleTask = null
        }
        detached?.close()
    }

    private fun delegate(): AnalysisClient =
        synchronized(lock) {
            check(!closed) { "analysis service is closed" }
            check(sessions > 0) { "analysis session is closed" }
            client ?: clientFactory().also { client = it }
        }

    private inner class Session : AnalysisSessionHandle {
        private val sessionClosed = AtomicBoolean()
        override val client: AnalysisClient = sharedClient

        override fun close() {
            if (sessionClosed.compareAndSet(false, true)) releaseSession()
        }
    }

    private inner class ManagedClient : AnalysisClient {
        override fun open(snapshot: AdmittedAnalysisSnapshot): CompletableFuture<SnapshotOpenResult> = delegate().open(snapshot)

        override fun query(
            snapshot: AdmittedAnalysisSnapshot,
            query: AnalysisQuery,
        ): CompletableFuture<AnalysisClientResult> = delegate().query(snapshot, query)

        override fun cancel(future: CompletableFuture<AnalysisClientResult>): Boolean = delegate().cancel(future)

        override fun closeSnapshot(identity: AnalysisSnapshotIdentity): CompletableFuture<Unit> = delegate().closeSnapshot(identity)

        override fun close() = Unit
    }
}

private class ExecutorAnalysisTaskScheduler : AnalysisTaskScheduler {
    private val executor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "compukter-analysis-timer").apply { isDaemon = true }
        }

    override fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): AnalysisScheduledTask {
        require(delayNanos >= 0) { "analysis task delay must not be negative" }
        val future = executor.schedule(action, delayNanos, TimeUnit.NANOSECONDS)
        return AnalysisScheduledTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
