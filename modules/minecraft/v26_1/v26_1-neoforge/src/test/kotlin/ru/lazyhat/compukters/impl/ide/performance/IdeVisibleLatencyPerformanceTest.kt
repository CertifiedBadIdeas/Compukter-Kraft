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

package ru.lazyhat.compukters.impl.ide.performance

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageType
import ru.lazyhat.compukters.ide.client.analysis.BoundedIdeVisibleLatencyCollector
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyClock
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyKind
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencySample
import ru.lazyhat.compukters.ide.client.controller.IdeClientController
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.workspace.DefaultIdeWorkspace
import ru.lazyhat.compukters.ide.client.workspace.IdeMutationRequest
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveRequest
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.compiler.profile.COMPUKTER_ARTIFACT_ABI
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import ru.lazyhat.compukters.ide.project.ProjectResolution
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.ide.IdeClientApplication
import ru.lazyhat.compukters.impl.ide.IdeClientPaths
import ru.lazyhat.compukters.impl.ide.IdeRenderGeometry
import ru.lazyhat.compukters.impl.ide.IdeRenderer
import ru.lazyhat.compukters.impl.ide.IdeVisibleFrameEvidence
import ru.lazyhat.compukters.impl.ide.ProductionIdeApplicationFactory
import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeVisibleLatencyPerformanceTest {
    @Test
    fun `production analysis reaches the first visible frame latency targets`() {
        listOf(IdeVisibleLatencyFixtures.singleFile(), IdeVisibleLatencyFixtures.fiveFiles()).forEach { fixture ->
            val report = measure(fixture)
            println("compukters.ide.fixture=${fixture.name}")
            println(report.render())
            assertEquals(MEASURED_CYCLES, report.presentation.count, fixture.name)
            assertEquals(MEASURED_CYCLES, report.completion.count, fixture.name)
            assertTrue(report.presentation.totalMedianNanos <= PRESENTATION_MEDIAN_NANOS, "${fixture.name} presentation median")
            assertTrue(report.presentation.totalP95Nanos <= PRESENTATION_P95_NANOS, "${fixture.name} presentation p95")
            assertTrue(report.completion.totalMedianNanos <= COMPLETION_MEDIAN_NANOS, "${fixture.name} completion median")
            assertTrue(report.completion.totalP95Nanos <= COMPLETION_P95_NANOS, "${fixture.name} completion p95")
            assertEquals(1, report.workerStarts, fixture.name)
            assertEquals(1, report.fullRebuilds, fixture.name)
            assertEquals(EXPECTED_INCREMENTAL_UPDATES, report.incrementalUpdates, fixture.name)
            assertTrue(report.heapBytes <= MAXIMUM_HEAP_BYTES, fixture.name)
        }
    }

    private fun measure(fixture: IdeVisibleLatencyFixture): IdeVisibleLatencyReport {
        val gameRoot = createTempDirectory("compukters-visible-latency-").toAbsolutePath().normalize()
        val paths = IdeClientPaths.at(gameRoot)
        val prepared = ProductionIdeApplicationFactory.prepare(paths)
        seedProject(paths, fixture, prepared)
        val trace = BoundedIdeVisibleLatencyCollector(IdeVisibleLatencyClock.System, maximumSamples = 128)
        val processFactory = CountingWorkerProcessFactory()
        var application: IdeClientApplication? = null
        try {
            application =
                ProductionIdeApplicationFactory.open(paths, trace) { workspace ->
                    CompletableFuture.completedFuture(
                        ProductionIdeApplicationFactory.createTooling(
                            paths = paths,
                            workspace = workspace,
                            prepared = prepared,
                            visibleLatency = trace,
                            analysisProcessFactory = processFactory,
                        ),
                    )
                }
            val controller = application.controller
            openFixture(controller, processFactory, fixture)

            var measurementRevision = 0
            repeat(WARM_UP_CYCLES) {
                measurementRevision = runCycle(controller, trace, processFactory, fixture, measurementRevision, null, null)
            }

            val presentation = mutableListOf<IdeVisibleLatencySample>()
            val completion = mutableListOf<IdeVisibleLatencySample>()
            repeat(MEASURED_CYCLES) {
                measurementRevision = runCycle(controller, trace, processFactory, fixture, measurementRevision, presentation, completion)
            }

            val process = checkNotNull(processFactory.process) { "analysis worker was not started" }
            val pid = checkNotNull(process.processId) { "analysis worker PID is unavailable" }
            val rssBytes = residentBytes(pid)
            val memory = jcmdMemory(prepared.java, pid)
            assertTrue(memory.metaspaceBytes <= MAXIMUM_METASPACE_BYTES, fixture.name)
            assertTrue(rssBytes > 0, fixture.name)

            application.close()
            application = null
            awaitExit(pid)
            return IdeVisibleLatencyReport(
                presentation = IdeVisiblePhaseSamples(presentation),
                completion = IdeVisiblePhaseSamples(completion),
                droppedTraces = trace.droppedTraces,
                workerStarts = processFactory.starts,
                fullRebuilds = processFactory.fullRebuilds,
                incrementalUpdates = processFactory.incrementalUpdates,
                heapBytes = memory.heapBytes,
                metaspaceBytes = memory.metaspaceBytes,
                rssBytes = rssBytes,
            )
        } finally {
            application?.close()
            gameRoot.toFile().deleteRecursively()
        }
    }

    private fun runCycle(
        controller: IdeClientController,
        trace: BoundedIdeVisibleLatencyCollector,
        processFactory: CountingWorkerProcessFactory,
        fixture: IdeVisibleLatencyFixture,
        initialMeasurementRevision: Int,
        presentationSamples: MutableList<IdeVisibleLatencySample>?,
        completionSamples: MutableList<IdeVisibleLatencySample>?,
    ): Int {
        val presentationRevision = Math.incrementExact(initialMeasurementRevision)
        var expectedUpdates = processFactory.incrementalUpdates + 1
        val presentationEditorRevision = replaceActiveSource(controller, fixture.activeText(presentationRevision))
        val presentation = awaitVisibleSample(controller, trace, IdeVisibleLatencyKind.Presentation, presentationEditorRevision)
        assertEquals(expectedUpdates, processFactory.incrementalUpdates, "${fixture.name} presentation $presentationRevision")
        presentationSamples?.add(presentation)

        controller.dispatch(
            IdeCommand.Edit(
                IdeEditorInput.SetCaret(
                    fixture.completionCaretUtf16(presentationRevision),
                    extendSelection = false,
                ),
            ),
        )
        controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("d")))
        val completionEditorRevision = textEditor(controller).contentRevision
        val completion = awaitVisibleSample(controller, trace, IdeVisibleLatencyKind.AutomaticCompletion, completionEditorRevision)
        expectedUpdates++
        assertEquals(expectedUpdates, processFactory.incrementalUpdates, "${fixture.name} completion $presentationRevision")
        completionSamples?.add(completion)

        val resetRevision = Math.incrementExact(presentationRevision)
        val resetEditorRevision = replaceActiveSource(controller, fixture.activeText(resetRevision))
        awaitVisibleSample(controller, trace, IdeVisibleLatencyKind.Presentation, resetEditorRevision)
        expectedUpdates++
        assertEquals(expectedUpdates, processFactory.incrementalUpdates, "${fixture.name} reset $resetRevision")
        return resetRevision
    }

    private fun replaceActiveSource(
        controller: IdeClientController,
        text: String,
    ): Long {
        controller.dispatch(IdeCommand.Edit(IdeEditorInput.SelectAll))
        controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type(text)))
        return textEditor(controller).contentRevision
    }

    private fun openFixture(
        controller: IdeClientController,
        processFactory: CountingWorkerProcessFactory,
        fixture: IdeVisibleLatencyFixture,
    ) {
        awaitController(controller) { state ->
            state.tooling == IdeToolingState.Ready &&
                (state.page as? IdePageState.Start)?.projects?.any { it.directoryName == PROJECT_NAME } == true
        }
        controller.dispatch(IdeCommand.OpenProject(PROJECT_NAME))
        awaitController(controller) { state ->
            (state.page as? IdePageState.Workspace)?.value?.project?.directoryName == PROJECT_NAME
        }
        controller.dispatch(IdeCommand.OpenFile(fixture.activePath))
        awaitController(controller) { state ->
            val editor = ((state.page as? IdePageState.Workspace)?.value?.editor as? IdeEditorView.Text)
            editor?.path == fixture.activePath &&
                editor.analysis is IdeAnalysisState.Active &&
                processFactory.fullRebuilds == 1L &&
                IdeVisibleFrameEvidence.from(state, IdeRenderer.extract(state, GEOMETRY))?.presentationVisible == true
        }
    }

    private fun awaitController(
        controller: IdeClientController,
        accepted: (ru.lazyhat.compukters.ide.client.state.IdeViewState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            controller.tick()
            if (accepted(controller.viewState())) return
            LockSupport.parkNanos(1_000_000L)
        }
        error("IDE controller did not reach the expected state before timeout: ${controller.viewState()}")
    }

    private fun awaitVisibleSample(
        controller: IdeClientController,
        trace: BoundedIdeVisibleLatencyCollector,
        kind: IdeVisibleLatencyKind,
        expectedRevision: Long,
    ): IdeVisibleLatencySample {
        val previousSamples = trace.samples().size
        var nextTick = System.nanoTime()
        var nextFrame = nextTick
        val deadline = nextTick + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val now = System.nanoTime()
            if (now >= nextTick) {
                controller.tick()
                nextTick = nextDeadline(nextTick, TICK_NANOS, now)
            }
            if (now >= nextFrame) {
                val state = controller.viewState()
                val model = IdeRenderer.extract(state, GEOMETRY)
                IdeVisibleFrameEvidence.from(state, model)?.let { evidence ->
                    trace.frameExtracted(evidence.documentRevision, evidence.presentationVisible, evidence.completionVisible)
                }
                nextFrame = nextDeadline(nextFrame, FRAME_NANOS, now)
            }
            trace
                .samples()
                .drop(previousSamples)
                .firstOrNull { it.kind == kind && it.documentRevision == expectedRevision }
                ?.let { return it }
            LockSupport.parkNanos(250_000L)
        }
        error("visible $kind sample for revision $expectedRevision was not produced before timeout")
    }

    private fun nextDeadline(
        previous: Long,
        period: Long,
        now: Long,
    ): Long {
        val elapsedPeriods = Math.addExact(Math.floorDiv(Math.subtractExact(now, previous), period), 1L)
        return Math.addExact(previous, Math.multiplyExact(elapsedPeriods, period))
    }

    private fun textEditor(controller: IdeClientController): IdeEditorView.Text =
        assertIs<IdeEditorView.Text>((assertIs<IdePageState.Workspace>(controller.viewState().page)).value.editor)

    private fun seedProject(
        paths: IdeClientPaths,
        fixture: IdeVisibleLatencyFixture,
        prepared: ProductionIdeApplicationFactory.PreparedWorkers,
    ) {
        DefaultIdeWorkspace(paths.projects).use { workspace ->
            val project = workspace.createProject(PROJECT_NAME).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            fixture.sources.forEach { source ->
                if (source.path != ProjectPath.file("src/main.kt")) {
                    workspace
                        .mutate(IdeMutationRequest.CreateText(project.handle, source.path))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                val opened =
                    assertIs<ProjectFileOpenResult.Text>(
                        workspace.open(project.handle, source.path).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    )
                workspace
                    .save(
                        IdeSaveRequest(
                            project.handle,
                            source.path,
                            opened.snapshot.revision,
                            if (source.path == fixture.activePath) fixture.activeText(0) else source.text,
                        ),
                    ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            val manifest =
                ProjectManifestCodec.decode(
                    project.handle.canonicalPath
                        .resolve("compukter.toml")
                        .toFile()
                        .readText(),
                )
            val identity = prepared.compilerPayload.manifest.identity
            val toolchain =
                ToolchainLockIdentity(
                    compilerVersion = identity.compilerVersion,
                    languageVersion = identity.languageVersion,
                    codegenAbi = identity.codegenAbi,
                    artifactAbi = COMPUKTER_ARTIFACT_ABI,
                    artifactWriterVersion = identity.artifactWriterVersion,
                    payloadHash = identity.payloadHash,
                    platformAbi = identity.platformAbi,
                )
            ProjectLockService(project.handle.lockFileWriter())
                .createLock(manifest, ProjectResolution(toolchain, PlatformCatalog.of(prepared.platform)))
        }
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(10_000_000L)
        }
        assertTrue(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "analysis worker $pid remained alive")
    }

    private data class WorkerMemory(
        val heapBytes: Long,
        val metaspaceBytes: Long,
    )

    private companion object {
        const val PROJECT_NAME = "visible-latency"
        const val TIMEOUT_SECONDS = 90L
        const val WARM_UP_CYCLES = 5
        const val MEASURED_CYCLES = 20
        const val EXPECTED_INCREMENTAL_UPDATES = (WARM_UP_CYCLES + MEASURED_CYCLES) * 3L
        const val TICK_NANOS = 50_000_000L
        const val FRAME_NANOS = 16_666_667L
        const val PRESENTATION_MEDIAN_NANOS = 350_000_000L
        const val PRESENTATION_P95_NANOS = 600_000_000L
        const val COMPLETION_MEDIAN_NANOS = 150_000_000L
        const val COMPLETION_P95_NANOS = 250_000_000L
        const val MAXIMUM_HEAP_BYTES = 512L * 1024 * 1024
        const val MAXIMUM_METASPACE_BYTES = 256L * 1024 * 1024
        val GEOMETRY = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, TerminalFontProfile.DINA)
    }
}

private class CountingWorkerProcessFactory : WorkerProcessFactory {
    private val delegate = JdkWorkerProcessFactory()
    private val startCount = AtomicLong()
    private val fullRebuildCount = AtomicLong()
    private val incrementalUpdateCount = AtomicLong()

    @Volatile
    var process: WorkerProcess? = null
        private set

    val starts: Long
        get() = startCount.get()
    val fullRebuilds: Long
        get() = fullRebuildCount.get()
    val incrementalUpdates: Long
        get() = incrementalUpdateCount.get()

    override fun start(launch: WorkerLaunch): WorkerProcess {
        startCount.incrementAndGet()
        return CountingWorkerProcess(delegate.start(launch), fullRebuildCount, incrementalUpdateCount).also { process = it }
    }
}

private class CountingWorkerProcess(
    private val delegate: WorkerProcess,
    private val fullRebuilds: AtomicLong,
    private val incrementalUpdates: AtomicLong,
) : WorkerProcess by delegate {
    override fun writeFrame(frame: ByteArray) {
        when (AnalysisFrameCodec.decode(frame, frame.size).type) {
            AnalysisMessageType.OpenSnapshot -> fullRebuilds.incrementAndGet()
            AnalysisMessageType.UpdateSnapshot -> incrementalUpdates.incrementAndGet()
            else -> Unit
        }
        delegate.writeFrame(frame)
    }
}
