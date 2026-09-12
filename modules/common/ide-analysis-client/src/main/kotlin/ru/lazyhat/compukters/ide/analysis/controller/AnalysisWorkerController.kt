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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.protocol.ANALYSIS_PROTOCOL_VERSION
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisCancelled
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailure
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFeature
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisHandshake
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessage
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQuerySuccess
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.CancelAnalysisRequest
import ru.lazyhat.compukters.ide.analysis.protocol.CloseSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotClosed
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReady
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReopenRequired
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotUpdated
import ru.lazyhat.compukters.ide.analysis.protocol.UpdateSnapshotRequest
import ru.lazyhat.compukters.worker.process.WorkerDeadlineExceededException
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

data class AdmittedAnalysisSnapshot(
    val identity: AnalysisSnapshotIdentity,
    val sources: ProjectSnapshot,
    val profile: AdmittedAnalysisProfile,
    val limits: AnalysisLimits,
) {
    internal val protocolContext: AnalysisProtocolContext by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnalysisProtocolContext.of(sources, profile, limits)
    }

    init {
        OpenSnapshotRequest(RequestId.of(1uL), identity, sources, profile, limits)
    }
}

sealed interface SnapshotOpenResult {
    data class Opened(
        val identity: AnalysisSnapshotIdentity,
    ) : SnapshotOpenResult

    data object Stale : SnapshotOpenResult

    data class Failure(
        val kind: AnalysisFailureKind,
        val detail: String,
    ) : SnapshotOpenResult
}

sealed interface AnalysisClientResult {
    data class Success(
        val result: AnalysisResult,
    ) : AnalysisClientResult

    data object Stale : AnalysisClientResult

    data object Cancelled : AnalysisClientResult

    data class Failure(
        val kind: AnalysisFailureKind,
        val detail: String,
    ) : AnalysisClientResult
}

interface AnalysisClient : AutoCloseable {
    fun open(snapshot: AdmittedAnalysisSnapshot): CompletableFuture<SnapshotOpenResult>

    fun query(
        snapshot: AdmittedAnalysisSnapshot,
        query: AnalysisQuery,
    ): CompletableFuture<AnalysisClientResult>

    fun cancel(future: CompletableFuture<AnalysisClientResult>): Boolean

    fun closeSnapshot(identity: AnalysisSnapshotIdentity): CompletableFuture<Unit>
}

class AnalysisWorkerController(
    private val launch: WorkerLaunch,
    private val expectedWorkerIdentity: AnalysisWorkerIdentity,
    private val limits: AnalysisLimits,
    private val processFactory: WorkerProcessFactory,
    private val policy: AnalysisWorkerPolicy = AnalysisWorkerPolicy(),
    private val nanoTime: () -> Long = System::nanoTime,
) : AnalysisClient {
    private val lock = Any()
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "compukter-analysis-controller").apply { isDaemon = true }
        }
    private val scheduler = AnalysisScheduler<Pending>()
    private var retainedSnapshot: AdmittedAnalysisSnapshot? = null
    private var process: WorkerProcess? = null
    private var workerSnapshot: AdmittedAnalysisSnapshot? = null
    private var nextRequestId = 1uL
    private var draining = false
    private var closed = false

    init {
        require(launch.maximumFrameBytes >= limits.frameBytes) { "process frame limit is below analysis protocol limit" }
    }

    override fun open(snapshot: AdmittedAnalysisSnapshot): CompletableFuture<SnapshotOpenResult> {
        val future = CompletableFuture<SnapshotOpenResult>()
        val child: WorkerProcess?
        val stale: List<Pending>
        synchronized(lock) {
            if (closed) return failedFuture("analysis controller is closed")
            retainedSnapshot = snapshot
            val displaced = scheduler.clear()
            child = if (displaced.isNotEmpty()) detachProcess() else null
            stale = displaced.map { it.value } + enqueueLocked(Pending.Open(nextRequestId(), snapshot, future))
        }
        stale.forEach(::completeStale)
        child?.terminate(policy.terminationGraceMillis)
        return future
    }

    override fun query(
        snapshot: AdmittedAnalysisSnapshot,
        query: AnalysisQuery,
    ): CompletableFuture<AnalysisClientResult> {
        require(snapshot.identity == query.identity) { "analysis query identity does not match its snapshot" }
        val future = CompletableFuture<AnalysisClientResult>()
        val stale: List<Pending>
        synchronized(lock) {
            if (closed) return failedFuture("analysis controller is closed")
            retainedSnapshot = snapshot
            stale =
                scheduler.dropQueuedExcept(snapshot.identity).map { it.value } +
                enqueueLocked(Pending.Query(nextRequestId(), snapshot, query, workKind(query), future))
        }
        stale.forEach(::completeStale)
        return future
    }

    override fun cancel(future: CompletableFuture<AnalysisClientResult>): Boolean {
        val pending: Pending.Query
        val child: WorkerProcess?
        val active: Boolean
        synchronized(lock) {
            if (future.isDone) return false
            pending = schedulerItems().filterIsInstance<Pending.Query>().firstOrNull { it.future === future } ?: return false
            active = scheduler.active?.value === pending
            if (active && pending.querySent && !pending.awaitingResponse) return false
            pending.cancelled = true
            if (active) {
                child = process.takeIf { pending.awaitingResponse }
            } else {
                scheduler.remove(pending)
                child = null
            }
        }
        if (active) child?.let { safelySendCancel(it, pending.requestId) }
        if (!active || child == null) pending.future.complete(AnalysisClientResult.Cancelled)
        return true
    }

    override fun closeSnapshot(identity: AnalysisSnapshotIdentity): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        var immediate = false
        var stale = emptyList<Pending>()
        synchronized(lock) {
            if (closed) return failedFuture("analysis controller is closed")
            if (retainedSnapshot?.identity != identity) {
                immediate = true
            } else {
                stale = enqueueLocked(Pending.Close(nextRequestId(), identity, future))
            }
        }
        stale.forEach(::completeStale)
        if (immediate) future.complete(Unit)
        return future
    }

    override fun close() {
        val child: WorkerProcess?
        val pending: List<ScheduledAnalysisWork<Pending>>
        synchronized(lock) {
            if (closed) return
            closed = true
            retainedSnapshot = null
            pending = scheduler.clear()
            child = detachProcess()
        }
        pending.forEach { completeCancelled(it.value) }
        child?.terminate(policy.terminationGraceMillis)
        executor.shutdownNow()
    }

    private fun enqueueLocked(pending: Pending): List<Pending> {
        val update = scheduler.offer(ScheduledAnalysisWork(pending.identity, pending.kind, pending))
        if (update.accepted && !draining) {
            draining = true
            executor.execute(::drain)
        }
        return update.displaced.map { it.value }
    }

    private fun drain() {
        while (true) {
            val pending =
                synchronized(lock) {
                    scheduler.active?.value.also { if (it == null) draining = false }
                } ?: return
            runPending(pending)
            synchronized(lock) {
                if (scheduler.active?.value === pending) scheduler.completeActive()
                if (scheduler.active == null) {
                    draining = false
                    return
                }
            }
        }
    }

    private fun runPending(pending: Pending) {
        if (pending.cancelled || pending.isDone()) return
        try {
            when (pending) {
                is Pending.Open -> runOpen(pending)
                is Pending.Query -> runQuery(pending)
                is Pending.Close -> runClose(pending)
            }
        } catch (fault: ControllerFault) {
            invalidate()
            completeFailure(pending, fault.kind, boundedDetail(fault.message.orEmpty()))
        } catch (exception: Exception) {
            invalidate()
            completeFailure(pending, AnalysisFailureKind.Protocol, boundedDetail(exception.message.orEmpty()))
        }
    }

    private fun runOpen(pending: Pending.Open) {
        val child = ensureWorker()
        send(
            child,
            OpenSnapshotRequest(pending.requestId, pending.snapshot.identity, pending.snapshot.sources, pending.snapshot.profile, limits),
            context(pending.snapshot),
        )
        val response = receive(child, policy.requestTimeoutNanos, context(pending.snapshot))
        if (response is AnalysisFailure) {
            if (response.requestId != pending.requestId || response.identity != pending.snapshot.identity) {
                throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot failure response mismatch")
            }
            pending.future.complete(SnapshotOpenResult.Failure(response.failure, response.detail))
            return
        }
        val ready = response as? SnapshotReady ?: throw ControllerFault(AnalysisFailureKind.Protocol, "expected snapshot-ready response")
        if (ready.requestId != pending.requestId || ready.identity != pending.snapshot.identity) {
            throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot-ready response mismatch")
        }
        val result =
            synchronized(lock) {
                workerSnapshot = pending.snapshot
                if (retainedSnapshot?.identity == pending.snapshot.identity) {
                    SnapshotOpenResult.Opened(pending.snapshot.identity)
                } else {
                    SnapshotOpenResult.Stale
                }
            }
        if (!pending.future.isDone) pending.future.complete(result)
    }

    private fun runQuery(pending: Pending.Query) {
        if (synchronized(lock) { retainedSnapshot?.identity } != pending.snapshot.identity) {
            pending.future.complete(AnalysisClientResult.Stale)
            return
        }
        if (pending.cancelled) {
            pending.future.complete(AnalysisClientResult.Cancelled)
            return
        }
        val child = ensureWorker()
        if (pending.cancelled) {
            pending.future.complete(AnalysisClientResult.Cancelled)
            return
        }
        ensureSnapshot(child, pending.snapshot)
        if (synchronized(lock) { workerSnapshot?.identity } != pending.snapshot.identity) {
            pending.future.complete(AnalysisClientResult.Stale)
            return
        }
        val queryContext = context(pending.snapshot).forQuery(pending.query)
        val sent =
            synchronized(lock) {
                if (pending.cancelled) {
                    false
                } else {
                    send(child, AnalysisQueryRequest(pending.requestId, pending.query), queryContext)
                    pending.querySent = true
                    pending.awaitingResponse = true
                    true
                }
            }
        if (!sent) {
            pending.future.complete(AnalysisClientResult.Cancelled)
            return
        }
        val response = receiveQueryResponse(child, pending, queryContext)
        when (response) {
            is AnalysisQuerySuccess -> {
                if (response.requestId != pending.requestId || response.result.identity != pending.query.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "analysis result response mismatch")
                }
                val result =
                    synchronized(lock) {
                        if (pending.cancelled) {
                            AnalysisClientResult.Cancelled
                        } else if (retainedSnapshot?.identity == pending.query.identity) {
                            AnalysisClientResult.Success(response.result)
                        } else {
                            AnalysisClientResult.Stale
                        }
                    }
                pending.future.complete(result)
            }

            is AnalysisCancelled -> {
                if (response.requestId != pending.requestId || response.identity != pending.query.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "analysis cancellation response mismatch")
                }
                pending.future.complete(AnalysisClientResult.Cancelled)
            }

            is AnalysisFailure -> {
                if (response.requestId != pending.requestId || response.identity != pending.query.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "analysis failure response mismatch")
                }
                if (pending.cancelled) {
                    pending.future.complete(AnalysisClientResult.Cancelled)
                } else {
                    pending.future.complete(AnalysisClientResult.Failure(response.failure, response.detail))
                }
            }

            else -> {
                throw ControllerFault(AnalysisFailureKind.Protocol, "unexpected analysis response")
            }
        }
    }

    private fun receiveQueryResponse(
        child: WorkerProcess,
        pending: Pending.Query,
        queryContext: AnalysisProtocolContext,
    ): AnalysisMessage {
        try {
            var response = receive(child, policy.requestTimeoutNanos, queryContext)
            validateQueryResponse(pending, response)
            val awaitCancellation =
                synchronized(lock) {
                    if (pending.cancelled && response !is AnalysisCancelled) {
                        true
                    } else {
                        pending.awaitingResponse = false
                        false
                    }
                }
            if (awaitCancellation) {
                response = receive(child, policy.requestTimeoutNanos, queryContext)
                validateQueryResponse(pending, response)
                if (response !is AnalysisCancelled) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "expected analysis cancellation response")
                }
            }
            return response
        } finally {
            synchronized(lock) { pending.awaitingResponse = false }
        }
    }

    private fun validateQueryResponse(
        pending: Pending.Query,
        response: AnalysisMessage,
    ) {
        when (response) {
            is AnalysisQuerySuccess -> {
                if (response.requestId != pending.requestId || response.result.identity != pending.query.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "analysis result response mismatch")
                }
            }

            is AnalysisCancelled -> {
                if (response.requestId != pending.requestId || response.identity != pending.query.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "analysis cancellation response mismatch")
                }
            }

            is AnalysisFailure -> {
                if (response.requestId != pending.requestId || response.identity != pending.query.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "analysis failure response mismatch")
                }
            }

            else -> {
                throw ControllerFault(AnalysisFailureKind.Protocol, "unexpected analysis response")
            }
        }
    }

    private fun runClose(pending: Pending.Close) {
        val child = synchronized(lock) { process }
        if (child == null || workerSnapshot?.identity != pending.identity) {
            synchronized(lock) {
                if (retainedSnapshot?.identity == pending.identity) retainedSnapshot = null
            }
            pending.future.complete(Unit)
            return
        }
        send(child, CloseSnapshotRequest(pending.requestId, pending.identity), AnalysisProtocolContext.unchecked())
        val response =
            receive(child, policy.requestTimeoutNanos, AnalysisProtocolContext.unchecked()) as? SnapshotClosed
                ?: throw ControllerFault(AnalysisFailureKind.Protocol, "expected snapshot-closed response")
        if (response.requestId != pending.requestId || response.identity != pending.identity) {
            throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot-closed response mismatch")
        }
        synchronized(lock) {
            workerSnapshot = null
            if (retainedSnapshot?.identity == pending.identity) retainedSnapshot = null
        }
        pending.future.complete(Unit)
    }

    private fun ensureWorker(): WorkerProcess {
        synchronized(lock) { process?.takeIf(WorkerProcess::isAlive)?.let { return it } }
        val child =
            try {
                processFactory.start(launch)
            } catch (exception: Exception) {
                throw ControllerFault(AnalysisFailureKind.Startup, exception.message ?: "analysis worker failed to start")
            }
        synchronized(lock) {
            if (closed) {
                child.terminate(policy.terminationGraceMillis)
                throw ControllerFault(AnalysisFailureKind.Cancelled, "analysis controller is closed")
            }
            process = child
            workerSnapshot = null
        }
        val handshake =
            receive(child, policy.startupTimeoutNanos, AnalysisProtocolContext.unchecked()) as? AnalysisHandshake
                ?: throw ControllerFault(AnalysisFailureKind.Protocol, "expected analysis handshake")
        if (handshake.protocol != ANALYSIS_PROTOCOL_VERSION || handshake.workerIdentity != expectedWorkerIdentity) {
            throw ControllerFault(AnalysisFailureKind.Protocol, "analysis worker identity mismatch")
        }
        if (!handshake.features.containsAll(REQUIRED_FEATURES) || !handshake.limits.supports(limits)) {
            throw ControllerFault(AnalysisFailureKind.Protocol, "analysis worker capabilities mismatch")
        }
        return child
    }

    private fun ensureSnapshot(
        child: WorkerProcess,
        snapshot: AdmittedAnalysisSnapshot,
    ) {
        val confirmed = synchronized(lock) { workerSnapshot }
        val snapshotTransition = if (confirmed == null) AnalysisSnapshotTransition.FullOpen else transition(confirmed, snapshot)
        when (snapshotTransition) {
            AnalysisSnapshotTransition.AlreadyCurrent -> {}

            AnalysisSnapshotTransition.FullOpen -> {
                openWorkerSnapshot(child, snapshot)
            }

            is AnalysisSnapshotTransition.Incremental -> {
                updateWorkerSnapshot(child, checkNotNull(confirmed), snapshot, snapshotTransition.changedSources)
            }
        }
    }

    private fun updateWorkerSnapshot(
        child: WorkerProcess,
        confirmed: AdmittedAnalysisSnapshot,
        target: AdmittedAnalysisSnapshot,
        changedSources: List<ProjectSource>,
    ) {
        val requestId = synchronized(lock) { nextRequestId() }
        send(
            child,
            UpdateSnapshotRequest(requestId, confirmed.identity, target.identity, changedSources),
            context(confirmed),
        )
        when (val response = receive(child, policy.requestTimeoutNanos, context(confirmed))) {
            is SnapshotUpdated -> {
                if (response.requestId != requestId || response.targetIdentity != target.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot-updated response mismatch")
                }
                synchronized(lock) { workerSnapshot = target }
            }

            is SnapshotReopenRequired -> {
                if (response.requestId != requestId || response.targetIdentity != target.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot reopen-required response mismatch")
                }
                val newest =
                    synchronized(lock) {
                        workerSnapshot = null
                        retainedSnapshot ?: target
                    }
                openWorkerSnapshot(child, newest)
            }

            is AnalysisFailure -> {
                if (response.requestId != requestId || response.identity != target.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot update failure response mismatch")
                }
                throw ControllerFault(response.failure, response.detail)
            }

            else -> {
                throw ControllerFault(AnalysisFailureKind.Protocol, "expected snapshot update response")
            }
        }
    }

    private fun openWorkerSnapshot(
        child: WorkerProcess,
        snapshot: AdmittedAnalysisSnapshot,
    ) {
        val requestId = synchronized(lock) { nextRequestId() }
        send(child, OpenSnapshotRequest(requestId, snapshot.identity, snapshot.sources, snapshot.profile, limits), context(snapshot))
        when (val response = receive(child, policy.requestTimeoutNanos, context(snapshot))) {
            is SnapshotReady -> {
                if (response.requestId != requestId || response.identity != snapshot.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot reopen response mismatch")
                }
                synchronized(lock) { workerSnapshot = snapshot }
            }

            is AnalysisFailure -> {
                if (response.requestId != requestId || response.identity != snapshot.identity) {
                    throw ControllerFault(AnalysisFailureKind.Protocol, "snapshot reopen failure response mismatch")
                }
                throw ControllerFault(response.failure, response.detail)
            }

            else -> {
                throw ControllerFault(AnalysisFailureKind.Protocol, "expected snapshot-ready response")
            }
        }
    }

    private fun send(
        child: WorkerProcess,
        message: AnalysisMessage,
        context: AnalysisProtocolContext,
    ) {
        try {
            val frame = AnalysisFrameCodec.encode(AnalysisMessageCodec.encode(message, context))
            if (frame.size >
                launch.maximumFrameBytes
            ) {
                throw ControllerFault(AnalysisFailureKind.OutputLimit, "analysis frame exceeds process limit")
            }
            child.writeFrame(frame)
        } catch (fault: ControllerFault) {
            throw fault
        } catch (exception: Exception) {
            throw ControllerFault(AnalysisFailureKind.WorkerExit, exception.message ?: "analysis worker input failed")
        }
    }

    private fun receive(
        child: WorkerProcess,
        timeoutNanos: Long,
        context: AnalysisProtocolContext,
    ): AnalysisMessage {
        val frame =
            try {
                child.readFrame(deadlineAfter(timeoutNanos))
            } catch (_: WorkerDeadlineExceededException) {
                throw ControllerFault(AnalysisFailureKind.Timeout, "analysis deadline exceeded")
            } catch (exception: Exception) {
                throw ControllerFault(AnalysisFailureKind.WorkerExit, exception.message ?: "analysis worker output failed")
            } ?: throw exitFault(child)
        return try {
            AnalysisMessageCodec.decode(AnalysisFrameCodec.decode(frame, limits.frameBytes), context)
        } catch (exception: IllegalArgumentException) {
            throw ControllerFault(AnalysisFailureKind.Protocol, exception.message ?: "malformed analysis worker frame")
        }
    }

    private fun safelySendCancel(
        child: WorkerProcess,
        requestId: RequestId,
    ) {
        runCatching { send(child, CancelAnalysisRequest(requestId), AnalysisProtocolContext.unchecked()) }
    }

    private fun invalidate() {
        val child = synchronized(lock) { detachProcess() }
        child?.terminate(policy.terminationGraceMillis)
    }

    private fun detachProcess(): WorkerProcess? =
        process.also {
            process = null
            workerSnapshot = null
        }

    private fun context(snapshot: AdmittedAnalysisSnapshot) =
        try {
            snapshot.protocolContext
        } catch (exception: Exception) {
            throw ControllerFault(AnalysisFailureKind.InvalidSnapshot, exception.message ?: "invalid attached platform sources")
        }

    private fun schedulerItems(): List<Pending> = scheduler.values().map { it.value }

    private fun nextRequestId(): RequestId {
        check(nextRequestId != 0uL) { "analysis request ID space exhausted" }
        return RequestId.of(nextRequestId++)
    }

    private fun completeStale(pending: Pending) {
        when (pending) {
            is Pending.Open -> pending.future.complete(SnapshotOpenResult.Stale)
            is Pending.Query -> pending.future.complete(AnalysisClientResult.Stale)
            is Pending.Close -> pending.future.complete(Unit)
        }
    }

    private fun completeCancelled(pending: Pending) {
        when (pending) {
            is Pending.Open -> pending.future.complete(SnapshotOpenResult.Failure(AnalysisFailureKind.Cancelled, "controller closed"))
            is Pending.Query -> pending.future.complete(AnalysisClientResult.Cancelled)
            is Pending.Close -> pending.future.complete(Unit)
        }
    }

    private fun completeFailure(
        pending: Pending,
        kind: AnalysisFailureKind,
        detail: String,
    ) {
        if (pending.isDone()) return
        when (pending) {
            is Pending.Open -> {
                pending.future.complete(SnapshotOpenResult.Failure(kind, detail))
            }

            is Pending.Query -> {
                if (pending.cancelled) {
                    pending.future.complete(AnalysisClientResult.Cancelled)
                } else {
                    pending.future.complete(AnalysisClientResult.Failure(kind, detail))
                }
            }

            is Pending.Close -> {
                pending.future.completeExceptionally(IllegalStateException(detail))
            }
        }
    }

    private fun boundedDetail(value: String): String {
        val maximumBytes = minOf(MAX_FAILURE_DETAIL_BYTES, limits.detailTextBytes)
        val source = value.ifEmpty { "analysis worker failure" }
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val encodedBytes = text.encodeToByteArray().size
            if (bytes + encodedBytes > maximumBytes) break
            result.append(text)
            bytes += encodedBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun deadlineAfter(durationNanos: Long): Long = Math.addExact(nanoTime(), durationNanos)

    private fun exitFault(child: WorkerProcess): ControllerFault {
        val stderr = child.stderrSnapshot().decodeToString()
        val kind = if (MEMORY_FAILURE_MARKERS.any(stderr::contains)) AnalysisFailureKind.MemoryLimit else AnalysisFailureKind.WorkerExit
        return ControllerFault(kind, stderr.ifBlank { "analysis worker closed stdout" })
    }

    private fun workKind(query: AnalysisQuery): AnalysisWorkKind =
        when (query) {
            is AnalysisQuery.Presentation -> {
                AnalysisWorkKind.BackgroundPresentation
            }

            is AnalysisQuery.Completion -> {
                if (query.trigger == CompletionTrigger.Automatic) {
                    AnalysisWorkKind.AutomaticCompletion
                } else {
                    AnalysisWorkKind.ManualInteractive
                }
            }

            else -> {
                AnalysisWorkKind.ManualInteractive
            }
        }

    private fun <T> failedFuture(message: String): CompletableFuture<T> =
        CompletableFuture<T>().also { it.completeExceptionally(IllegalStateException(message)) }

    private sealed class Pending {
        abstract val requestId: RequestId
        abstract val identity: AnalysisSnapshotIdentity
        abstract val kind: AnalysisWorkKind

        @Volatile var cancelled = false

        abstract fun isDone(): Boolean

        class Open(
            override val requestId: RequestId,
            val snapshot: AdmittedAnalysisSnapshot,
            val future: CompletableFuture<SnapshotOpenResult>,
        ) : Pending() {
            override val identity = snapshot.identity
            override val kind = AnalysisWorkKind.ManualInteractive

            override fun isDone() = future.isDone
        }

        class Query(
            override val requestId: RequestId,
            val snapshot: AdmittedAnalysisSnapshot,
            val query: AnalysisQuery,
            override val kind: AnalysisWorkKind,
            val future: CompletableFuture<AnalysisClientResult>,
        ) : Pending() {
            override val identity = query.identity

            @Volatile var querySent = false

            @Volatile var awaitingResponse = false

            override fun isDone() = future.isDone
        }

        class Close(
            override val requestId: RequestId,
            override val identity: AnalysisSnapshotIdentity,
            val future: CompletableFuture<Unit>,
        ) : Pending() {
            override val kind = AnalysisWorkKind.ManualInteractive

            override fun isDone() = future.isDone
        }
    }

    private class ControllerFault(
        val kind: AnalysisFailureKind,
        message: String,
    ) : IllegalStateException(message)

    private companion object {
        const val MAX_FAILURE_DETAIL_BYTES = 4096
        val REQUIRED_FEATURES = AnalysisFeature.entries.toSet()
        val MEMORY_FAILURE_MARKERS = listOf("OutOfMemoryError", "Java heap space", "Metaspace")
    }
}
