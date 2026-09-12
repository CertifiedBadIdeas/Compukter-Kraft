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
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.ANALYSIS_PROTOCOL_VERSION
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisCancelled
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailure
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFeature
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisHandshake
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQuerySuccess
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.CancelAnalysisRequest
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReady
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReopenRequired
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotUpdated
import ru.lazyhat.compukters.ide.analysis.protocol.UpdateSnapshotRequest
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalysisWorkerControllerTest {
    @Test
    fun `worker capability may exceed requested controller limits`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(workerIdentity, limits.copy(sourceFiles = 512)))
            val snapshot = admitted("val answer = 42", limits)

            val opened = controller.open(snapshot)
            val openRequest = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            worker.enqueue(SnapshotReady(openRequest.requestId, snapshot.identity))

            assertEquals(SnapshotOpenResult.Opened(snapshot.identity), opened.get(5, TimeUnit.SECONDS))
        }

    @Test
    fun `query synchronizes directly from confirmed revision to the latest compatible snapshot`() =
        withController(1) { controller, factory, processes, workerIdentity, limits ->
            val worker = processes.single()
            val first = open(controller, worker, workerIdentity, limits)
            val latest = admitted("val answer = 333", limits)
            val query = AnalysisQuery.Completion(latest.identity, path(), 6, CompletionTrigger.Manual)

            val result = controller.query(latest, query)

            val update = assertIs<UpdateSnapshotRequest>(worker.awaitWrite())
            assertEquals(first.identity, update.baseIdentity)
            assertEquals(latest.identity, update.targetIdentity)
            assertEquals(latest.sources.sources, update.changedSources)
            worker.enqueue(SnapshotUpdated(update.requestId, latest.identity))
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val completion = AnalysisResult.Completion.create(latest.identity, EditorRange(4, 6), emptyList(), "val answer = 333".length)
            worker.enqueue(AnalysisQuerySuccess(request.requestId, completion))

            assertEquals(AnalysisClientResult.Success(completion), result.get(5, TimeUnit.SECONDS))
            assertEquals(1, factory.starts.size)
        }

    @Test
    fun `reopen-required falls back to a full open in the same worker before querying`() =
        withController(1) { controller, factory, processes, workerIdentity, limits ->
            val worker = processes.single()
            val first = open(controller, worker, workerIdentity, limits)
            val target = admitted("val answer = 2", limits)
            val query = AnalysisQuery.ExpressionInfo(target.identity, path(), 3)

            val result = controller.query(target, query)
            val update = assertIs<UpdateSnapshotRequest>(worker.awaitWrite())
            assertEquals(first.identity, update.baseIdentity)
            worker.enqueue(SnapshotReopenRequired(update.requestId, target.identity, "reopen"))
            val reopened = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            assertEquals(target.identity, reopened.identity)
            worker.enqueue(SnapshotReady(reopened.requestId, target.identity))
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val expression = AnalysisResult.ExpressionInfo.create(target.identity, null, mapOf(path() to 14))
            worker.enqueue(AnalysisQuerySuccess(request.requestId, expression))

            assertEquals(AnalysisClientResult.Success(expression), result.get(5, TimeUnit.SECONDS))
            assertEquals(1, factory.starts.size)
        }

    @Test
    fun `reopen-required skips directly to the newest retained snapshot`() =
        withController(1) { controller, factory, processes, workerIdentity, limits ->
            val worker = processes.single()
            val first = open(controller, worker, workerIdentity, limits)
            val intermediate = admitted("val answer = 2", limits)
            val latest = admitted("val answer = 333", limits)

            val stale = controller.query(intermediate, AnalysisQuery.ExpressionInfo(intermediate.identity, path(), 3))
            val update = assertIs<UpdateSnapshotRequest>(worker.awaitWrite())
            assertEquals(first.identity, update.baseIdentity)
            val latestQuery = AnalysisQuery.ExpressionInfo(latest.identity, path(), 3)
            val result = controller.query(latest, latestQuery)

            worker.enqueue(SnapshotReopenRequired(update.requestId, intermediate.identity, "reopen"))
            val reopened = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            assertEquals(latest.identity, reopened.identity)
            worker.enqueue(SnapshotReady(reopened.requestId, latest.identity))
            assertEquals(AnalysisClientResult.Stale, stale.get(5, TimeUnit.SECONDS))

            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            assertEquals(latestQuery, request.query)
            val expression = AnalysisResult.ExpressionInfo.create(latest.identity, null, mapOf(path() to 14))
            worker.enqueue(AnalysisQuerySuccess(request.requestId, expression))

            assertEquals(AnalysisClientResult.Success(expression), result.get(5, TimeUnit.SECONDS))
            assertEquals(1, factory.starts.size)
        }

    @Test
    fun `profile change uses a full open instead of an incremental update`() =
        withController(1) { controller, factory, processes, workerIdentity, limits ->
            val worker = processes.single()
            open(controller, worker, workerIdentity, limits)
            val target = admitted("val answer = 2", limits, profileByte = 2)
            val query = AnalysisQuery.ExpressionInfo(target.identity, path(), 3)

            val result = controller.query(target, query)

            val reopened = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            assertEquals(target.identity, reopened.identity)
            worker.enqueue(SnapshotReady(reopened.requestId, target.identity))
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val expression = AnalysisResult.ExpressionInfo.create(target.identity, null, mapOf(path() to 14))
            worker.enqueue(AnalysisQuerySuccess(request.requestId, expression))

            assertEquals(AnalysisClientResult.Success(expression), result.get(5, TimeUnit.SECONDS))
            assertEquals(1, factory.starts.size)
        }

    @Test
    fun `source path set change uses a full open instead of an incremental update`() =
        withController(1) { controller, factory, processes, workerIdentity, limits ->
            val worker = processes.single()
            open(controller, worker, workerIdentity, limits)
            val target = admitted("val answer = 2", limits, sourcePath = "renamed.kt")
            val targetPath = path("renamed.kt")
            val query = AnalysisQuery.ExpressionInfo(target.identity, targetPath, 3)

            val result = controller.query(target, query)

            val reopened = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            assertEquals(target.identity, reopened.identity)
            worker.enqueue(SnapshotReady(reopened.requestId, target.identity))
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val expression = AnalysisResult.ExpressionInfo.create(target.identity, null, mapOf(targetPath to 14))
            worker.enqueue(AnalysisQuerySuccess(request.requestId, expression))

            assertEquals(AnalysisClientResult.Success(expression), result.get(5, TimeUnit.SECONDS))
            assertEquals(1, factory.starts.size)
        }

    @Test
    fun `failed full reopen invalidates the worker`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val worker = processes.single()
            val first = open(controller, worker, workerIdentity, limits)
            val target = admitted("val answer = 2", limits)
            val query = AnalysisQuery.ExpressionInfo(target.identity, path(), 3)

            val result = controller.query(target, query)
            val update = assertIs<UpdateSnapshotRequest>(worker.awaitWrite())
            assertEquals(first.identity, update.baseIdentity)
            worker.enqueue(SnapshotReopenRequired(update.requestId, target.identity, "reopen"))
            val reopened = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            worker.enqueue(AnalysisFailure(reopened.requestId, target.identity, AnalysisFailureKind.InvalidSnapshot, "rejected"))

            assertEquals(
                AnalysisClientResult.Failure(AnalysisFailureKind.InvalidSnapshot, "rejected"),
                result.get(5, TimeUnit.SECONDS),
            )
            assertEquals(listOf(37L), worker.terminationGraces)
        }

    @Test
    fun `worker starts lazily opens once and returns a correlated result`() =
        withController(1) { controller, factory, processes, workerIdentity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(workerIdentity, limits))
            val snapshot = admitted("val answer = 42", limits)

            val opened = controller.open(snapshot)
            val openRequest = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
            worker.enqueue(SnapshotReady(openRequest.requestId, snapshot.identity))
            assertEquals(SnapshotOpenResult.Opened(snapshot.identity), opened.get(5, TimeUnit.SECONDS))

            val query = AnalysisQuery.Completion(snapshot.identity, path(), 6, CompletionTrigger.Manual)
            val result = controller.query(snapshot, query)
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val completion = AnalysisResult.Completion.create(snapshot.identity, EditorRange(4, 6), emptyList(), "val answer = 42".length)
            worker.enqueue(AnalysisQuerySuccess(request.requestId, completion))

            assertEquals(AnalysisClientResult.Success(completion), result.get(5, TimeUnit.SECONDS))
            assertEquals(1, factory.starts.size)
        }

    @Test
    fun `active cancellation waits for acknowledgement and queued work continues`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val worker = processes.single()
            val snapshot = open(controller, worker, workerIdentity, limits)
            val future = controller.query(snapshot, AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val queuedQuery = AnalysisQuery.Declaration(snapshot.identity, path(), 3)
            val queued = controller.query(snapshot, queuedQuery)

            assertTrue(controller.cancel(future))

            assertEquals(request.requestId, assertIs<CancelAnalysisRequest>(worker.awaitWrite()).requestId)
            assertTrue(!future.isDone)
            val obsolete = AnalysisResult.ExpressionInfo.create(snapshot.identity, null, mapOf(path() to 14))
            worker.enqueue(AnalysisQuerySuccess(request.requestId, obsolete))
            assertTrue(!future.isDone)
            worker.enqueue(AnalysisCancelled(request.requestId, snapshot.identity))
            assertEquals(AnalysisClientResult.Cancelled, future.get(5, TimeUnit.SECONDS))

            val queuedRequest = assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            val declaration = AnalysisResult.Declaration.create(snapshot.identity, emptyList(), mapOf(path() to 15))
            worker.enqueue(AnalysisQuerySuccess(queuedRequest.requestId, declaration))
            assertEquals(AnalysisClientResult.Success(declaration), queued.get(5, TimeUnit.SECONDS))
            assertEquals(emptyList(), worker.terminationGraces)
        }

    @Test
    fun `query rejects a snapshot with a different identity`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val snapshot = open(controller, processes.single(), workerIdentity, limits)
            val other = admitted("val answer = 2", limits)

            kotlin.test.assertFailsWith<IllegalArgumentException> {
                controller.query(snapshot, AnalysisQuery.ExpressionInfo(other.identity, path(), 3))
            }
        }

    @Test
    fun `cancelled query reports cancellation when the worker misses the deadline`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val worker = processes.single()
            val snapshot = open(controller, worker, workerIdentity, limits)
            val future = controller.query(snapshot, AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            val request = assertIs<AnalysisQueryRequest>(worker.awaitWrite())

            assertTrue(controller.cancel(future))
            assertEquals(request.requestId, assertIs<CancelAnalysisRequest>(worker.awaitWrite()).requestId)
            worker.enqueueTimeout()

            assertEquals(AnalysisClientResult.Cancelled, future.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(37L), worker.terminationGraces)
        }

    @Test
    fun `timeout invalidates process and next query restarts and reopens retained snapshot`() =
        withController(2) { controller, factory, processes, workerIdentity, limits ->
            val snapshot = open(controller, processes[0], workerIdentity, limits)
            val first = controller.query(snapshot, AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            processes[0].enqueueTimeout()
            assertIs<AnalysisClientResult.Failure>(first.get(5, TimeUnit.SECONDS)).also {
                assertEquals(ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind.Timeout, it.kind)
            }

            processes[1].enqueue(handshake(workerIdentity, limits))
            val secondQuery = AnalysisQuery.Declaration(snapshot.identity, path(), 3)
            val second = controller.query(snapshot, secondQuery)
            val reopened = assertIs<OpenSnapshotRequest>(processes[1].awaitWrite())
            processes[1].enqueue(SnapshotReady(reopened.requestId, snapshot.identity))
            val request = assertIs<AnalysisQueryRequest>(processes[1].awaitWrite())
            val declaration = AnalysisResult.Declaration.create(snapshot.identity, emptyList(), mapOf(path() to 15))
            processes[1].enqueue(AnalysisQuerySuccess(request.requestId, declaration))

            assertEquals(AnalysisClientResult.Success(declaration), second.get(5, TimeUnit.SECONDS))
            assertEquals(2, factory.starts.size)
        }

    @Test
    fun `malformed output and worker EOF have distinct recoverable failures`() {
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val snapshot = open(controller, processes[0], workerIdentity, limits)
            val result = controller.query(snapshot, AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            processes[0].enqueueMalformed()

            assertEquals(
                ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind.Protocol,
                assertIs<AnalysisClientResult.Failure>(result.get(5, TimeUnit.SECONDS)).kind,
            )
            assertEquals(listOf(37L), processes[0].terminationGraces)
        }

        withController(1) { controller, _, processes, workerIdentity, limits ->
            val snapshot = open(controller, processes[0], workerIdentity, limits)
            val result = controller.query(snapshot, AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            processes[0].enqueueEof()

            assertEquals(
                ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind.WorkerExit,
                assertIs<AnalysisClientResult.Failure>(result.get(5, TimeUnit.SECONDS)).kind,
            )
            assertEquals(listOf(37L), processes[0].terminationGraces)
        }
    }

    @Test
    fun `worker failure detail is bounded by encoded bytes without splitting text`() =
        withController(1) { controller, _, processes, workerIdentity, limits ->
            val worker = processes[0]
            val snapshot = open(controller, worker, workerIdentity, limits)
            val result = controller.query(snapshot, AnalysisQuery.ExpressionInfo(snapshot.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(worker.awaitWrite())
            worker.stderr = ("x" + "💥".repeat(3_000)).encodeToByteArray()
            worker.enqueueEof()

            val failure = assertIs<AnalysisClientResult.Failure>(result.get(5, TimeUnit.SECONDS))

            assertTrue(failure.detail.encodeToByteArray().size <= 4_096)
            assertTrue(failure.detail.codePoints().noneMatch { it in 0xD800..0xDFFF })
        }

    @Test
    fun `opening a new snapshot makes active and queued old work stale`() =
        withController(2) { controller, _, processes, workerIdentity, limits ->
            val first = open(controller, processes[0], workerIdentity, limits)
            val active = controller.query(first, AnalysisQuery.ExpressionInfo(first.identity, path(), 3))
            assertIs<AnalysisQueryRequest>(processes[0].awaitWrite())
            val queued = controller.query(first, AnalysisQuery.Presentation(first.identity, testPath()))

            processes[1].enqueue(handshake(workerIdentity, limits))
            val second = admitted("val changed = true", limits)
            val opening = controller.open(second)

            assertEquals(AnalysisClientResult.Stale, active.get(5, TimeUnit.SECONDS))
            assertEquals(AnalysisClientResult.Stale, queued.get(5, TimeUnit.SECONDS))
            val openRequest = assertIs<OpenSnapshotRequest>(processes[1].awaitWrite())
            processes[1].enqueue(SnapshotReady(openRequest.requestId, second.identity))
            assertEquals(SnapshotOpenResult.Opened(second.identity), opening.get(5, TimeUnit.SECONDS))
        }

    private fun open(
        controller: AnalysisWorkerController,
        worker: FakeAnalysisWorkerProcess,
        workerIdentity: AnalysisWorkerIdentity,
        limits: AnalysisLimits,
    ): AdmittedAnalysisSnapshot {
        worker.enqueue(handshake(workerIdentity, limits))
        val snapshot = admitted("val answer = 42", limits)
        val future = controller.open(snapshot)
        val request = assertIs<OpenSnapshotRequest>(worker.awaitWrite())
        worker.enqueue(SnapshotReady(request.requestId, snapshot.identity))
        assertIs<SnapshotOpenResult.Opened>(future.get(5, TimeUnit.SECONDS))
        return snapshot
    }

    private fun withController(
        processCount: Int,
        block: (
            AnalysisWorkerController,
            FakeAnalysisWorkerProcessFactory,
            List<FakeAnalysisWorkerProcess>,
            AnalysisWorkerIdentity,
            AnalysisLimits,
        ) -> Unit,
    ) {
        val processes = List(processCount) { FakeAnalysisWorkerProcess() }
        val factory = FakeAnalysisWorkerProcessFactory(processes)
        val identity = AnalysisWorkerIdentity("2.4.10", "2.4", hash(8), hash(9))
        val limits = AnalysisLimits()
        val launch = WorkerLaunch(Path.of("java"), listOf(Path.of("worker.jar")), "example.AnalysisMain", 256, 128, Path.of("tmp"))
        val policy = AnalysisWorkerPolicy(startupTimeoutNanos = 11, requestTimeoutNanos = 23, terminationGraceMillis = 37)
        AnalysisWorkerController(launch, identity, limits, factory, policy) { 100 }.use { controller ->
            block(controller, factory, processes, identity, limits)
        }
    }

    private fun admitted(
        text: String,
        limits: AnalysisLimits,
        profileByte: Int = 1,
        sourcePath: String = "main.kt",
    ): AdmittedAnalysisSnapshot {
        val sources =
            ProjectSnapshot.of(
                listOf(ProjectSource(path(sourcePath), BinaryValue.of(text.encodeToByteArray()))),
                WorkerLimits(),
            )
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), AnalysisProfileIdentity(hash(profileByte)))
        return AdmittedAnalysisSnapshot(
            identity,
            sources,
            AdmittedAnalysisProfile(
                identity.profile,
                ru.lazyhat.compukters.ide.analysis.protocol
                    .AdmittedAnalysisPlatform(Hash256.zero(), emptyList()),
            ),
            limits,
        )
    }

    private fun handshake(
        identity: AnalysisWorkerIdentity,
        limits: AnalysisLimits,
    ) = AnalysisHandshake(ANALYSIS_PROTOCOL_VERSION, identity, AnalysisFeature.entries.toSet(), limits)

    private fun path(value: String = "main.kt") = VirtualSourcePath.kotlin(value)

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}
