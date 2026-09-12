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

package ru.lazyhat.compukters.ide.compiler

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.worker.controller.CompilationCacheArtifact
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.WorkerQueueFullException
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure as WorkerPlatformFailure

sealed interface ClientBuildResult {
    val identity: Hash256

    data class Success(
        override val identity: Hash256,
        val artifact: BinaryValue,
        val artifactHash: Hash256,
        val cacheHit: Boolean,
    ) : ClientBuildResult

    data class Diagnostics(
        override val identity: Hash256,
        val values: List<WorkerDiagnostic>,
    ) : ClientBuildResult

    data class PlatformFailure(
        override val identity: Hash256,
        val failureClass: PlatformFailureClass,
        val detail: String,
    ) : ClientBuildResult
}

interface ClientCompilationService : AutoCloseable {
    fun build(input: ClientBuildSnapshot): CompletableFuture<ClientBuildResult>

    fun cancel(future: CompletableFuture<ClientBuildResult>): Boolean
}

interface ClientCompilerBackend : AutoCloseable {
    fun compile(request: CompileRequest): CompletableFuture<CompileResult>

    fun cancel(future: CompletableFuture<CompileResult>): Boolean
}

class ControllerClientCompilerBackend(
    private val controller: CompilerWorkerController,
) : ClientCompilerBackend {
    override fun compile(request: CompileRequest): CompletableFuture<CompileResult> =
        controller.compile(
            ProjectSnapshot.of(request.sources, request.limits),
            request.target,
            request.platformModules,
        )

    override fun cancel(future: CompletableFuture<CompileResult>): Boolean = controller.cancel(future)

    override fun close() = controller.close()
}

class DefaultClientCompilationService(
    private val cache: ClientCompilationCache,
    private val backend: ClientCompilerBackend,
    private val executor: ExecutorService = newExecutor(),
) : ClientCompilationService {
    private val lock = Any()
    private var active: Build? = null
    private var queued: Build? = null
    private var closed = false

    override fun build(input: ClientBuildSnapshot): CompletableFuture<ClientBuildResult> {
        val prepared = ClientCompileRequestFactory.prepare(input)
        var start: Build? = null
        synchronized(lock) {
            if (closed) return CompletableFuture.failedFuture(IllegalStateException("client compilation service is closed"))
            active?.takeIf { build -> build.prepared.identity == prepared.identity }?.let { return it.future }
            queued?.takeIf { build -> build.prepared.identity == prepared.identity }?.let { return it.future }
            if (active != null && queued != null) return CompletableFuture.failedFuture(WorkerQueueFullException())
            val build = Build(prepared)
            if (active == null) {
                active = build
                start = build
            } else {
                queued = build
            }
            if (start == null) return build.future
        }
        executor.execute { begin(checkNotNull(start)) }
        return checkNotNull(start).future
    }

    override fun cancel(future: CompletableFuture<ClientBuildResult>): Boolean {
        var workerFuture: CompletableFuture<CompileResult>? = null
        var queuedBuild: Build? = null
        synchronized(lock) {
            val pending = queued
            if (pending?.future === future) {
                queued = null
                pending.cancelRequested = true
                queuedBuild = pending
            } else {
                val running = active
                if (running?.future !== future || running.cancelRequested || running.future.isDone) return false
                running.cancelRequested = true
                workerFuture = running.workerFuture
            }
        }
        queuedBuild?.let { build ->
            build.future.complete(cancelled(build.prepared.identity))
            return true
        }
        val runningWorker = workerFuture
        if (runningWorker != null) executor.execute { backend.cancel(runningWorker) }
        return true
    }

    override fun close() {
        val builds: List<Build>
        synchronized(lock) {
            if (closed) return
            closed = true
            builds = listOfNotNull(active, queued)
            active = null
            queued = null
            builds.forEach { build -> build.cancelRequested = true }
        }
        val cleanup =
            Runnable {
                builds.forEach { build -> build.workerFuture?.let(backend::cancel) }
                backend.close()
                cache.close()
                builds.forEach { build -> build.future.complete(cancelled(build.prepared.identity)) }
            }
        val cleanupFuture = executor.submit(cleanup)
        try {
            cleanupFuture.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }
    }

    private fun begin(build: Build) {
        if (!isCurrent(build)) return
        if (build.cancelRequested) {
            finish(build, cancelled(build.prepared.identity))
            return
        }
        try {
            val cached = cache.get(build.prepared.identity)
            if (cached != null) {
                val hash = Hash256.of(MessageDigest.getInstance("SHA-256").digest(cached))
                finish(build, ClientBuildResult.Success(build.prepared.identity, BinaryValue.of(cached), hash, cacheHit = true))
                return
            }
            val worker = backend.compile(build.prepared.request)
            synchronized(lock) {
                if (active !== build) return
                build.workerFuture = worker
            }
            if (build.cancelRequested) backend.cancel(worker)
            worker.whenCompleteAsync(
                { result, error ->
                    if (error != null) {
                        finish(
                            build,
                            ClientBuildResult.PlatformFailure(
                                build.prepared.identity,
                                PlatformFailureClass.INTERNAL_COMPILER,
                                error.message ?: error::class.java.simpleName,
                            ),
                        )
                    } else {
                        finish(build, result(build, checkNotNull(result)))
                    }
                },
                executor,
            )
        } catch (error: Throwable) {
            finish(
                build,
                ClientBuildResult.PlatformFailure(
                    build.prepared.identity,
                    PlatformFailureClass.INTERNAL_COMPILER,
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }
    }

    private fun result(
        build: Build,
        result: CompileResult,
    ): ClientBuildResult =
        when (result) {
            is CompileSuccess -> {
                val admitted = CompilationCacheArtifact.admit(result)
                if (admitted == null) {
                    ClientBuildResult.PlatformFailure(
                        build.prepared.identity,
                        PlatformFailureClass.INTERNAL_COMPILER,
                        "compiler returned an invalid artifact hash",
                    )
                } else {
                    val stored = cache.put(build.prepared.identity, admitted.artifact.toByteArray())
                    ClientBuildResult.Success(
                        build.prepared.identity,
                        BinaryValue.of(stored),
                        admitted.artifactHash,
                        cacheHit = false,
                    )
                }
            }

            is CompilerFailure -> {
                ClientBuildResult.Diagnostics(build.prepared.identity, result.diagnostics)
            }

            is WorkerPlatformFailure -> {
                ClientBuildResult.PlatformFailure(build.prepared.identity, result.failureClass, result.detail)
            }
        }

    private fun finish(
        build: Build,
        result: ClientBuildResult,
    ) {
        var next: Build? = null
        synchronized(lock) {
            if (active !== build) return
            active = queued
            queued = null
            next = active
        }
        build.future.complete(if (build.cancelRequested) cancelled(build.prepared.identity) else result)
        next?.let { queuedBuild -> executor.execute { begin(queuedBuild) } }
    }

    private fun isCurrent(build: Build): Boolean = synchronized(lock) { active === build }

    private data class Build(
        val prepared: PreparedClientCompilation,
        val future: CompletableFuture<ClientBuildResult> = CompletableFuture(),
        var workerFuture: CompletableFuture<CompileResult>? = null,
        var cancelRequested: Boolean = false,
    )

    companion object {
        private const val CLOSE_TIMEOUT_SECONDS = 30L
        private val THREAD_IDS = AtomicLong()

        private fun newExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor(
                ThreadFactory { runnable ->
                    Thread(runnable, "compukters-client-compilation-${THREAD_IDS.incrementAndGet()}").apply { isDaemon = true }
                },
            )
    }
}

private fun cancelled(identity: Hash256) =
    ClientBuildResult.PlatformFailure(identity, PlatformFailureClass.CANCELLED, "client compilation cancelled")
