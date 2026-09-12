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

package ru.lazyhat.compukters.ide.analysis.k2.server

import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AnalysisCancellation {
    private val cancelled = AtomicBoolean()
    private val lock = Any()
    private val callbacks = mutableListOf<() -> Unit>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val actions = synchronized(lock) { callbacks.toList().also { callbacks.clear() } }
        actions.forEach { action -> runCatching(action) }
    }

    fun invokeOnCancellation(action: () -> Unit) {
        synchronized(lock) {
            if (!cancelled.get()) {
                callbacks += action
                return
            }
        }
        runCatching(action)
    }
}

internal class AnalysisExecutionQueue(
    private val maximumQueued: Int,
) : AutoCloseable {
    private val lock = Any()
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "compukter-k2-analysis").apply { isDaemon = true }
        }
    private val queued = ArrayDeque<Work>()
    private var active: Work? = null
    private var closed = false

    init {
        require(maximumQueued >= 0) { "maximum queued analysis work must not be negative" }
    }

    fun submit(
        requestId: RequestId,
        task: (AnalysisCancellation) -> Unit,
        onCancelled: () -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean {
        val start: Boolean
        synchronized(lock) {
            if (closed || contains(requestId)) return false
            val work = Work(requestId, AnalysisCancellation(), task, onCancelled, onFailure)
            if (active == null) {
                active = work
                start = true
            } else {
                if (queued.size >= maximumQueued) return false
                queued += work
                start = false
            }
        }
        if (start) executor.execute(::drain)
        return true
    }

    fun cancel(requestId: RequestId): Boolean {
        val removed: Work?
        synchronized(lock) {
            active?.takeIf { it.requestId == requestId }?.let {
                it.cancellation.cancel()
                return true
            }
            removed = queued.firstOrNull { it.requestId == requestId }
            if (removed != null) queued.remove(removed)
        }
        removed ?: return false
        removed.cancellation.cancel()
        runCatching(removed.onCancelled)
        return true
    }

    override fun close() {
        val abandoned: List<Work>
        synchronized(lock) {
            if (closed) return
            closed = true
            active?.cancellation?.cancel()
            abandoned = queued.toList()
            queued.clear()
        }
        abandoned.forEach { work ->
            work.cancellation.cancel()
            runCatching(work.onCancelled)
        }
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) { active } ?: return
            try {
                if (!work.cancellation.isCancelled) work.task(work.cancellation)
            } catch (throwable: Throwable) {
                if (!work.cancellation.isCancelled) runCatching { work.onFailure(throwable) }
            } finally {
                if (work.cancellation.isCancelled) runCatching(work.onCancelled)
                synchronized(lock) {
                    if (active === work) active = queued.removeFirstOrNull()
                    if (active == null) return
                }
            }
        }
    }

    private fun contains(requestId: RequestId): Boolean = active?.requestId == requestId || queued.any { it.requestId == requestId }

    private class Work(
        val requestId: RequestId,
        val cancellation: AnalysisCancellation,
        val task: (AnalysisCancellation) -> Unit,
        val onCancelled: () -> Unit,
        val onFailure: (Throwable) -> Unit,
    )
}
