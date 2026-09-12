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

package ru.lazyhat.compukters.ide.analysis.k2.integration

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
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.SemanticToken
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.SourceLocation
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisScheduledTask
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisTaskScheduler
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerPolicy
import ru.lazyhat.compukters.ide.analysis.controller.DefaultAnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.SnapshotOpenResult
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisMeasurementFixture
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisMeasurementFixtures
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageType
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReopenRequired
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IncrementalAnalysisEquivalenceTest {
    @Test
    fun `single-file incremental edits match full rebuilds`() {
        assertEquivalent(AnalysisMeasurementFixtures.singleFile(), ::singleFileRevisions)
    }

    @Test
    fun `five-file incremental edits match full rebuilds`() {
        assertEquivalent(AnalysisMeasurementFixtures.fiveFiles(), ::fiveFileRevisions)
    }

    @Test
    fun `rapid editor revisions synchronize only the latest snapshot`() {
        withController(CountingWorkerFactory()) { controller, factory, limits ->
            val fixture = AnalysisMeasurementFixtures.singleFile()
            val base = snapshot(fixture.sources.map { it.path to it.text }, limits)
            assertIs<SnapshotOpenResult.Opened>(controller.open(base).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val scheduler = ManualScheduler()
            val result = CompletableFuture<AnalysisClientResult>()
            val coordinator = DefaultAnalysisRequestCoordinator(controller, scheduler, 10, 5) { result.complete(it) }
            val original = fixture.sources.single()
            val revisions =
                listOf("c", "ca", "cand").map { suffix ->
                    snapshot(listOf(original.path to original.text.replace("{ can }", "{ $suffix }")), limits)
                }

            revisions.forEach { revision -> coordinator.sourceChanged(revision, original.path) }
            assertEquals(1, factory.fullRebuilds)
            assertEquals(0, factory.incrementalUpdates)
            scheduler.advanceBy(10)

            assertIs<AnalysisClientResult.Success>(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, factory.starts)
            assertEquals(1, factory.fullRebuilds)
            assertEquals(1, factory.incrementalUpdates)
            coordinator.close()
        }
    }

    private fun assertEquivalent(
        fixture: AnalysisMeasurementFixture,
        revisions: (AnalysisMeasurementFixture, AnalysisLimits) -> List<QueryRevision>,
    ) {
        val incrementalFactory = CountingWorkerFactory()
        val fullFactory = CountingWorkerFactory()
        withControllers(incrementalFactory, fullFactory) { incremental, full, limits ->
            val base = snapshot(fixture.sources.map { it.path to it.text }, limits)
            assertIs<SnapshotOpenResult.Opened>(incremental.open(base).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val edits = revisions(fixture, limits)

            edits.forEach { edit ->
                val incrementalResult = successful(incremental.query(edit.snapshot, edit.query).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertIs<SnapshotOpenResult.Opened>(full.open(edit.snapshot).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val fullResult = successful(full.query(edit.snapshot, edit.query).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(normalize(fullResult), normalize(incrementalResult), edit.name)
                assertEquals(
                    1,
                    incrementalFactory.fullRebuilds,
                    "${edit.name} reopen reasons: ${incrementalFactory.reopenReasons}",
                )
            }

            assertEquals(1, incrementalFactory.starts)
            assertEquals(edits.size, incrementalFactory.incrementalUpdates)
        }
    }

    private fun singleFileRevisions(
        fixture: AnalysisMeasurementFixture,
        limits: AnalysisLimits,
    ): List<QueryRevision> {
        val source = fixture.sources.single()
        val body = source.text.replace("fun candidate() = Unit", "fun candidate() { val local = 1 }")
        val signature = body.replace("fun candidate()", "fun candidate(value: Int = 0)")
        val imported = signature.replace("package benchmark", "package benchmark\nimport kotlin.collections.List")
        val invalid = imported.replace("val singleValue0 get() = 0", "val singleValue0 =")
        val identifier = imported.replace("{ can }", "{ cand }")
        val dot = imported.replace("{ can }", "{ \"text\". }")
        return listOf(
            presentation("body", source.path, body, limits),
            presentation("signature", source.path, signature, limits),
            presentation("import", source.path, imported, limits),
            presentation("invalid-text", source.path, invalid, limits),
            completion("identifier-completion", source.path, identifier, "cand", limits),
            completion("dot-completion", source.path, dot, ".", limits),
        )
    }

    private fun fiveFileRevisions(
        fixture: AnalysisMeasurementFixture,
        limits: AnalysisLimits,
    ): List<QueryRevision> {
        val originals = fixture.sources.associate { it.path to it.text }
        val firstPath = fixture.sources.first().path
        val probePath = fixture.sources.last().path

        fun changed(
            first: (String) -> String = { it },
            probe: (String) -> String = { it },
        ): List<Pair<VirtualSourcePath, String>> =
            fixture.sources.map { source ->
                source.path to
                    when (source.path) {
                        firstPath -> first(originals.getValue(source.path))
                        probePath -> probe(originals.getValue(source.path))
                        else -> originals.getValue(source.path)
                    }
            }
        val body = changed(first = { it.replace("fun file0Seed() = 0", "fun file0Seed() = 42") })
        val signature = changed(first = { it.replace("fun file0Seed() = 0", "fun file0Seed(value: Int = 0) = value") })
        val imported = changed(first = { it.replace("package benchmark", "package benchmark\nimport kotlin.collections.List") })
        val invalid = changed(first = { it.replace("val file0Value0 get() = 0", "val file0Value0 =") })
        val identifier = changed(probe = { "$it\nfun completionProbe() { file0S }" })
        val dot = changed(probe = { "$it\nfun completionProbe() { \"text\". }" })
        return listOf(
            presentation("body", body, firstPath, limits),
            presentation("signature", signature, firstPath, limits),
            presentation("import", imported, firstPath, limits),
            presentation("invalid-text", invalid, firstPath, limits),
            completion("identifier-completion", identifier, probePath, "file0S", limits),
            completion("dot-completion", dot, probePath, ".", limits),
        )
    }

    private fun presentation(
        name: String,
        path: VirtualSourcePath,
        text: String,
        limits: AnalysisLimits,
    ) = presentation(name, listOf(path to text), path, limits)

    private fun presentation(
        name: String,
        sources: List<Pair<VirtualSourcePath, String>>,
        path: VirtualSourcePath,
        limits: AnalysisLimits,
    ): QueryRevision {
        val admitted = snapshot(sources, limits)
        return QueryRevision(name, admitted, AnalysisQuery.Presentation(admitted.identity, path))
    }

    private fun completion(
        name: String,
        path: VirtualSourcePath,
        text: String,
        marker: String,
        limits: AnalysisLimits,
    ) = completion(name, listOf(path to text), path, marker, limits)

    private fun completion(
        name: String,
        sources: List<Pair<VirtualSourcePath, String>>,
        path: VirtualSourcePath,
        marker: String,
        limits: AnalysisLimits,
    ): QueryRevision {
        val admitted = snapshot(sources, limits)
        val text = sources.single { it.first == path }.second
        val query = AnalysisQuery.Completion(admitted.identity, path, text.lastIndexOf(marker) + marker.length, CompletionTrigger.Manual)
        return QueryRevision(name, admitted, query)
    }

    private fun snapshot(
        sources: List<Pair<VirtualSourcePath, String>>,
        limits: AnalysisLimits,
    ): AdmittedAnalysisSnapshot {
        val project =
            ProjectSnapshot.of(
                sources.map { (path, text) -> ProjectSource(path, BinaryValue.of(text.encodeToByteArray())) },
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 27 }))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(project), profile)
        return AdmittedAnalysisSnapshot(
            identity,
            project,
            AdmittedAnalysisProfile(
                profile,
                ru.lazyhat.compukters.ide.analysis.k2
                    .testAdmittedPlatform(),
            ),
            limits,
        )
    }

    private fun successful(result: AnalysisClientResult): AnalysisResult = assertIs<AnalysisClientResult.Success>(result).result

    private fun normalize(result: AnalysisResult): Any =
        when (result) {
            is AnalysisResult.Presentation -> {
                val active = assertIs<SnapshotPresentationAcceptance.Active>(result.value.accept(result.identity))
                NormalizedPresentation(
                    active.diagnostics.sortedBy(EditorDiagnostic::toString),
                    active.semanticTokens.sortedBy(SemanticToken::toString),
                    active.locations.sortedBy(SourceLocation::toString),
                )
            }

            is AnalysisResult.Completion -> {
                NormalizedCompletion(result.replacement, result.items.sortedBy(CompletionItem::toString))
            }

            else -> {
                result
            }
        }

    private fun withController(
        factory: CountingWorkerFactory,
        block: (AnalysisWorkerController, CountingWorkerFactory, AnalysisLimits) -> Unit,
    ) {
        withControllers(factory, CountingWorkerFactory()) { controller, _, limits -> block(controller, factory, limits) }
    }

    private fun withControllers(
        firstFactory: CountingWorkerFactory,
        secondFactory: CountingWorkerFactory,
        block: (AnalysisWorkerController, AnalysisWorkerController, AnalysisLimits) -> Unit,
    ) {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val java = Path.of(checkNotNull(System.getProperty("compukters.analysis.java"))).toAbsolutePath().normalize()
        val root = createTempDirectory("compukters-incremental-equivalence-").toAbsolutePath().normalize()
        val limits = AnalysisLimits()
        val identity =
            AnalysisWorkerIdentity(
                payload.manifest.identityProperties.getValue("compiler"),
                payload.manifest.identityProperties.getValue("language"),
                Hash256.of(payload.manifest.payloadHash.toByteArray()),
                ru.lazyhat.compukters.ide.analysis.k2
                    .testPlatformAbi(),
            )

        fun controller(
            name: String,
            factory: WorkerProcessFactory,
        ) = AnalysisWorkerController(
            WorkerLaunch(
                java,
                payload.classpath,
                payload.manifest.mainClass,
                512,
                256,
                root.resolve(name),
                limits.frameBytes + 12,
                64 * 1024,
            ),
            identity,
            limits,
            factory,
            AnalysisWorkerPolicy(30_000_000_000, 60_000_000_000, 250),
        )
        val first = controller("incremental", firstFactory)
        val second = controller("full", secondFactory)
        try {
            block(first, second, limits)
        } finally {
            first.close()
            second.close()
            root.toFile().deleteRecursively()
        }
    }

    private data class QueryRevision(
        val name: String,
        val snapshot: AdmittedAnalysisSnapshot,
        val query: AnalysisQuery,
    )

    private data class NormalizedPresentation(
        val diagnostics: List<EditorDiagnostic>,
        val semanticTokens: List<SemanticToken>,
        val locations: List<SourceLocation>,
    )

    private data class NormalizedCompletion(
        val replacement: ru.lazyhat.compukters.ide.editor.EditorRange,
        val items: List<CompletionItem>,
    )

    private companion object {
        const val TIMEOUT_SECONDS = 90L
    }
}

private class CountingWorkerFactory : WorkerProcessFactory {
    private val delegate = JdkWorkerProcessFactory()

    var starts = 0
        private set
    var fullRebuilds = 0
        private set
    var incrementalUpdates = 0
        private set
    val reopenReasons = mutableListOf<String>()

    override fun start(launch: WorkerLaunch): WorkerProcess {
        starts += 1
        val process = delegate.start(launch)
        return object : WorkerProcess by process {
            override fun writeFrame(frame: ByteArray) {
                when (AnalysisFrameCodec.decode(frame, frame.size).type) {
                    AnalysisMessageType.OpenSnapshot -> fullRebuilds += 1
                    AnalysisMessageType.UpdateSnapshot -> incrementalUpdates += 1
                    else -> Unit
                }
                process.writeFrame(frame)
            }

            override fun readFrame(deadlineNanos: Long): ByteArray? {
                val frame = process.readFrame(deadlineNanos) ?: return null
                val envelope = AnalysisFrameCodec.decode(frame, frame.size)
                if (envelope.type == AnalysisMessageType.SnapshotReopenRequired) {
                    reopenReasons +=
                        (AnalysisMessageCodec.decode(envelope, AnalysisProtocolContext.unbound()) as SnapshotReopenRequired).reason
                }
                return frame
            }
        }
    }
}

private class ManualScheduler : AnalysisTaskScheduler {
    private var now = 0L
    private val tasks = mutableListOf<Task>()

    override fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): AnalysisScheduledTask {
        val task = Task(now + delayNanos, action)
        tasks += task
        return AnalysisScheduledTask { task.cancelled = true }
    }

    fun advanceBy(nanos: Long) {
        now += nanos
        while (true) {
            val task = tasks.filterNot(Task::cancelled).filter { it.deadline <= now }.minByOrNull(Task::deadline) ?: return
            tasks.remove(task)
            task.action()
        }
    }

    override fun close() = Unit

    private class Task(
        val deadline: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )
}
