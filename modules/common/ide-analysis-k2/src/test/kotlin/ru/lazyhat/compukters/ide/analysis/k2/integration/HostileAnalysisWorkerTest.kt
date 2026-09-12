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
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerPolicy
import ru.lazyhat.compukters.ide.analysis.controller.SnapshotOpenResult
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HostileAnalysisWorkerTest {
    @Test
    fun `startup timeout is typed and a clean worker can restart`() {
        withHostileThenHealthy("startup-hang") { controller, snapshot ->
            val failed = assertIs<SnapshotOpenResult.Failure>(controller.open(snapshot).get(90, TimeUnit.SECONDS))
            assertEquals(AnalysisFailureKind.Timeout, failed.kind)
        }
    }

    @Test
    fun `wrong handshake and open correlations are protocol failures`() {
        listOf("wrong-handshake", "wrong-open-request", "wrong-open-profile").forEach { mode ->
            withHostileThenHealthy(mode) { controller, snapshot ->
                val failed = assertIs<SnapshotOpenResult.Failure>(controller.open(snapshot).get(90, TimeUnit.SECONDS))
                assertEquals(AnalysisFailureKind.Protocol, failed.kind, mode)
            }
        }
    }

    @Test
    fun `query timeout and ignored cancellation permit clean restart`() {
        withHostileThenHealthy("query-hang") { controller, snapshot ->
            assertIs<SnapshotOpenResult.Opened>(controller.open(snapshot).get(90, TimeUnit.SECONDS))
            val failed =
                controller.query(
                    snapshot,
                    AnalysisQuery.Presentation(snapshot.identity, VirtualSourcePath.kotlin("main.kt")),
                )
            assertEquals(AnalysisFailureKind.Timeout, assertIs<AnalysisClientResult.Failure>(failed.get(90, TimeUnit.SECONDS)).kind)
        }
        withHostileThenHealthy("query-hang") { controller, snapshot ->
            assertIs<SnapshotOpenResult.Opened>(controller.open(snapshot).get(90, TimeUnit.SECONDS))
            val cancelled =
                controller.query(
                    snapshot,
                    AnalysisQuery.Completion(
                        snapshot.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        3,
                        CompletionTrigger.Manual,
                    ),
                )
            Thread.sleep(100)
            assertTrue(controller.cancel(cancelled))
            assertEquals(AnalysisClientResult.Cancelled, cancelled.get(90, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `worker exit OOM and long stderr are typed and bounded`() {
        withHostileThenHealthy("exit") { controller, snapshot ->
            assertQueryFailure(controller, snapshot, AnalysisFailureKind.WorkerExit)
        }
        withHostileThenHealthy("oom") { controller, snapshot ->
            val failure = assertQueryFailure(controller, snapshot, AnalysisFailureKind.MemoryLimit)
            assertTrue(failure.detail.encodeToByteArray().size <= 4 * 1024)
        }
    }

    @Test
    fun `malformed truncated oversized and excessive responses are protocol failures`() {
        listOf("malformed", "truncated", "oversized", "excessive-result").forEach { mode ->
            withHostileThenHealthy(mode) { controller, snapshot ->
                assertQueryFailure(controller, snapshot, AnalysisFailureKind.Protocol)
            }
        }
    }

    @Test
    fun `wrong query request snapshot and profile correlations are protocol failures`() {
        listOf("wrong-query-request", "wrong-query-snapshot", "wrong-query-profile").forEach { mode ->
            withHostileThenHealthy(mode) { controller, snapshot ->
                assertQueryFailure(controller, snapshot, AnalysisFailureKind.Protocol)
            }
        }
    }

    private fun withHostileThenHealthy(
        mode: String,
        block: (AnalysisWorkerController, AdmittedAnalysisSnapshot) -> Unit,
    ) {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val java = Path.of(checkNotNull(System.getProperty("compukters.analysis.java"))).toAbsolutePath().normalize()
        val testClasspath =
            checkNotNull(System.getProperty("compukters.analysis.testClasspath"))
                .split(File.pathSeparator)
                .map { Path.of(it).toAbsolutePath().normalize() }
        val root = createTempDirectory("compukters-hostile-analysis-").toAbsolutePath().normalize()
        val limits = AnalysisLimits()
        val identity =
            AnalysisWorkerIdentity(
                payload.manifest.identityProperties.getValue("compiler"),
                payload.manifest.identityProperties.getValue("language"),
                Hash256.of(payload.manifest.payloadHash.toByteArray()),
                ru.lazyhat.compukters.ide.analysis.k2
                    .testPlatformAbi(),
            )
        val healthyLaunch =
            WorkerLaunch(
                java,
                payload.classpath,
                payload.manifest.mainClass,
                512,
                256,
                root.resolve("healthy"),
                limits.frameBytes + 12,
                4 * 1024,
            )
        val hostileRoot = root.resolve(mode)
        Files.createDirectories(hostileRoot)
        Files.writeString(
            hostileRoot.resolve("identity.txt"),
            listOf(
                identity.compilerVersion,
                identity.languageVersion,
                identity.payloadHash.hex(),
                identity.platformAbi.hex(),
            ).joinToString("\n", postfix = "\n"),
        )
        val factory = HostileThenHealthyFactory(testClasspath, hostileRoot)
        val controller =
            AnalysisWorkerController(
                healthyLaunch,
                identity,
                limits,
                factory,
                AnalysisWorkerPolicy(2_000_000_000, 2_000_000_000, 100),
            )
        try {
            val snapshot = snapshot(limits)
            block(controller, snapshot)
            val recovered =
                assertIs<AnalysisClientResult.Success>(
                    controller
                        .query(
                            snapshot,
                            AnalysisQuery.Presentation(snapshot.identity, VirtualSourcePath.kotlin("main.kt")),
                        ).get(90, TimeUnit.SECONDS),
                )
            assertEquals(snapshot.identity, recovered.result.identity)
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun assertQueryFailure(
        controller: AnalysisWorkerController,
        snapshot: AdmittedAnalysisSnapshot,
        expected: AnalysisFailureKind,
    ): AnalysisClientResult.Failure {
        assertIs<SnapshotOpenResult.Opened>(controller.open(snapshot).get(90, TimeUnit.SECONDS))
        val failed =
            controller
                .query(
                    snapshot,
                    AnalysisQuery.Completion(
                        snapshot.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        3,
                        CompletionTrigger.Manual,
                    ),
                ).get(90, TimeUnit.SECONDS)
        return assertIs<AnalysisClientResult.Failure>(failed).also { assertEquals(expected, it.kind) }
    }

    private fun snapshot(limits: AnalysisLimits): AdmittedAnalysisSnapshot {
        val path = VirtualSourcePath.kotlin("main.kt")
        val sources =
            ProjectSnapshot.of(
                listOf(ProjectSource(path, BinaryValue.of("val answer = 42".encodeToByteArray()))),
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 5 }))
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
}

private class HostileThenHealthyFactory(
    private val hostileClasspath: List<Path>,
    private val hostileTemporaryDirectory: Path,
) : WorkerProcessFactory {
    private val delegate = JdkWorkerProcessFactory()
    private var starts = 0

    override fun start(launch: WorkerLaunch): WorkerProcess {
        starts += 1
        if (starts > 1) return delegate.start(launch)
        return delegate.start(
            launch.copy(
                classpath = hostileClasspath,
                mainClass = HOSTILE_MAIN,
                temporaryDirectory = hostileTemporaryDirectory,
            ),
        )
    }

    private companion object {
        const val HOSTILE_MAIN = "ru.lazyhat.compukters.ide.analysis.k2.integration.HostileAnalysisWorkerMainKt"
    }
}
