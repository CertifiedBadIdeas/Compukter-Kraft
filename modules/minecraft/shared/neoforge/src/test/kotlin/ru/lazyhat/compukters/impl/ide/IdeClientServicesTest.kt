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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisPlatform
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyKind
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyTrace
import ru.lazyhat.compukters.ide.client.controller.IdeClientTooling
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReply
import ru.lazyhat.compukters.impl.ide.target.IdeTargetRequest
import ru.lazyhat.compukters.impl.ide.target.IdeTargetRequestChannel
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalClient
import ru.lazyhat.compukters.impl.ide.target.NetworkIdeTargetPort
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class IdeClientServicesTest {
    @Test
    fun `attached source loader admits exact Unicode Kotlin text`() {
        val archive = createTempDirectory("compukters-attached-source-").resolve("sources.jar")
        val text = "package compukter.api\nclass Пример"
        ZipOutputStream(
            java.nio.file.Files
                .newOutputStream(archive),
        ).use { output ->
            output.putNextEntry(ZipEntry("compukter/api/Sample.kt"))
            output.write(text.encodeToByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("compukter/api/ignored.class"))
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
        }
        val identity = AnalysisModuleIdentity("std.core", Hash256.of(ByteArray(32) { 7 }))

        val catalog =
            ProductionIdeApplicationFactory.loadAttachedSources(
                mapOf(identity to setOf("compukter/api/Sample.kt")),
                archive,
                AnalysisLimits(sourceFiles = 2, sourceFileBytes = 128, sourceBytes = 128, modules = 1),
            )

        assertEquals(text, catalog.text(identity, VirtualSourcePath.kotlin("compukter/api/Sample.kt")))
        archive.parent.toFile().deleteRecursively()
    }

    @Test
    fun `production analysis timing favors completion without starving presentation`() {
        assertEquals(150_000_000L, ProductionIdeApplicationFactory.analysisTiming.presentationDebounceNanos)
        assertEquals(75_000_000L, ProductionIdeApplicationFactory.analysisTiming.automaticCompletionDebounceNanos)
        assertEquals(400_000_000L, ProductionIdeApplicationFactory.analysisTiming.hoverDebounceNanos)
    }

    @Test
    fun `production application retains the injected visible latency trace`() {
        val gameRoot = createTempDirectory("compukters-ide-visible-latency-").toAbsolutePath().normalize()
        val trace = RecordingVisibleLatencyTrace()
        val pendingTooling = CompletableFuture<IdeClientTooling>()
        var application: IdeClientApplication? = null
        try {
            application =
                ProductionIdeApplicationFactory.open(IdeClientPaths.at(gameRoot), TestTargetTransport, TestLayoutStore, trace) { _ ->
                    pendingTooling
                }

            assertSame(trace, application.visibleLatency)
        } finally {
            application?.close()
            pendingTooling.cancel(true)
            gameRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `prepares distinct compiler and analysis workers from one shared bundle`() {
        val gameRoot = createTempDirectory("compukters-ide-tooling-").toAbsolutePath().normalize()
        try {
            val paths = IdeClientPaths.at(gameRoot)
            val prepared = ProductionIdeApplicationFactory.prepare(paths)
            val compiler = prepared.compilerPayload
            val analysis = prepared.analysisPayload
            val guestApi = prepared.platformSourceRoot
            val platform = prepared.platform

            assertEquals(compiler.root, analysis.root)
            assertNotEquals(compiler.classpath, analysis.classpath)
            assertNotEquals(compiler.manifest.mainClass, analysis.manifest.mainClass)
            assertTrue(
                Path
                    .of(guestApi.toString())
                    .fileName
                    .toString()
                    .startsWith("guest-platform-"),
            )
            assertTrue(
                java.nio.file.Files
                    .isRegularFile(guestApi),
            )
            assertEquals(compiler.manifest.identity.languageVersion, platform.identity.languageVersion)
            assertEquals(
                compiler.manifest.identity.platformAbi,
                Hash256.of(platform.identity.contentHash.toByteArray()),
            )
            val builtinsIdentity =
                AnalysisModuleIdentity(
                    platform.builtins.id.toString(),
                    Hash256.of(PlatformBundleCodec.moduleContentHash(platform.builtins).toByteArray()),
                )
            assertTrue(
                prepared.attachedSources
                    .text(
                        builtinsIdentity,
                        VirtualSourcePath.kotlin("compukters-platform/sources/builtins/kotlin/Arrays.kt"),
                    )?.contains("intArrayOf(vararg elements: Int)") == true,
            )

            val modulesById = platform.modules.associateBy { it.id.toString() }
            val admittedModules =
                ProductionIdeApplicationFactory.admittedAnalysisModules(
                    platform,
                    listOf(
                        modulesById.getValue("stdlib:core"),
                        modulesById.getValue("stdlib:ranges"),
                        modulesById.getValue("compukter:redstone"),
                        modulesById.getValue("std:terminal"),
                    ),
                )
            assertEquals(
                listOf("compukter:redstone", "kotlin:builtins", "std:terminal", "stdlib:core", "stdlib:ranges"),
                admittedModules.map { it.identity.name },
            )
            AdmittedAnalysisPlatform(compiler.manifest.identity.platformAbi, admittedModules, guestApi.toString())

            val mismatch = compiler.manifest.identity.copy(platformAbi = Hash256.zero())
            assertFailsWith<IllegalStateException> {
                ProductionIdeApplicationFactory.admitPlatform(platform, mismatch)
            }

            val compilerLaunch = ProductionIdeApplicationFactory.compilerLaunch(paths, prepared)
            val analysisLaunch = ProductionIdeApplicationFactory.analysisLaunch(paths, prepared)
            assertEquals(paths.compilerTemporary, compilerLaunch.temporaryDirectory)
            assertEquals(paths.analysisTemporary, analysisLaunch.temporaryDirectory)
            assertNotEquals(compilerLaunch.maximumHeapMiB, analysisLaunch.maximumHeapMiB)
            assertNotEquals(compilerLaunch.maximumMetaspaceMiB, analysisLaunch.maximumMetaspaceMiB)
            assertEquals(compiler.classpath, compilerLaunch.processLaunch(compiler).classpath)
            assertEquals(analysis.classpath, analysisLaunch.classpath)
            assertNotEquals(compilerLaunch.processLaunch(compiler).mainClass, analysisLaunch.mainClass)
        } finally {
            gameRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `uses distinct bounded client roots and opens only one session`() {
        val gameRoot = createTempDirectory("compukters-ide-services-").toAbsolutePath().normalize()
        val opened = mutableListOf<RecordingApplication>()
        val lifetime = RecordingLifetime()
        try {
            val services = IdeClientServices(gameRoot, lifetime) { paths -> RecordingApplication(paths).also(opened::add) }
            val expected = gameRoot.resolve("compukters/ide")
            assertEquals(expected.resolve("projects"), services.paths.projects)
            assertEquals(expected.resolve("cache/compiler"), services.paths.compilerCache)
            assertEquals(expected.resolve("workers/tooling"), services.paths.toolingWorkers)
            assertEquals(expected.resolve("tmp/compiler"), services.paths.compilerTemporary)
            assertEquals(expected.resolve("tmp/analysis"), services.paths.analysisTemporary)
            assertEquals(expected.resolve("session.preferences"), services.paths.preferences)

            val first = services.open()
            assertEquals(services.paths, first.application.paths)
            assertFailsWith<IllegalStateException> { services.open() }
            first.close()
            assertTrue(opened.single().closed)

            val second = services.open()
            assertEquals(2, opened.size)
            assertFalse(opened.last().closed)
            services.close()
            assertTrue(opened.last().closed)
            assertTrue(lifetime.closed)
            second.close()
        } finally {
            gameRoot.toFile().deleteRecursively()
        }
    }
}

private class RecordingVisibleLatencyTrace : IdeVisibleLatencyTrace {
    override fun editApplied(documentRevision: Long) = Unit

    override fun automaticCompletionExpected(documentRevision: Long) = Unit

    override fun analysisPublished(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) = Unit

    override fun controllerObserved(documentRevision: Long) = Unit

    override fun frameExtracted(
        documentRevision: Long,
        presentationVisible: Boolean,
        completionVisible: Boolean,
    ) = Unit

    override fun resultUnavailable(
        kind: IdeVisibleLatencyKind,
        documentRevision: Long,
    ) = Unit

    override fun dropActive() = Unit
}

private class RecordingLifetime : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
    }
}

private class RecordingApplication(
    val paths: IdeClientPaths,
) : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
    }
}

internal object TestTargetTransport : IdeClientTargetTransport {
    override fun openPort(): NetworkIdeTargetPort =
        NetworkIdeTargetPort(
            object : IdeTargetRequestChannel {
                override fun request(request: IdeTargetRequest): CompletableFuture<IdeTargetReply> = CompletableFuture()

                override fun disconnect() = Unit
            },
        )

    override fun openTerminal(): IdeTargetTerminalClient = IdeTargetTerminalClient {}

    override fun release(terminal: IdeTargetTerminalClient) = terminal.close()
}

internal object TestLayoutStore : IdeLayoutStore {
    private var settings = IdeLayoutSettings.defaults()

    override fun load(): IdeLayoutSettings = settings

    override fun save(settings: IdeLayoutSettings) {
        this.settings = settings
    }
}
