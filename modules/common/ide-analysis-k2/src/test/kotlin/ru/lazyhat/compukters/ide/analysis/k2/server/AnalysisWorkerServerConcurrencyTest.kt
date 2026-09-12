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

import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.ExceptionWithAttachments
import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.k2.standalone.K2SourceUpdater
import ru.lazyhat.compukters.ide.analysis.k2.standalone.SnapshotAdmission
import ru.lazyhat.compukters.ide.analysis.k2.testPlatform
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisCancelled
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailure
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.CancelAnalysisRequest
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReady
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReopenRequired
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotUpdated
import ru.lazyhat.compukters.ide.analysis.protocol.UpdateSnapshotRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalysisWorkerServerConcurrencyTest {
    @Test
    fun `update is serialized with queries and keeps the active K2 environment`() {
        val root = createTempDirectory("compukters-server-update-")
        val output = RecordingOutputStream()
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val updateStarted = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val queryAfterUpdate = CountDownLatch(1)
        val observedEnvironments = mutableListOf<Any>()
        val initialSources = projectSources("val answer = 1")
        val initialIdentity = identity(initialSources)
        val updatedSources = projectSources("val answer = 2")
        val updatedIdentity = identity(updatedSources)
        val updater =
            K2SourceUpdater { environment, files, changedTexts ->
                updateStarted.countDown()
                releaseUpdate.await(5, TimeUnit.SECONDS)
                ru.lazyhat.compukters.ide.analysis.k2.standalone.DocumentK2SourceUpdater.update(
                    environment,
                    files,
                    changedTexts,
                )
            }
        val server =
            server(root, output, updater) { request, snapshot, _ ->
                synchronized(observedEnvironments) { observedEnvironments += snapshot.environment }
                if (request.query.identity == initialIdentity) {
                    queryStarted.countDown()
                    releaseQuery.await(5, TimeUnit.SECONDS)
                } else {
                    queryAfterUpdate.countDown()
                }
                AnalysisFailure(
                    request.requestId,
                    request.query.identity,
                    AnalysisFailureKind.UnsupportedFeature,
                    snapshot.files.getValue(VirtualSourcePath.kotlin("main.kt")).text,
                )
            }
        try {
            assertTrue(server.accept(openRequest(RequestId.of(1uL), initialIdentity, initialSources)))
            assertIs<SnapshotReady>(output.next())

            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(2uL), AnalysisQuery.Presentation(initialIdentity, mainPath()))))
            assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
            assertTrue(server.accept(updateRequest(RequestId.of(3uL), initialIdentity, updatedIdentity, "val answer = 2")))
            assertFalse(updateStarted.await(100, TimeUnit.MILLISECONDS))

            releaseQuery.countDown()
            assertIs<AnalysisFailure>(output.next())
            assertTrue(updateStarted.await(5, TimeUnit.SECONDS))
            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(4uL), AnalysisQuery.Presentation(updatedIdentity, mainPath()))))
            assertFalse(queryAfterUpdate.await(100, TimeUnit.MILLISECONDS))

            releaseUpdate.countDown()
            assertEquals(updatedIdentity, assertIs<SnapshotUpdated>(output.next()).targetIdentity)
            assertTrue(queryAfterUpdate.await(5, TimeUnit.SECONDS))
            assertEquals("val answer = 2", assertIs<AnalysisFailure>(output.next()).detail)
            val replacementSources = projectSources("val answer = 3")
            val replacementIdentity = identity(replacementSources)
            assertTrue(server.accept(openRequest(RequestId.of(5uL), replacementIdentity, replacementSources)))
            assertIs<SnapshotReady>(output.next())
            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(6uL), AnalysisQuery.Presentation(replacementIdentity, mainPath()))))
            assertEquals("val answer = 3", assertIs<AnalysisFailure>(output.next()).detail)

            val environments = synchronized(observedEnvironments) { observedEnvironments.toList() }
            assertTrue(environments[0] === environments[1])
            assertFalse(environments[1] === environments[2])
        } finally {
            releaseQuery.countDown()
            releaseUpdate.countDown()
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale update is rejected without mutating the active snapshot`() {
        val root = createTempDirectory("compukters-server-stale-update-")
        val output = RecordingOutputStream()
        val initialSources = projectSources("val answer = 1")
        val initialIdentity = identity(initialSources)
        val targetSources = projectSources("val answer = 2")
        val targetIdentity = identity(targetSources)
        val staleBase = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(projectSources("val answer = 0")), initialIdentity.profile)
        val server =
            server(root, output) { request, snapshot, _ ->
                AnalysisFailure(
                    request.requestId,
                    request.query.identity,
                    AnalysisFailureKind.UnsupportedFeature,
                    snapshot.files.getValue(VirtualSourcePath.kotlin("main.kt")).text,
                )
            }
        try {
            assertTrue(server.accept(openRequest(RequestId.of(10uL), initialIdentity, initialSources)))
            assertIs<SnapshotReady>(output.next())

            assertTrue(server.accept(updateRequest(RequestId.of(11uL), staleBase, targetIdentity, "val answer = 2")))
            val rejected = assertIs<AnalysisFailure>(output.next())
            assertEquals(AnalysisFailureKind.InvalidSnapshot, rejected.failure)

            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(12uL), AnalysisQuery.Presentation(initialIdentity, mainPath()))))
            assertEquals("val answer = 1", assertIs<AnalysisFailure>(output.next()).detail)
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mutation failure requires reopen and a full open restores the server`() {
        val root = createTempDirectory("compukters-server-reopen-")
        val output = RecordingOutputStream()
        val initialSources = projectSources("val answer = 1")
        val initialIdentity = identity(initialSources)
        val targetSources = projectSources("val answer = 2")
        val targetIdentity = identity(targetSources)
        val server =
            server(
                root,
                output,
                K2SourceUpdater { _, _, _ -> error("synthetic mutation failure") },
            ) { request, snapshot, _ ->
                AnalysisFailure(
                    request.requestId,
                    request.query.identity,
                    AnalysisFailureKind.UnsupportedFeature,
                    snapshot.files.getValue(VirtualSourcePath.kotlin("main.kt")).text,
                )
            }
        try {
            assertTrue(server.accept(openRequest(RequestId.of(20uL), initialIdentity, initialSources)))
            assertIs<SnapshotReady>(output.next())

            assertTrue(server.accept(updateRequest(RequestId.of(21uL), initialIdentity, targetIdentity, "val answer = 2")))
            assertEquals(targetIdentity, assertIs<SnapshotReopenRequired>(output.next()).targetIdentity)

            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(22uL), AnalysisQuery.Presentation(initialIdentity, mainPath()))))
            assertEquals(AnalysisFailureKind.InvalidSnapshot, assertIs<AnalysisFailure>(output.next()).failure)

            assertTrue(server.accept(openRequest(RequestId.of(23uL), targetIdentity, targetSources)))
            assertIs<SnapshotReady>(output.next())
            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(24uL), AnalysisQuery.Presentation(targetIdentity, mainPath()))))
            assertEquals("val answer = 2", assertIs<AnalysisFailure>(output.next()).detail)
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `query is rejected before entering K2 when its snapshot is not active`() {
        val root = createTempDirectory("compukters-server-inactive-")
        val output = SignallingOutputStream()
        val invoked = AtomicBoolean()
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(projectSources()), AnalysisProfileIdentity(hash(2)))
        val limits = AnalysisLimits()
        val server =
            AnalysisWorkerServer(
                AnalysisWorkerIdentity(
                    "2.4.10",
                    "2.4",
                    hash(3),
                    ru.lazyhat.compukters.ide.analysis.k2
                        .testPlatformAbi(),
                ),
                limits,
                ByteArrayInputStream(ByteArray(0)),
                output,
                SnapshotAdmission(root, testPlatform()),
                queryHandler =
                    AnalysisQueryHandler { request, _, _ ->
                        invoked.set(true)
                        error("handler must not run for an inactive snapshot: ${request.requestId}")
                    },
            )
        try {
            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(7uL), AnalysisQuery.Presentation(identity, mainPath()))))
            assertTrue(output.written.await(5, TimeUnit.SECONDS))

            val failure =
                assertIs<AnalysisFailure>(
                    AnalysisMessageCodec.decode(
                        AnalysisFrameCodec.decode(output.toByteArray(), limits.frameBytes),
                        AnalysisProtocolContext.unbound(limits),
                    ),
                )
            assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.failure)
            assertFalse(invoked.get())
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `internal analysis failure preserves its cause chain`() {
        val root = createTempDirectory("compukters-server-failure-detail-")
        val output = RecordingOutputStream()
        val sources = projectSources("object ll")
        val identity = identity(sources)
        val server =
            server(root, output) { _, _, _ ->
                val cause = IllegalStateException("broken FIR").apply { stackTrace = emptyArray() }
                throw AssertionError("diagnostic collector").apply {
                    stackTrace = emptyArray()
                    initCause(cause)
                }
            }
        try {
            assertTrue(server.accept(openRequest(RequestId.of(30uL), identity, sources)))
            assertIs<SnapshotReady>(output.next())

            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(31uL), AnalysisQuery.Presentation(identity, mainPath()))))
            val failure = assertIs<AnalysisFailure>(output.next())

            assertEquals(
                "AssertionError: diagnostic collector\nCaused by: IllegalStateException: broken FIR",
                failure.detail,
            )
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `internal analysis failure includes stack frames and attachments`() {
        val root = createTempDirectory("compukters-server-failure-diagnostics-")
        val output = RecordingOutputStream()
        val sources = projectSources("object ll")
        val identity = identity(sources)
        val cause =
            AttachedFailure(
                "broken FIR cache",
                arrayOf(Attachment("cache.txt", "classId=ll\ncontext=<null>")),
            ).apply {
                stackTrace = arrayOf(StackTraceElement("fir.Cache", "get", "Cache.kt", 42))
            }
        val failure =
            AssertionError("diagnostic collector").apply {
                stackTrace = arrayOf(StackTraceElement("analysis.Collector", "collect", "Collector.kt", 17))
                initCause(cause)
            }
        val server = server(root, output) { _, _, _ -> throw failure }
        try {
            assertTrue(server.accept(openRequest(RequestId.of(32uL), identity, sources)))
            assertIs<SnapshotReady>(output.next())

            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(33uL), AnalysisQuery.Presentation(identity, mainPath()))))
            val response = assertIs<AnalysisFailure>(output.next())

            assertEquals(
                """
                AssertionError: diagnostic collector
                  at analysis.Collector.collect(Collector.kt:17)
                Caused by: AttachedFailure: broken FIR cache
                  at fir.Cache.get(Cache.kt:42)
                  Attachment cache.txt:
                    classId=ll
                    context=<null>
                """.trimIndent(),
                response.detail,
            )
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `server dispatch accepts cancel while analysis handler is occupied`() {
        val root = createTempDirectory("compukters-server-concurrency-")
        val started = CountDownLatch(1)
        val observed = CompletableFuture<Unit>()
        val resumed = CompletableFuture<Unit>()
        val invocations = AtomicInteger()
        val output = RecordingOutputStream()
        val sources = projectSources()
        val profile = AnalysisProfileIdentity(hash(2))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        val server =
            AnalysisWorkerServer(
                AnalysisWorkerIdentity(
                    "2.4.10",
                    "2.4",
                    hash(3),
                    ru.lazyhat.compukters.ide.analysis.k2
                        .testPlatformAbi(),
                ),
                AnalysisLimits(),
                ByteArrayInputStream(ByteArray(0)),
                output,
                SnapshotAdmission(root, testPlatform()),
                queryHandler =
                    AnalysisQueryHandler { request, _, cancellation ->
                        if (invocations.incrementAndGet() == 1) {
                            started.countDown()
                            while (!cancellation.isCancelled) Thread.onSpinWait()
                            observed.complete(Unit)
                        } else {
                            resumed.complete(Unit)
                        }
                        AnalysisFailure(
                            request.requestId,
                            request.query.identity,
                            AnalysisFailureKind.Cancelled,
                            "cancelled",
                        )
                    },
            )
        try {
            assertTrue(
                server.accept(
                    OpenSnapshotRequest(
                        RequestId.of(6uL),
                        identity,
                        sources,
                        AdmittedAnalysisProfile(
                            profile,
                            ru.lazyhat.compukters.ide.analysis.k2
                                .testAdmittedPlatform(),
                        ),
                        AnalysisLimits(),
                    ),
                ),
            )
            assertIs<SnapshotReady>(output.next())
            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(7uL), AnalysisQuery.Presentation(identity, mainPath()))))
            assertTrue(started.await(5, TimeUnit.SECONDS))

            assertTrue(server.accept(CancelAnalysisRequest(RequestId.of(7uL))))

            observed.get(5, TimeUnit.SECONDS)
            assertIs<AnalysisCancelled>(output.next())

            assertTrue(server.accept(AnalysisQueryRequest(RequestId.of(8uL), AnalysisQuery.Presentation(identity, mainPath()))))
            resumed.get(5, TimeUnit.SECONDS)
            assertIs<AnalysisFailure>(output.next())
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun standardLibrary(): Path =
        Path
            .of(
                Unit::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).toAbsolutePath()
            .normalize()

    private fun server(
        root: Path,
        output: RecordingOutputStream,
        updater: K2SourceUpdater? = null,
        handler: AnalysisQueryHandler,
    ): AnalysisWorkerServer =
        AnalysisWorkerServer(
            AnalysisWorkerIdentity(
                "2.4.10",
                "2.4",
                hash(3),
                ru.lazyhat.compukters.ide.analysis.k2
                    .testPlatformAbi(),
            ),
            AnalysisLimits(),
            ByteArrayInputStream(ByteArray(0)),
            output,
            if (updater == null) {
                SnapshotAdmission(root, testPlatform())
            } else {
                SnapshotAdmission(root, testPlatform(), updater)
            },
            handler,
        )

    private fun openRequest(
        requestId: RequestId,
        identity: AnalysisSnapshotIdentity,
        sources: ProjectSnapshot,
    ): OpenSnapshotRequest =
        OpenSnapshotRequest(
            requestId,
            identity,
            sources,
            AdmittedAnalysisProfile(
                identity.profile,
                ru.lazyhat.compukters.ide.analysis.k2
                    .testAdmittedPlatform(),
            ),
            AnalysisLimits(),
        )

    private fun updateRequest(
        requestId: RequestId,
        base: AnalysisSnapshotIdentity,
        target: AnalysisSnapshotIdentity,
        text: String,
    ): UpdateSnapshotRequest =
        UpdateSnapshotRequest(
            requestId,
            base,
            target,
            listOf(ProjectSource(VirtualSourcePath.kotlin("main.kt"), BinaryValue.of(text.encodeToByteArray()))),
        )

    private fun identity(sources: ProjectSnapshot): AnalysisSnapshotIdentity =
        AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), AnalysisProfileIdentity(hash(2)))

    private fun projectSources(text: String = "val answer = 42"): ProjectSnapshot =
        ProjectSnapshot.of(
            listOf(
                ProjectSource(
                    VirtualSourcePath.kotlin("main.kt"),
                    BinaryValue.of(text.encodeToByteArray()),
                ),
            ),
            WorkerLimits(),
        )

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}

private fun mainPath(): VirtualSourcePath = VirtualSourcePath.kotlin("main.kt")

private class AttachedFailure(
    message: String,
    private val diagnosticAttachments: Array<Attachment>,
) : IllegalStateException(message),
    ExceptionWithAttachments {
    override fun getAttachments(): Array<Attachment> = diagnosticAttachments
}

private class RecordingOutputStream : ByteArrayOutputStream() {
    private val frames = LinkedBlockingQueue<ByteArray>()

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        super.write(bytes, offset, length)
        frames.put(bytes.copyOfRange(offset, offset + length))
    }

    fun next(): ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessage {
        val frame = checkNotNull(frames.poll(5, TimeUnit.SECONDS)) { "timed out waiting for analysis response" }
        val limits = AnalysisLimits()
        return AnalysisMessageCodec.decode(
            AnalysisFrameCodec.decode(frame, limits.frameBytes),
            AnalysisProtocolContext.unbound(limits),
        )
    }
}

private class SignallingOutputStream : ByteArrayOutputStream() {
    val written = CountDownLatch(1)

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        super.write(bytes, offset, length)
        written.countDown()
    }
}
