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
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerPolicy
import ru.lazyhat.compukters.ide.analysis.controller.SnapshotOpenResult
import ru.lazyhat.compukters.ide.analysis.k2.testAdmittedPlatform
import ru.lazyhat.compukters.ide.analysis.k2.testPlatform
import ru.lazyhat.compukters.ide.analysis.k2.testPlatformAbi
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisModule
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisPlatform
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisHandshake
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessage
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQuerySuccess
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReady
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotUpdated
import ru.lazyhat.compukters.ide.analysis.protocol.UpdateSnapshotRequest
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SnapshotAdmissionTest {
    @Test
    fun `forked worker applies an incremental update before querying the target snapshot`() {
        val initial = snapshot(profileByte = 7, main = "package demo\nval answer = 1".encodeToByteArray())
        val updated = snapshot(profileByte = 7, main = "package demo\nval broken: String = 42".encodeToByteArray())
        val limits = AnalysisLimits()
        withRawWorker(limits) { worker ->
            assertIs<AnalysisHandshake>(receive(worker, limits, AnalysisProtocolContext.unbound(limits)))
            val initialContext = AnalysisProtocolContext.of(initial.sources, initial.profile, limits)
            send(
                worker,
                OpenSnapshotRequest(RequestId.of(1uL), initial.identity, initial.sources, initial.profile, limits),
                initialContext,
            )
            assertEquals(initial.identity, assertIs<SnapshotReady>(receive(worker, limits, initialContext)).identity)

            val changedSources =
                updated.sources.sources.filter { candidate ->
                    initial.sources.sources
                        .single { it.path == candidate.path }
                        .content != candidate.content
                }
            send(
                worker,
                UpdateSnapshotRequest(RequestId.of(2uL), initial.identity, updated.identity, changedSources),
                initialContext,
            )
            assertEquals(updated.identity, assertIs<SnapshotUpdated>(receive(worker, limits, initialContext)).targetIdentity)

            val updatedContext = AnalysisProtocolContext.of(updated.sources, updated.profile, limits)
            val query = AnalysisQuery.Presentation(updated.identity, VirtualSourcePath.kotlin("demo/Main.kt"))
            val queryContext = updatedContext.forQuery(query)
            send(
                worker,
                AnalysisQueryRequest(RequestId.of(3uL), query),
                queryContext,
            )
            val result = assertIs<AnalysisQuerySuccess>(receive(worker, limits, queryContext)).result as AnalysisResult.Presentation
            val active = assertIs<SnapshotPresentationAcceptance.Active>(result.value.accept(updated.identity))
            assertTrue(active.diagnostics.any { it.path?.value == "demo/Main.kt" })
        }
    }

    @Test
    fun `forked worker admits multi-file snapshot replaces profile and closes it`() {
        withController { controller ->
            val first = snapshot(profileByte = 1)
            assertEquals(SnapshotOpenResult.Opened(first.identity), controller.open(first).get(90, TimeUnit.SECONDS))

            val replacement = snapshot(profileByte = 2)
            assertEquals(
                SnapshotOpenResult.Opened(replacement.identity),
                controller.open(replacement).get(90, TimeUnit.SECONDS),
            )
            controller.closeSnapshot(replacement.identity).get(90, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `host snapshot boundary rejects malformed UTF-8 before process admission`() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(profileByte = 1, main = byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun `forked worker rejects a platform module whose content does not match its hash`() {
        val module = testPlatform().modules.first()
        val platform =
            AdmittedAnalysisPlatform(
                testPlatformAbi(),
                listOf(AdmittedAnalysisModule(AnalysisModuleIdentity(module.id.toString(), Hash256.of(ByteArray(32) { 9 })))),
            )
        withController { controller ->
            val failure =
                assertIs<SnapshotOpenResult.Failure>(
                    controller.open(snapshot(profileByte = 1, platform = platform)).get(90, TimeUnit.SECONDS),
                )
            assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.kind)
        }
    }

    @Test
    fun `forked worker rejects an unavailable platform module`() {
        val platform =
            AdmittedAnalysisPlatform(
                testPlatformAbi(),
                listOf(AdmittedAnalysisModule(AnalysisModuleIdentity("missing:module", Hash256.of(ByteArray(32) { 7 })))),
            )
        withController { controller ->
            val failure =
                assertIs<SnapshotOpenResult.Failure>(
                    controller.open(snapshot(profileByte = 1, platform = platform)).get(90, TimeUnit.SECONDS),
                )
            assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.kind)
        }
    }

    @Test
    fun `forked worker rejects a foreign platform ABI`() {
        val platform = AdmittedAnalysisPlatform(Hash256.of(ByteArray(32) { 6 }), emptyList())
        withController { controller ->
            val failure =
                assertIs<SnapshotOpenResult.Failure>(
                    controller.open(snapshot(profileByte = 1, platform = platform)).get(90, TimeUnit.SECONDS),
                )
            assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.kind)
        }
    }

    @Test
    fun `forked worker rejects a module selection without dependencies`() {
        val platform = testPlatform()
        val candidate =
            requireNotNull(platform.modules.firstOrNull { it.dependencies.any { dependency -> dependency != platform.builtins.id } }) {
                "native analysis fixture must contain a module with a non-builtins dependency"
            }
        val admitted =
            AdmittedAnalysisModule(
                AnalysisModuleIdentity(
                    candidate.id.toString(),
                    Hash256.of(PlatformBundleCodec.moduleContentHash(candidate).toByteArray()),
                ),
            )
        withController { controller ->
            val failure =
                assertIs<SnapshotOpenResult.Failure>(
                    controller
                        .open(snapshot(profileByte = 1, platform = AdmittedAnalysisPlatform(testPlatformAbi(), listOf(admitted))))
                        .get(90, TimeUnit.SECONDS),
                )
            assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.kind)
        }
    }

    @Test
    fun `source path traversal is rejected before worker admission`() {
        assertFailsWith<IllegalArgumentException> { VirtualSourcePath.kotlin("../Main.kt") }
        assertFailsWith<IllegalArgumentException> { VirtualSourcePath.kotlin("demo/../../Main.kt") }
    }

    @Test
    fun `closed snapshot can be reopened after a worker restart`() {
        val admitted = snapshot(profileByte = 4)
        repeat(2) {
            withController { controller ->
                assertEquals(
                    SnapshotOpenResult.Opened(admitted.identity),
                    controller.open(admitted).get(90, TimeUnit.SECONDS),
                )
                controller.closeSnapshot(admitted.identity).get(90, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `forked worker returns real presentation and expression results`() {
        val main = "package demo\nval emoji = \"😀\"\nval broken: String = 42\nval inferred = \"ok\""
        val admitted = snapshot(profileByte = 5, main = main.encodeToByteArray())
        withController { controller ->
            assertEquals(SnapshotOpenResult.Opened(admitted.identity), controller.open(admitted).get(90, TimeUnit.SECONDS))

            val presentation =
                assertIs<AnalysisClientResult.Success>(
                    controller
                        .query(
                            admitted,
                            AnalysisQuery.Presentation(admitted.identity, VirtualSourcePath.kotlin("demo/Main.kt")),
                        ).get(90, TimeUnit.SECONDS),
                ).result as AnalysisResult.Presentation
            val active = assertIs<SnapshotPresentationAcceptance.Active>(presentation.value.accept(admitted.identity))
            assertTrue(active.diagnostics.any { it.path?.value == "demo/Main.kt" })

            val offset = main.lastIndexOf("\"ok\"") + 1
            val expression =
                assertIs<AnalysisClientResult.Success>(
                    controller
                        .query(
                            admitted,
                            AnalysisQuery.ExpressionInfo(
                                admitted.identity,
                                VirtualSourcePath.kotlin("demo/Main.kt"),
                                offset,
                            ),
                        ).get(90, TimeUnit.SECONDS),
                ).result as AnalysisResult.ExpressionInfo
            assertEquals("kotlin.String", expression.value?.renderedType)
        }
    }

    private fun withController(block: (AnalysisWorkerController) -> Unit) {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val temporaryRoot = createTempDirectory("compukters-analysis-integration-").toAbsolutePath().normalize()
        val limits = AnalysisLimits()
        val identity =
            AnalysisWorkerIdentity(
                payload.manifest.identityProperties.getValue("compiler"),
                payload.manifest.identityProperties.getValue("language"),
                Hash256.of(payload.manifest.payloadHash.toByteArray()),
                ru.lazyhat.compukters.ide.analysis.k2
                    .testPlatformAbi(),
            )
        val controller =
            AnalysisWorkerController(
                WorkerLaunch(
                    Path.of(checkNotNull(System.getProperty("compukters.analysis.java"))).toAbsolutePath().normalize(),
                    payload.classpath,
                    payload.manifest.mainClass,
                    512,
                    256,
                    temporaryRoot.resolve("worker"),
                    limits.frameBytes + 12,
                    64 * 1024,
                ),
                identity,
                limits,
                JdkWorkerProcessFactory(),
                AnalysisWorkerPolicy(30_000_000_000, 60_000_000_000, 250),
            )
        try {
            block(controller)
        } finally {
            controller.close()
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun withRawWorker(
        limits: AnalysisLimits,
        block: (WorkerProcess) -> Unit,
    ) {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val temporaryRoot = createTempDirectory("compukters-analysis-raw-").toAbsolutePath().normalize()
        val worker =
            JdkWorkerProcessFactory().start(
                WorkerLaunch(
                    Path.of(checkNotNull(System.getProperty("compukters.analysis.java"))).toAbsolutePath().normalize(),
                    payload.classpath,
                    payload.manifest.mainClass,
                    512,
                    256,
                    temporaryRoot.resolve("worker"),
                    limits.frameBytes + 12,
                    64 * 1024,
                ),
            )
        try {
            block(worker)
        } finally {
            worker.close()
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun snapshot(
        profileByte: Int,
        main: ByteArray = "package demo\nfun answer() = helper()".encodeToByteArray(),
        platform: AdmittedAnalysisPlatform = testAdmittedPlatform(),
    ): AdmittedAnalysisSnapshot {
        val sources =
            ProjectSnapshot.of(
                listOf(
                    ProjectSource(
                        VirtualSourcePath.kotlin("demo/Helper.kt"),
                        BinaryValue.of("package demo\nfun helper() = 42".encodeToByteArray()),
                    ),
                    ProjectSource(VirtualSourcePath.kotlin("demo/Main.kt"), BinaryValue.of(main)),
                ),
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { profileByte.toByte() }))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        return AdmittedAnalysisSnapshot(identity, sources, AdmittedAnalysisProfile(profile, platform), AnalysisLimits())
    }
}

private fun send(
    worker: WorkerProcess,
    message: AnalysisMessage,
    context: AnalysisProtocolContext,
) {
    worker.writeFrame(AnalysisFrameCodec.encode(AnalysisMessageCodec.encode(message, context)))
}

private fun receive(
    worker: WorkerProcess,
    limits: AnalysisLimits,
    context: AnalysisProtocolContext,
): AnalysisMessage {
    val frame = checkNotNull(worker.readFrame(System.nanoTime() + TimeUnit.SECONDS.toNanos(90)))
    return AnalysisMessageCodec.decode(AnalysisFrameCodec.decode(frame, limits.frameBytes), context)
}
