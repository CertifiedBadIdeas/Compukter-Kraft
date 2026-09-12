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
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerPolicy
import ru.lazyhat.compukters.ide.analysis.controller.SnapshotOpenResult
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeScreenAnalysisIntegrationTest {
    @Test
    fun `same admitted snapshot provides presentation and applicable completion`() {
        val source = "fun candidate() = Unit\nfun main() { can }"
        val path = VirtualSourcePath.kotlin("src/main.kt")
        val sources = ProjectSnapshot.of(listOf(ProjectSource(path, BinaryValue.of(source.encodeToByteArray()))), WorkerLimits())
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 17 }))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        val admitted =
            AdmittedAnalysisSnapshot(
                identity,
                sources,
                AdmittedAnalysisProfile(
                    profile,
                    ru.lazyhat.compukters.ide.analysis.k2
                        .testAdmittedPlatform(),
                ),
                AnalysisLimits(),
            )

        withController { controller ->
            assertEquals(SnapshotOpenResult.Opened(identity), controller.open(admitted).get(90, TimeUnit.SECONDS))
            val presentation =
                assertIs<AnalysisResult.Presentation>(
                    assertIs<AnalysisClientResult.Success>(
                        controller.query(admitted, AnalysisQuery.Presentation(identity, path)).get(90, TimeUnit.SECONDS),
                    ).result,
                )
            val active = assertIs<SnapshotPresentationAcceptance.Active>(presentation.value.accept(identity))
            assertTrue(active.semanticTokens.isNotEmpty())

            val completion =
                assertIs<AnalysisResult.Completion>(
                    assertIs<AnalysisClientResult.Success>(
                        controller
                            .query(
                                admitted,
                                AnalysisQuery.Completion(
                                    identity,
                                    path,
                                    source.lastIndexOf("can") + 3,
                                    CompletionTrigger.Automatic,
                                ),
                            ).get(90, TimeUnit.SECONDS),
                    ).result,
                )
            val candidate = completion.items.single { it.insertText == "candidate" }
            assertEquals("candidate()", candidate.label)
            val completed = source.replaceRange(completion.replacement.startUtf16, completion.replacement.endUtf16, candidate.insertText)
            assertTrue("candidate" in completed)
        }
    }

    private fun withController(block: (AnalysisWorkerController) -> Unit) {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val temporaryRoot = createTempDirectory("compukters-ide-screen-analysis-").toAbsolutePath().normalize()
        val limits = AnalysisLimits()
        val workerIdentity =
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
                workerIdentity,
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
}
