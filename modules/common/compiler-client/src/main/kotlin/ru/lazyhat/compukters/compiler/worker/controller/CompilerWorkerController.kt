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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerFeature
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

enum class CompilerWorkerState { STOPPED, STARTING, IDLE, COMPILING, INVALID }

class WorkerQueueFullException : IllegalStateException("compiler worker queue is full")

data class CompilerWorkerPolicy(
    val startupTimeoutNanos: Long = 10_000_000_000,
    val compilationTimeoutNanos: Long = 30_000_000_000,
    val terminationGraceMillis: Long = 250,
) {
    init {
        require(startupTimeoutNanos >= 0) { "startup timeout must not be negative" }
        require(compilationTimeoutNanos >= 0) { "compilation timeout must not be negative" }
        require(terminationGraceMillis >= 0) { "termination grace must not be negative" }
    }
}

class CompilerWorkerController(
    private val payload: PublishedWorkerPayload,
    private val launch: WorkerLaunch,
    private val limits: WorkerLimits,
    private val processFactory: WorkerProcessFactory,
    private val policy: CompilerWorkerPolicy = CompilerWorkerPolicy(),
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val lock = Any()
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "compukter-compiler-controller").apply { isDaemon = true }
        }
    private var process: WorkerProcess? = null
    private var active: Pending? = null
    private var queued: Pending? = null
    private var nextRequestId = 1uL
    private var closed = false
    private var state = CompilerWorkerState.STOPPED

    init {
        require(launch.expectedIdentity == payload.manifest.identity) { "launch and payload identities must match" }
        require(launch.maximumFrameBytes == limits.frameBytes) { "launch and controller frame limits must match" }
        require(launch.maximumStderrBytes == limits.stderrBytes) { "launch and controller stderr limits must match" }
    }

    val currentState: CompilerWorkerState
        get() = synchronized(lock) { state }

    fun compile(
        snapshot: ProjectSnapshot,
        target: TargetSettings = TargetSettings.KOTLIN_2_4_JVM_17,
        platformModules: List<TrustedBundleIdentity> = emptyList(),
    ): CompletableFuture<CompileResult> {
        val future = CompletableFuture<CompileResult>()
        var startDrain = false
        synchronized(lock) {
            if (closed) {
                future.completeExceptionally(IllegalStateException("compiler worker controller is closed"))
                return future
            }
            if (active != null && queued != null) {
                future.completeExceptionally(WorkerQueueFullException())
                return future
            }
            check(nextRequestId != 0uL) { "request ID space exhausted" }
            val requestId = RequestId.of(nextRequestId)
            nextRequestId++
            val pending =
                Pending(
                    CompileRequest(
                        requestId,
                        snapshot.sources,
                        target,
                        launch.expectedIdentity,
                        limits,
                        platformModules,
                    ),
                    future,
                )
            if (active == null) {
                active = pending
                startDrain = true
            } else {
                queued = pending
            }
        }
        if (startDrain) executor.execute(::drain)
        return future
    }

    fun cancel(future: CompletableFuture<CompileResult>): Boolean {
        val pending: Pending
        val child: WorkerProcess?
        val activeCancellation: Boolean
        synchronized(lock) {
            if (future.isDone) return false
            val activePending = active
            val queuedPending = queued
            pending =
                when {
                    activePending?.future === future -> {
                        activePending.cancelled = true
                        state = CompilerWorkerState.INVALID
                        activePending
                    }

                    queuedPending?.future === future -> {
                        queued = null
                        queuedPending
                    }

                    else -> {
                        return false
                    }
                }
            child = if (pending === activePending) process.also { process = null } else null
            activeCancellation = pending === activePending
        }
        child?.terminate(policy.terminationGraceMillis)
        if (activeCancellation) {
            synchronized(lock) {
                if (!closed) state = CompilerWorkerState.STOPPED
            }
        }
        return pending.future.complete(
            PlatformFailure(pending.request.requestId, PlatformFailureClass.CANCELLED, "compilation cancelled"),
        )
    }

    override fun close() {
        val pending: List<Pending>
        val child: WorkerProcess?
        synchronized(lock) {
            if (closed) return
            closed = true
            state = CompilerWorkerState.INVALID
            pending = listOfNotNull(active, queued)
            active = null
            queued = null
            child = process
            process = null
        }
        child?.terminate(policy.terminationGraceMillis)
        pending.forEach { item ->
            item.future.complete(PlatformFailure(item.request.requestId, PlatformFailureClass.CANCELLED, "controller closed"))
        }
        executor.shutdownNow()
    }

    private fun drain() {
        while (true) {
            val pending = synchronized(lock) { active } ?: return
            val result = runRequest(pending)
            pending.future.complete(result)
            synchronized(lock) {
                if (active === pending) {
                    active = queued
                    queued = null
                }
                if (active == null) return
            }
        }
    }

    private fun runRequest(pending: Pending): CompileResult =
        try {
            val request = pending.request
            if (pending.cancelled) throw ControllerFault(PlatformFailureClass.CANCELLED, "compilation cancelled")
            val child = ensureWorker(pending)
            if (pending.cancelled) throw ControllerFault(PlatformFailureClass.CANCELLED, "compilation cancelled")
            setState(CompilerWorkerState.COMPILING)
            val outbound = encode(request)
            try {
                child.writeFrame(outbound)
            } catch (exception: Exception) {
                throw ControllerFault(PlatformFailureClass.WORKER_EXIT, exception.message ?: "worker input failed")
            }
            val frame =
                try {
                    child.readFrame(deadlineAfter(policy.compilationTimeoutNanos))
                } catch (_: WorkerDeadlineExceededException) {
                    throw ControllerFault(PlatformFailureClass.TIMEOUT, "compilation deadline exceeded")
                } catch (exception: Exception) {
                    throw ControllerFault(PlatformFailureClass.WORKER_EXIT, exception.message ?: "worker output failed")
                } ?: throw exitFault(child)
            val message = decode(frame)
            val result =
                message as? CompileResult ?: throw ControllerFault(PlatformFailureClass.PROTOCOL, "unexpected message while compiling")
            if (result.requestId != request.requestId) throw ControllerFault(PlatformFailureClass.PROTOCOL, "terminal request ID mismatch")
            validateResult(result)
            synchronized(lock) {
                if (pending.cancelled) throw ControllerFault(PlatformFailureClass.CANCELLED, "compilation cancelled")
                state = CompilerWorkerState.IDLE
            }
            result
        } catch (fault: ControllerFault) {
            invalidate()
            PlatformFailure(pending.request.requestId, fault.failureClass, boundedDetail(fault.message.orEmpty()))
        } catch (exception: Exception) {
            invalidate()
            PlatformFailure(pending.request.requestId, PlatformFailureClass.PROTOCOL, boundedDetail(exception.message.orEmpty()))
        }

    private fun ensureWorker(pending: Pending): WorkerProcess {
        synchronized(lock) {
            process?.takeIf(WorkerProcess::isAlive)?.let { return it }
            state = CompilerWorkerState.STARTING
        }
        val child =
            try {
                processFactory.start(launch.processLaunch(payload))
            } catch (exception: Exception) {
                throw ControllerFault(PlatformFailureClass.WORKER_STARTUP, exception.message ?: "worker failed to start")
            }
        val cancelled =
            synchronized(lock) {
                if (pending.cancelled || closed) {
                    true
                } else {
                    process = child
                    false
                }
            }
        if (cancelled) {
            child.terminate(policy.terminationGraceMillis)
            throw ControllerFault(PlatformFailureClass.CANCELLED, "compilation cancelled")
        }
        val frame =
            try {
                child.readFrame(deadlineAfter(policy.startupTimeoutNanos))
            } catch (_: WorkerDeadlineExceededException) {
                throw ControllerFault(PlatformFailureClass.WORKER_STARTUP, "worker handshake deadline exceeded")
            } catch (exception: Exception) {
                throw ControllerFault(PlatformFailureClass.WORKER_STARTUP, exception.message ?: "worker handshake failed")
            } ?: throw ControllerFault(PlatformFailureClass.WORKER_STARTUP, "worker exited before handshake")
        val handshake =
            decode(frame) as? WorkerHandshake ?: throw ControllerFault(PlatformFailureClass.PROTOCOL, "expected worker handshake")
        if (handshake.identity != launch.expectedIdentity) throw ControllerFault(PlatformFailureClass.PROTOCOL, "worker identity mismatch")
        if (!handshake.features.containsAll(
                REQUIRED_FEATURES,
            )
        ) {
            throw ControllerFault(PlatformFailureClass.PROTOCOL, "worker features mismatch")
        }
        if (handshake.limits != limits) throw ControllerFault(PlatformFailureClass.PROTOCOL, "worker limits mismatch")
        setState(CompilerWorkerState.IDLE)
        return child
    }

    private fun validateResult(result: CompileResult) {
        when (result) {
            is CompileSuccess -> {
                if (result.artifact.size >
                    limits.artifactBytes
                ) {
                    throw ControllerFault(PlatformFailureClass.PROTOCOL, "artifact exceeds limit")
                }
                val actualHash = Hashing.sha256(result.artifact.toByteArray())
                if (actualHash != result.artifactHash) throw ControllerFault(PlatformFailureClass.PROTOCOL, "artifact hash mismatch")
                validateDiagnostics(result.warnings)
            }

            is CompilerFailure -> {
                validateDiagnostics(result.diagnostics)
            }

            is PlatformFailure -> {
                throw ControllerFault(result.failureClass, result.detail)
            }
        }
    }

    private fun validateDiagnostics(diagnostics: List<ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic>) {
        if (diagnostics.size > limits.diagnostics) throw ControllerFault(PlatformFailureClass.PROTOCOL, "diagnostic count exceeds limit")
        val bytes =
            diagnostics.fold(0L) { total, diagnostic ->
                Math.addExact(
                    total,
                    diagnostic.message
                        .encodeToByteArray()
                        .size
                        .toLong(),
                )
            }
        if (bytes > limits.diagnosticTextBytes) throw ControllerFault(PlatformFailureClass.PROTOCOL, "diagnostic text exceeds limit")
    }

    private fun encode(message: WorkerMessage): ByteArray {
        val frame = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))
        if (frame.size > limits.frameBytes) throw ControllerFault(PlatformFailureClass.PROTOCOL, "request frame exceeds limit")
        return frame
    }

    private fun decode(frame: ByteArray): WorkerMessage =
        try {
            WorkerMessageCodec.decode(WorkerCodec.decodeFrame(frame, limits.frameBytes))
        } catch (exception: IllegalArgumentException) {
            throw ControllerFault(PlatformFailureClass.PROTOCOL, exception.message ?: "malformed worker frame")
        }

    private fun invalidate() {
        val child =
            synchronized(lock) {
                state = CompilerWorkerState.INVALID
                process.also { process = null }
            }
        child?.terminate(policy.terminationGraceMillis)
        synchronized(lock) {
            if (!closed) state = CompilerWorkerState.STOPPED
        }
    }

    private fun setState(value: CompilerWorkerState) {
        synchronized(lock) { state = value }
    }

    private data class Pending(
        val request: CompileRequest,
        val future: CompletableFuture<CompileResult>,
        @Volatile var cancelled: Boolean = false,
    )

    private class ControllerFault(
        val failureClass: PlatformFailureClass,
        message: String,
    ) : IllegalStateException(message)

    private object Hashing {
        fun sha256(bytes: ByteArray) =
            ru.lazyhat.compukters.compiler.worker.protocol.Hash256
                .of(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private companion object {
        const val MAX_FAILURE_DETAIL_BYTES = 4096
        val REQUIRED_FEATURES = setOf(WorkerFeature.PROJECT_SNAPSHOT, WorkerFeature.KOTLIN_IR)
        val MEMORY_FAILURE_MARKERS = listOf("OutOfMemoryError", "Java heap space", "Metaspace")
    }

    private fun deadlineAfter(durationNanos: Long): Long = nanoTime() + durationNanos

    private fun boundedDetail(value: String): String {
        val maximumBytes = minOf(MAX_FAILURE_DETAIL_BYTES, limits.diagnosticTextBytes)
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val encodedBytes = text.encodeToByteArray().size
            if (bytes + encodedBytes > maximumBytes) break
            result.append(text)
            bytes += encodedBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun exitFault(child: WorkerProcess): ControllerFault {
        val stderr = child.stderrSnapshot().decodeToString()
        val memoryLimit = MEMORY_FAILURE_MARKERS.any(stderr::contains)
        val failureClass = if (memoryLimit) PlatformFailureClass.MEMORY_LIMIT else PlatformFailureClass.WORKER_EXIT
        val exit = child.exitCode?.let { " (exit $it)" }.orEmpty()
        val detail = stderr.ifBlank { "worker closed stdout$exit" }
        return ControllerFault(failureClass, boundedDetail(detail))
    }
}
