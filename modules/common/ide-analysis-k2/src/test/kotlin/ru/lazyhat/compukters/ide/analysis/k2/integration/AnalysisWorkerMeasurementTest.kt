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
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClient
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisResultSink
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisScheduledTask
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisServiceLifetime
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisTaskScheduler
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerPolicy
import ru.lazyhat.compukters.ide.analysis.controller.DefaultAnalysisRequestCoordinator
import ru.lazyhat.compukters.ide.analysis.controller.SnapshotOpenResult
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisMeasurementFixture
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisMeasurementFixtures
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisPerformanceReport
import ru.lazyhat.compukters.ide.analysis.k2.measurement.PhaseSamples
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageType
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalysisWorkerMeasurementTest {
    @Test
    fun `real worker emits incremental measurements and exits after idle`() {
        val performanceGate = System.getProperty("compukters.analysis.performance") == "true"
        val sampleCount = if (performanceGate) PERFORMANCE_SAMPLES else STRUCTURAL_SAMPLES
        val reports =
            listOf(
                "single-file" to AnalysisMeasurementFixtures.singleFile(),
                "five-file" to AnalysisMeasurementFixtures.fiveFiles(),
                "maximum-files" to AnalysisMeasurementFixtures.maximumFiles(),
            ).map { (name, fixture) -> name to measureFixture(fixture, sampleCount) }

        reports.forEach { (name, report) ->
            println("compukters.analysis.fixture=$name")
            println(report.render())
            assertEquals(1, report.workerStarts, name)
            assertEquals(1, report.fullRebuilds, name)
            assertTrue(report.incrementalUpdates > 0, name)
            assertTrue(report.heapBytes <= MAXIMUM_HEAP_MIB * MIB, name)
            if (performanceGate && name != "maximum-files") enforceLatencyTargets(name, report)
        }
    }

    private fun measureFixture(
        fixture: AnalysisMeasurementFixture,
        sampleCount: Int,
    ): AnalysisPerformanceReport {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val java = Path.of(checkNotNull(System.getProperty("compukters.analysis.java"))).toAbsolutePath().normalize()
        val root = createTempDirectory("compukters-analysis-measurement-").toAbsolutePath().normalize()
        val defaults = AnalysisLimits()
        val limits = defaults.copy(sourceFiles = maxOf(defaults.sourceFiles, fixture.sources.size))
        val launch =
            WorkerLaunch(
                java,
                payload.classpath,
                payload.manifest.mainClass,
                MAXIMUM_HEAP_MIB,
                MAXIMUM_METASPACE_MIB,
                root.resolve("worker"),
                limits.frameBytes + 12,
                64 * 1024,
            )
        val workerIdentity =
            AnalysisWorkerIdentity(
                payload.manifest.identityProperties.getValue("compiler"),
                payload.manifest.identityProperties.getValue("language"),
                Hash256.of(payload.manifest.payloadHash.toByteArray()),
                ru.lazyhat.compukters.ide.analysis.k2
                    .testPlatformAbi(),
            )
        val factory = MeasuringWorkerFactory()
        val service =
            AnalysisServiceLifetime(IDLE_NANOS) {
                AnalysisWorkerController(
                    launch,
                    workerIdentity,
                    limits,
                    factory,
                    AnalysisWorkerPolicy(30_000_000_000, 60_000_000_000, 250),
                )
            }
        try {
            val session = service.openSession()
            val client = session.client
            val base = snapshot(fixture, 0, limits, includeProbe = false)
            val admissionStartedNanos = System.nanoTime()
            val openResult = client.open(base).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertIs<SnapshotOpenResult.Opened>(openResult, openResult.toString())
            val initialAdmissionNanos = System.nanoTime() - admissionStartedNanos
            factory.probe.takeSnapshotApply()

            var revision = 0
            repeat(WARM_UP_CYCLES) {
                revision = warmUp(client, fixture, limits, revision, factory.probe)
            }

            val snapshotApply = mutableListOf<Long>()
            val presentation = mutableListOf<Long>()
            val completion = mutableListOf<Long>()
            repeat(sampleCount) {
                val presentationRevision = revision(fixture, limits, ++revision)
                successful(
                    client
                        .query(
                            presentationRevision.snapshot,
                            AnalysisQuery.Presentation(presentationRevision.snapshot.identity, presentationRevision.path),
                        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                snapshotApply += factory.probe.takeSnapshotApply()
                presentation += factory.probe.takeQuery()

                val completionRevision = revision(fixture, limits, ++revision)
                successful(
                    client
                        .query(
                            completionRevision.snapshot,
                            AnalysisQuery.Completion(
                                completionRevision.snapshot.identity,
                                completionRevision.path,
                                completionRevision.offsetUtf16,
                                CompletionTrigger.Manual,
                            ),
                        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                snapshotApply += factory.probe.takeSnapshotApply()
                completion += factory.probe.takeQuery()
            }

            val endToEndPresentation = mutableListOf<Long>()
            val endToEndCompletion = mutableListOf<Long>()
            repeat(sampleCount) {
                val presentationRevision = revision(fixture, limits, ++revision)
                endToEndPresentation += measureEndToEndPresentation(client, presentationRevision)
                factory.probe.takeSnapshotApply()
                factory.probe.takeQuery()

                val completionRevision = revision(fixture, limits, ++revision)
                endToEndCompletion += measureEndToEndCompletion(client, completionRevision)
                factory.probe.takeSnapshotApply()
                factory.probe.takeQuery()
            }

            val cancellation = mutableListOf<Long>()
            repeat(sampleCount) {
                val current = revision(fixture, limits, ++revision)
                successful(
                    client
                        .query(current.snapshot, AnalysisQuery.Presentation(current.snapshot.identity, current.path))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                factory.probe.takeSnapshotApply()
                factory.probe.takeQuery()
                cancellation += measureCancellation(client, current, factory.probe)
            }

            val process = checkNotNull(factory.process)
            val pid = checkNotNull(process.processId)
            val residentBytes = residentBytes(pid)
            val memory = jcmdMemory(java, pid)
            assertTrue(memory.metaspaceUsedBytes <= MAXIMUM_METASPACE_MIB * MIB)
            assertTrue(residentBytes > 0)

            val expectedUpdates = revision
            assertEquals(expectedUpdates, factory.probe.incrementalUpdates)
            session.close()
            awaitExit(pid)
            return AnalysisPerformanceReport(
                initialAdmission = PhaseSamples(listOf(initialAdmissionNanos)),
                snapshotApply = PhaseSamples(snapshotApply),
                presentation = PhaseSamples(presentation),
                completion = PhaseSamples(completion),
                endToEndPresentation = PhaseSamples(endToEndPresentation),
                endToEndCompletion = PhaseSamples(endToEndCompletion),
                cancellation = PhaseSamples(cancellation),
                workerStarts = factory.starts,
                fullRebuilds = factory.probe.fullRebuilds,
                incrementalUpdates = factory.probe.incrementalUpdates,
                heapBytes = memory.heapUsedBytes,
                metaspaceBytes = memory.metaspaceUsedBytes,
                rssBytes = residentBytes,
            )
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun warmUp(
        client: AnalysisClient,
        fixture: AnalysisMeasurementFixture,
        limits: AnalysisLimits,
        initialRevision: Int,
        probe: ProtocolMeasurementProbe,
    ): Int {
        var revision = initialRevision
        val presentation = revision(fixture, limits, ++revision)
        successful(
            client
                .query(
                    presentation.snapshot,
                    AnalysisQuery.Presentation(presentation.snapshot.identity, presentation.path),
                ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        probe.takeSnapshotApply()
        probe.takeQuery()

        val completion = revision(fixture, limits, ++revision)
        successful(
            client
                .query(
                    completion.snapshot,
                    AnalysisQuery.Completion(
                        completion.snapshot.identity,
                        completion.path,
                        completion.offsetUtf16,
                        CompletionTrigger.Manual,
                    ),
                ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        probe.takeSnapshotApply()
        probe.takeQuery()
        return revision
    }

    private fun measureEndToEndPresentation(
        client: AnalysisClient,
        revision: MeasurementRevision,
    ): Long {
        val published = CompletableFuture<PublishedResult>()
        ExecutorMeasurementScheduler().use { scheduler ->
            DefaultAnalysisRequestCoordinator(
                client,
                scheduler,
                PRESENTATION_DEBOUNCE_NANOS,
                COMPLETION_DEBOUNCE_NANOS,
                resultSink = matchingSink<AnalysisResult.Presentation>(published),
            ).use { coordinator ->
                val started = System.nanoTime()
                coordinator.sourceChanged(revision.snapshot, revision.path)
                val result = published.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertIs<AnalysisClientResult.Success>(result.result)
                return result.completedNanos - started
            }
        }
    }

    private fun measureEndToEndCompletion(
        client: AnalysisClient,
        revision: MeasurementRevision,
    ): Long {
        val published = CompletableFuture<PublishedResult>()
        ExecutorMeasurementScheduler().use { scheduler ->
            DefaultAnalysisRequestCoordinator(
                client,
                scheduler,
                PRESENTATION_DEBOUNCE_NANOS,
                COMPLETION_DEBOUNCE_NANOS,
                resultSink = matchingSink<AnalysisResult.Completion>(published),
            ).use { coordinator ->
                val started = System.nanoTime()
                coordinator.sourceChanged(revision.snapshot, revision.path)
                coordinator.automaticCompletion(revision.path, revision.offsetUtf16)
                val result = published.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertIs<AnalysisClientResult.Success>(result.result)
                return result.completedNanos - started
            }
        }
    }

    private inline fun <reified T : AnalysisResult> matchingSink(published: CompletableFuture<PublishedResult>): AnalysisResultSink =
        AnalysisResultSink { result ->
            when (result) {
                is AnalysisClientResult.Success -> {
                    if (result.result is T) published.complete(PublishedResult(System.nanoTime(), result))
                }

                else -> {
                    published.complete(PublishedResult(System.nanoTime(), result))
                }
            }
        }

    private fun measureCancellation(
        client: AnalysisClient,
        revision: MeasurementRevision,
        probe: ProtocolMeasurementProbe,
    ): Long {
        probe.clearQueryWrites()
        val cancelled = client.query(revision.snapshot, AnalysisQuery.Presentation(revision.snapshot.identity, revision.path))
        probe.awaitQueryWrite()
        val next =
            client.query(
                revision.snapshot,
                AnalysisQuery.Completion(
                    revision.snapshot.identity,
                    revision.path,
                    revision.offsetUtf16,
                    CompletionTrigger.Manual,
                ),
            )
        assertTrue(client.cancel(cancelled), "active analysis query was not cancellable")
        assertIs<AnalysisClientResult.Cancelled>(cancelled.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        successful(next.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val duration = probe.takeCancellation()
        probe.clearQueryTimings()
        return duration
    }

    private fun revision(
        fixture: AnalysisMeasurementFixture,
        limits: AnalysisLimits,
        revision: Int,
    ): MeasurementRevision {
        val snapshot = snapshot(fixture, revision, limits, includeProbe = true)
        val path = fixture.sources.last().path
        val text =
            snapshot.sources.sources
                .single { it.path == path }
                .content
                .toByteArray()
                .decodeToString()
        val marker = fixture.completionPrefix
        return MeasurementRevision(snapshot, path, text.lastIndexOf(marker) + marker.length)
    }

    private fun snapshot(
        fixture: AnalysisMeasurementFixture,
        revision: Int,
        limits: AnalysisLimits,
        includeProbe: Boolean,
    ): AdmittedAnalysisSnapshot {
        val lastPath = fixture.sources.last().path
        val sources =
            ProjectSnapshot.of(
                fixture.sources.map { source ->
                    val text =
                        if (includeProbe && source.path == lastPath) {
                            buildString {
                                append(source.text)
                                if (fixture.sources.size > 1) {
                                    append("\nfun completionProbe() { ${fixture.completionPrefix} }")
                                }
                                append("\nval measurementRevision = $revision")
                            }
                        } else {
                            source.text
                        }
                    ProjectSource(source.path, BinaryValue.of(text.encodeToByteArray()))
                },
                WorkerLimits(
                    sourceFiles = limits.sourceFiles,
                    sourceFileBytes = limits.sourceFileBytes,
                    sourceBytes = limits.sourceBytes,
                ),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 31 }))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        return AdmittedAnalysisSnapshot(
            identity,
            sources,
            AdmittedAnalysisProfile(
                profile,
                ru.lazyhat.compukters.ide.analysis.k2
                    .testAdmittedPlatform(),
            ),
            limits,
        )
    }

    private fun successful(result: AnalysisClientResult): AnalysisResult = assertIs<AnalysisClientResult.Success>(result).result

    private fun enforceLatencyTargets(
        fixture: String,
        report: AnalysisPerformanceReport,
    ) {
        assertTrue(report.completion.medianNanos <= 100_000_000, "$fixture completion median")
        assertTrue(report.completion.p95Nanos <= 200_000_000, "$fixture completion p95")
        assertTrue(report.presentation.medianNanos <= 250_000_000, "$fixture presentation median")
        assertTrue(report.presentation.p95Nanos <= 500_000_000, "$fixture presentation p95")
    }

    private fun residentBytes(pid: Long): Long {
        val line = Files.readAllLines(Path.of("/proc/$pid/status")).first { it.startsWith("VmRSS:") }
        return line
            .removePrefix("VmRSS:")
            .trim()
            .substringBefore(' ')
            .toLong() * 1024
    }

    private fun jcmdMemory(
        java: Path,
        pid: Long,
    ): WorkerMemory {
        val heapOutput = jcmd(java, pid, "GC.heap_info")
        val countersOutput = jcmd(java, pid, "PerfCounter.print")
        val heapKiB = requireNotNull(Regex("used (\\d+)K").find(heapOutput)) { heapOutput }.groupValues[1].toLong()
        val metaspaceBytes =
            requireNotNull(Regex("sun\\.gc\\.metaspace\\.used=(\\d+)").find(countersOutput)) { countersOutput }
                .groupValues[1]
                .toLong()
        return WorkerMemory(heapKiB * 1024, metaspaceBytes)
    }

    private fun jcmd(
        java: Path,
        pid: Long,
        command: String,
    ): String {
        val process = ProcessBuilder(java.parent.resolve("jcmd").toString(), pid.toString(), command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
        return output
    }

    private fun awaitExit(pid: Long) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "analysis worker $pid remained alive after idle")
    }

    private data class MeasurementRevision(
        val snapshot: AdmittedAnalysisSnapshot,
        val path: VirtualSourcePath,
        val offsetUtf16: Int,
    )

    private data class PublishedResult(
        val completedNanos: Long,
        val result: AnalysisClientResult,
    )

    private companion object {
        const val MAXIMUM_HEAP_MIB = 512
        const val MAXIMUM_METASPACE_MIB = 256
        const val MIB = 1024L * 1024L
        const val IDLE_NANOS = 100_000_000L
        const val TIMEOUT_SECONDS = 90L
        const val WARM_UP_CYCLES = 5
        const val STRUCTURAL_SAMPLES = 3
        const val PERFORMANCE_SAMPLES = 20
        const val PRESENTATION_DEBOUNCE_NANOS = 150_000_000L
        const val COMPLETION_DEBOUNCE_NANOS = 75_000_000L
    }
}

private class MeasuringWorkerFactory : WorkerProcessFactory {
    private val delegate = JdkWorkerProcessFactory()
    val probe = ProtocolMeasurementProbe()

    @Volatile var process: WorkerProcess? = null
        private set
    var starts: Int = 0
        private set

    override fun start(launch: WorkerLaunch): WorkerProcess {
        starts += 1
        return MeasuringWorkerProcess(delegate.start(launch), probe).also { process = it }
    }
}

private class MeasuringWorkerProcess(
    private val delegate: WorkerProcess,
    private val probe: ProtocolMeasurementProbe,
) : WorkerProcess by delegate {
    override fun writeFrame(frame: ByteArray) {
        probe.written(AnalysisFrameCodec.decode(frame, frame.size).type)
        delegate.writeFrame(frame)
    }

    override fun readFrame(deadlineNanos: Long): ByteArray? {
        val frame = delegate.readFrame(deadlineNanos) ?: return null
        probe.read(AnalysisFrameCodec.decode(frame, frame.size).type)
        return frame
    }
}

private class ProtocolMeasurementProbe {
    private val lock = Any()
    private val snapshotApplies = LinkedBlockingQueue<Long>()
    private val queries = LinkedBlockingQueue<Long>()
    private val cancellations = LinkedBlockingQueue<Long>()
    private val queryWrites = LinkedBlockingQueue<Unit>()
    private val fullRebuildCount = AtomicInteger()
    private val incrementalUpdateCount = AtomicInteger()
    private var snapshotStartedNanos: Long? = null
    private var queryStartedNanos: Long? = null
    private var cancellationStartedNanos: Long? = null
    private var cancellationTerminal = false

    val fullRebuilds: Int
        get() = fullRebuildCount.get()
    val incrementalUpdates: Int
        get() = incrementalUpdateCount.get()

    fun written(type: AnalysisMessageType) {
        val now = System.nanoTime()
        synchronized(lock) {
            when (type) {
                AnalysisMessageType.OpenSnapshot -> {
                    fullRebuildCount.incrementAndGet()
                    snapshotStartedNanos = now
                }

                AnalysisMessageType.UpdateSnapshot -> {
                    incrementalUpdateCount.incrementAndGet()
                    snapshotStartedNanos = now
                }

                AnalysisMessageType.Query -> {
                    if (cancellationTerminal) {
                        cancellations.offer(now - checkNotNull(cancellationStartedNanos))
                        cancellationStartedNanos = null
                        cancellationTerminal = false
                    }
                    queryStartedNanos = now
                    queryWrites.offer(Unit)
                }

                AnalysisMessageType.Cancel -> {
                    cancellationStartedNanos = now
                }

                else -> {
                    Unit
                }
            }
        }
    }

    fun read(type: AnalysisMessageType) {
        val now = System.nanoTime()
        synchronized(lock) {
            when (type) {
                AnalysisMessageType.SnapshotReady,
                AnalysisMessageType.SnapshotUpdated,
                -> {
                    snapshotApplies.offer(now - checkNotNull(snapshotStartedNanos))
                    snapshotStartedNanos = null
                }

                AnalysisMessageType.QuerySuccess -> {
                    queryStartedNanos?.let { queries.offer(now - it) }
                    queryStartedNanos = null
                }

                AnalysisMessageType.Cancelled -> {
                    queryStartedNanos = null
                    cancellationTerminal = true
                }

                else -> {
                    Unit
                }
            }
        }
    }

    fun takeSnapshotApply(): Long = snapshotApplies.takeMeasurement("snapshot apply")

    fun takeQuery(): Long = queries.takeMeasurement("analysis query")

    fun takeCancellation(): Long = cancellations.takeMeasurement("analysis cancellation")

    fun clearQueryWrites() = queryWrites.clear()

    fun clearQueryTimings() {
        queries.clear()
        queryWrites.clear()
    }

    fun awaitQueryWrite() {
        checkNotNull(queryWrites.poll(30, TimeUnit.SECONDS)) { "analysis query was not written" }
    }

    private fun LinkedBlockingQueue<Long>.takeMeasurement(name: String): Long =
        checkNotNull(poll(30, TimeUnit.SECONDS)) { "$name measurement was not recorded" }
}

private class ExecutorMeasurementScheduler : AnalysisTaskScheduler {
    private val executor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "compukters-analysis-measurement-timer").apply { isDaemon = true }
        }

    override fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): AnalysisScheduledTask {
        val future = executor.schedule(action, delayNanos, TimeUnit.NANOSECONDS)
        return AnalysisScheduledTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private data class WorkerMemory(
    val heapUsedBytes: Long,
    val metaspaceUsedBytes: Long,
)
