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
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
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

class NavigationAndReferencesTest {
    @Test
    fun `forked worker navigates to attached builtin source`() {
        val source = "fun main() { val values = intArrayOf(7, 11) }"
        val path = VirtualSourcePath.kotlin("main.kt")
        val sources =
            ProjectSnapshot.of(
                listOf(ProjectSource(path, BinaryValue.of(source.encodeToByteArray()))),
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 9 }))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        val admitted =
            AdmittedAnalysisSnapshot(
                identity,
                sources,
                AdmittedAnalysisProfile(
                    profile,
                    ru.lazyhat.compukters.ide.analysis.k2
                        .testAdmittedPlatform(selectAllModules = true, attachedSources = true),
                ),
                AnalysisLimits(),
            )

        withController { controller ->
            assertEquals(SnapshotOpenResult.Opened(identity), controller.open(admitted).get(90, TimeUnit.SECONDS))
            val result =
                assertIs<AnalysisClientResult.Success>(
                    controller
                        .query(admitted, AnalysisQuery.Declaration(identity, path, source.indexOf("intArrayOf") + 1))
                        .get(90, TimeUnit.SECONDS),
                ).result as AnalysisResult.Declaration
            val location = assertIs<DeclarationLocation.Source>(result.locations.single())

            assertEquals(
                "kotlin:builtins",
                assertIs<DeclarationOrigin.Platform>(location.origin).identity.name,
            )
            assertEquals("compukters-platform/sources/builtins/kotlin/Arrays.kt", location.path.value)
        }
    }

    @Test
    fun `forked worker navigates and finds exact project references`() {
        val declaration = "package demo\nfun target() = Unit"
        val usage = "package demo\nfun first() = target()\nfun second() = target()"
        val sources =
            ProjectSnapshot.of(
                listOf(
                    ProjectSource(VirtualSourcePath.kotlin("demo/Declaration.kt"), BinaryValue.of(declaration.encodeToByteArray())),
                    ProjectSource(VirtualSourcePath.kotlin("demo/Usage.kt"), BinaryValue.of(usage.encodeToByteArray())),
                ),
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { 8 }))
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
            val declarationResult =
                assertIs<AnalysisClientResult.Success>(
                    controller
                        .query(
                            admitted,
                            AnalysisQuery.Declaration(
                                identity,
                                VirtualSourcePath.kotlin("demo/Usage.kt"),
                                usage.indexOf("target") + 1,
                            ),
                        ).get(90, TimeUnit.SECONDS),
                ).result as AnalysisResult.Declaration
            assertEquals("demo/Declaration.kt", assertIs<DeclarationLocation.Source>(declarationResult.locations.single()).path.value)

            val references =
                assertIs<AnalysisClientResult.Success>(
                    controller
                        .query(
                            admitted,
                            AnalysisQuery.References(
                                identity,
                                VirtualSourcePath.kotlin("demo/Declaration.kt"),
                                declaration.indexOf("target") + 1,
                            ),
                        ).get(90, TimeUnit.SECONDS),
                ).result as AnalysisResult.References
            assertEquals(2, references.locations.size)
            assertEquals(setOf("demo/Usage.kt"), references.locations.map { assertIs<DeclarationLocation.Source>(it).path.value }.toSet())
        }
    }

    private fun withController(block: (AnalysisWorkerController) -> Unit) {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val temporaryRoot = createTempDirectory("compukters-analysis-navigation-").toAbsolutePath().normalize()
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
