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

package ru.lazyhat.compukters.compiler.runtime

import ru.lazyhat.compukters.compiler.cache.PersistentCompilationCache
import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.worker.controller.CompilationCacheArtifact
import ru.lazyhat.compukters.compiler.worker.controller.CompilationIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class ServerCompilerService(
    private val cache: PersistentCompilationCache,
    private val backend: CompilerBackend,
    private val configuration: CompilerServiceConfiguration,
    private val policy: CompilerServicePolicy = CompilerServicePolicy(),
    private val executor: Executor,
) : CompilerServicePort,
    AutoCloseable {
    private val lock = Any()
    private val pending = mutableMapOf<CompilerTarget, ProjectSnapshot>()
    private val flights = mutableMapOf<Hash256, Flight>()
    private val completions = ArrayDeque<CompilerCompletion>()
    private val outstanding = mutableSetOf<CompilerTarget>()
    private var queuedSnapshotBytes = 0L
    private var completionArtifactBytes = 0L
    private var closed = false

    override fun submit(
        target: CompilerTarget,
        snapshot: ProjectSnapshot,
    ): CompilerSubmissionResult {
        synchronized(lock) {
            if (closed) return CompilerSubmissionResult.CLOSED
            if (target in outstanding || outstanding.size >= policy.maximumOutstandingTargets) {
                return CompilerSubmissionResult.BUSY
            }
            if (snapshot.totalSourceBytes > policy.maximumSnapshotBytes) return CompilerSubmissionResult.BUSY
            val reserved = Math.addExact(queuedSnapshotBytes, snapshot.totalSourceBytes)
            if (reserved > policy.maximumQueuedSnapshotBytes) return CompilerSubmissionResult.BUSY
            outstanding += target
            pending[target] = snapshot
            queuedSnapshotBytes = reserved
        }
        executor.execute { prepare(target, snapshot) }
        return CompilerSubmissionResult.ACCEPTED
    }

    override fun cancel(target: CompilerTarget): Boolean {
        var cancellation: Pair<CompletableFuture<CompileResult>, CompilerBackend>? = null
        synchronized(lock) {
            if (!outstanding.remove(target)) return false
            pending.remove(target)?.let { queuedSnapshotBytes -= it.totalSourceBytes }
            val iterator = completions.iterator()
            while (iterator.hasNext()) {
                val completion = iterator.next()
                if (completion.target == target) {
                    completionArtifactBytes -= completion.artifactBytes()
                    iterator.remove()
                }
            }
            flights.values.firstOrNull { target in it.waiters }?.let { flight ->
                flight.waiters.remove(target)
                if (flight.waiters.isEmpty()) {
                    flights.remove(flight.identity)
                    cancellation = flight.future to backend
                }
            }
        }
        cancellation?.let { (future, owner) -> owner.cancel(future) }
        return true
    }

    override fun drain(maximum: Int): List<CompilerCompletion> {
        require(maximum >= 0) { "completion drain limit must not be negative" }
        synchronized(lock) {
            val result = ArrayList<CompilerCompletion>(minOf(maximum, completions.size))
            repeat(minOf(maximum, completions.size)) {
                val completion = completions.removeFirst()
                completionArtifactBytes -= completion.artifactBytes()
                outstanding.remove(completion.target)
                result += completion
            }
            return result
        }
    }

    override fun close() {
        val active: List<CompletableFuture<CompileResult>>
        synchronized(lock) {
            if (closed) return
            closed = true
            active = flights.values.map(Flight::future)
            flights.clear()
            pending.clear()
            queuedSnapshotBytes = 0
            completions.clear()
            completionArtifactBytes = 0
            outstanding.clear()
        }
        active.forEach(backend::cancel)
        var failure: Throwable? = null
        try {
            backend.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            cache.close()
        } catch (error: Throwable) {
            failure = failure ?: error
        }
        failure?.let { throw it }
    }

    private fun prepare(
        target: CompilerTarget,
        snapshot: ProjectSnapshot,
    ) {
        synchronized(lock) {
            val admitted = pending.remove(target) ?: return
            queuedSnapshotBytes -= admitted.totalSourceBytes
            if (closed) return
        }
        val identity =
            try {
                CompilationIdentity.compute(request(snapshot))
            } catch (error: Exception) {
                complete(target, CompilerOutcome.PlatformFailure(error.message ?: "compilation identity failed"))
                return
            }
        val hit =
            try {
                cache.get(identity)
            } catch (error: Exception) {
                complete(target, CompilerOutcome.PlatformFailure(error.message ?: "compilation cache failed"))
                return
            }
        if (hit != null) {
            complete(target, CompilerOutcome.Success(hit, hash(hit), cacheHit = true))
            return
        }

        val flight =
            synchronized(lock) {
                if (target !in outstanding || closed) return
                flights[identity]?.let { flight ->
                    if (flight.waiters.size >= policy.maximumWaitersPerIdentity) {
                        completeLocked(target, CompilerOutcome.Busy)
                    } else {
                        flight.waiters += target
                    }
                    return
                }
                if (flights.size >= policy.maximumDistinctCompilations) {
                    completeLocked(target, CompilerOutcome.Busy)
                    return
                }
                try {
                    val future = backend.compile(snapshot, configuration.target, configuration.platformModules)
                    Flight(identity, mutableListOf(target), future).also { flights[identity] = it }
                } catch (error: Exception) {
                    complete(target, CompilerOutcome.PlatformFailure(error.message ?: "compiler backend failed"))
                    return
                }
            }
        flight.future.whenComplete { result, error -> executor.execute { finish(flight, result, error) } }
    }

    private fun finish(
        flight: Flight,
        result: CompileResult?,
        error: Throwable?,
    ) {
        val waiters =
            synchronized(lock) {
                if (flights.remove(flight.identity) !== flight || closed) return
                flight.waiters.filter { it in outstanding }
            }
        val outcome =
            when {
                error != null || result == null -> CompilerOutcome.PlatformFailure(error?.message ?: "compiler backend failed")
                result !is CompileSuccess -> CompilerOutcome.Rejected(result)
                else -> admit(flight.identity, result)
            }
        synchronized(lock) { waiters.forEach { completeLocked(it, outcome) } }
    }

    private fun admit(
        identity: Hash256,
        result: CompileSuccess,
    ): CompilerOutcome {
        val admitted =
            CompilationCacheArtifact.admit(result)
                ?: return CompilerOutcome.PlatformFailure("compiler artifact hash mismatch")
        return try {
            val artifact = cache.put(identity, admitted.artifact.toByteArray())
            CompilerOutcome.Success(artifact, hash(artifact), cacheHit = false)
        } catch (error: Exception) {
            CompilerOutcome.PlatformFailure(error.message ?: "compiler cache publication failed")
        }
    }

    private fun request(snapshot: ProjectSnapshot): CompileRequest =
        CompileRequest(
            RequestId.of(1uL),
            snapshot.sources,
            configuration.target,
            configuration.workerIdentity,
            configuration.limits,
            configuration.platformModules,
        )

    private fun complete(
        target: CompilerTarget,
        outcome: CompilerOutcome,
    ) = synchronized(lock) { completeLocked(target, outcome) }

    private fun completeLocked(
        target: CompilerTarget,
        outcome: CompilerOutcome,
    ) {
        if (target !in outstanding || completions.any { it.target == target }) return
        val bytes = (outcome as? CompilerOutcome.Success)?.artifact?.size?.toLong() ?: 0
        val admitted =
            if (bytes > 0 && completionArtifactBytes + bytes > policy.maximumCompletionArtifactBytes) {
                CompilerOutcome.PlatformFailure("compiler completion queue byte limit exceeded")
            } else {
                completionArtifactBytes += bytes
                outcome
            }
        completions += CompilerCompletion(target, admitted)
    }

    private fun hash(bytes: ByteArray): Hash256 =
        Hash256.of(
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes),
        )

    private data class Flight(
        val identity: Hash256,
        val waiters: MutableList<CompilerTarget>,
        val future: CompletableFuture<CompileResult>,
    )
}

private fun CompilerCompletion.artifactBytes(): Long = (outcome as? CompilerOutcome.Success)?.artifact?.size?.toLong() ?: 0
