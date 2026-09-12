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

package ru.lazyhat.compukters.ide.client.build

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.client.controller.IdeControllerClock
import ru.lazyhat.compukters.ide.client.controller.TEST_PLATFORM_CATALOG
import ru.lazyhat.compukters.ide.client.controller.TEST_PROJECT_RESOLUTION
import ru.lazyhat.compukters.ide.client.controller.TEST_TOOLCHAIN
import ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput
import ru.lazyhat.compukters.ide.compiler.ClientBuildResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildSnapshot
import ru.lazyhat.compukters.ide.compiler.ClientCompilationService
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectDependencyRollback
import ru.lazyhat.compukters.ide.project.ProjectDependencyUpdate
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ProjectLockCodec
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ProjectManifestCodec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeBuildCoordinatorTest {
    private val fixture = BuildFixture()

    @AfterTest
    fun close() = fixture.coordinator.close()

    @Test
    fun `resolve creates missing lock but requires confirmation before replacing existing lock`() {
        val created = fixture.coordinator.resolve(fixture.input(lock = null), updateExisting = false).get(5, TimeUnit.SECONDS)
        assertIs<IdeResolveResult.Created>(created)
        val canonical = fixture.lockPath.readBytes()

        val stale =
            ProjectLockCodec
                .encode(
                    ProjectLock.of(fixture.toolchain.copy(compilerVersion = "old"), emptyList()),
                ).encodeToByteArray()
        fixture.lockPath.writeBytes(stale)
        val confirmation = fixture.coordinator.resolve(fixture.input(lock = stale), updateExisting = false).get(5, TimeUnit.SECONDS)
        assertIs<IdeResolveResult.ConfirmationRequired>(confirmation)
        assertContentEquals(stale, fixture.lockPath.readBytes())

        val updated = fixture.coordinator.resolve(fixture.input(lock = stale), updateExisting = true).get(5, TimeUnit.SECONDS)
        assertIs<IdeResolveResult.Updated>(updated)
        assertContentEquals(canonical, fixture.lockPath.readBytes())
    }

    @Test
    fun `ordinary build requires lock and never writes one`() {
        val job = fixture.coordinator.build(1, fixture.input(lock = null))

        val failed = assertIs<IdeBuildState.Failed>(job.result.get(5, TimeUnit.SECONDS))
        assertEquals(IdeBuildFailureKind.MissingLock, failed.kind)
        assertFalse(fixture.lockPath.toFile().exists())
        assertTrue(fixture.compilation.inputs.isEmpty())

        val stale =
            ProjectLockCodec
                .encode(
                    ProjectLock.of(fixture.toolchain.copy(languageVersion = "old"), emptyList()),
                ).encodeToByteArray()
        fixture.lockPath.writeBytes(stale)
        val staleBuild =
            fixture.coordinator
                .build(2, fixture.input(stale))
                .result
                .get(5, TimeUnit.SECONDS)
        assertIs<IdeBuildState.Failed>(staleBuild)
        assertContentEquals(stale, fixture.lockPath.readBytes())
    }

    @Test
    fun `build publishes immutable target-ready artifact and canonical program name`() {
        val lock = fixture.canonicalLock()
        val job = fixture.coordinator.build(7, fixture.input(lock))
        val compiling = job.started.get(5, TimeUnit.SECONDS)
        assertEquals(7, compiling.operationId)
        val submitted = fixture.compilation.awaitInput()
        val artifact = byteArrayOf(1, 2, 3)
        fixture.compilation.complete(
            ClientBuildResult.Success(
                compiling.identity,
                BinaryValue.of(artifact),
                hash(9),
                cacheHit = true,
            ),
        )

        val success = assertIs<IdeBuildState.Succeeded>(job.result.get(5, TimeUnit.SECONDS))
        assertEquals(compiling.identity, success.identity)
        assertEquals(hash(9), success.artifactHash)
        assertEquals(artifact.size, success.bytes)
        assertContentEquals(artifact, success.artifact.bytes())
        success.artifact.bytes().fill(0)
        assertContentEquals(artifact, success.artifact.bytes())
        assertEquals("demo", success.programName)
        assertTrue(success.cacheHit)
        assertEquals(1234, success.completedAtMillis)
        assertEquals(listOf("src/main.kt"), submitted.sources.sources.map { it.path.value })
    }

    @Test
    fun `target profile is admitted before resolve or compilation`() {
        val mismatched =
            TargetCompileProfile(
                fixture.toolchain.copy(languageVersion = "old"),
                emptyList(),
                fixture.limits,
            )

        val resolve =
            fixture.coordinator
                .resolve(
                    fixture.input(lock = null),
                    updateExisting = false,
                    target = mismatched,
                ).get(5, TimeUnit.SECONDS)
        assertIs<IdeResolveResult.Failed>(resolve)
        assertFalse(fixture.lockPath.toFile().exists())

        val build = fixture.coordinator.build(8, fixture.input(fixture.canonicalLock()), target = mismatched)
        val failed = assertIs<IdeBuildState.Failed>(build.result.get(5, TimeUnit.SECONDS))
        assertEquals(IdeBuildFailureKind.UnsatisfiedProfile, failed.kind)
        assertTrue(fixture.compilation.inputs.isEmpty())

        val matching = TargetCompileProfile(fixture.toolchain, emptyList(), fixture.limits)
        val admitted = fixture.coordinator.build(9, fixture.input(fixture.canonicalLock()), target = matching)
        admitted.started.get(5, TimeUnit.SECONDS)
        val submitted = fixture.compilation.awaitInput()
        assertEquals(fixture.limits, submitted.profile.limits)
        admitted.cancel()
    }

    @Test
    fun `module enablement publishes and rolls back dependency files`() {
        val manifestPath =
            fixture.descriptor.handle.canonicalPath
                .resolve("compukter.toml")
        val manifestBefore = manifestPath.readBytes()
        val update =
            fixture.coordinator
                .enableModule(fixture.descriptor.handle, ModuleId.parse("compukter:redstone"), ApiMajor(1))
                .get(5, TimeUnit.SECONDS)

        val published = assertIs<ProjectDependencyUpdate.Published>(update)
        assertTrue(fixture.lockPath.toFile().exists())
        assertFalse(manifestBefore.contentEquals(manifestPath.readBytes()))

        val rollback =
            fixture.coordinator
                .rollbackModule(fixture.descriptor.handle, published.receipt)
                .get(5, TimeUnit.SECONDS)
        assertEquals(ProjectDependencyRollback.Restored, rollback)
        assertContentEquals(manifestBefore, manifestPath.readBytes())
        assertFalse(fixture.lockPath.toFile().exists())
    }

    @Test
    fun `module enablement validates proposed lock against target before publication`() {
        val manifestPath =
            fixture.descriptor.handle.canonicalPath
                .resolve("compukter.toml")
        val manifestBefore = manifestPath.readBytes()
        val targetWithoutModule = TargetCompileProfile(fixture.toolchain, emptyList(), fixture.limits)

        val update =
            fixture.coordinator
                .enableModule(
                    fixture.descriptor.handle,
                    ModuleId.parse("compukter:redstone"),
                    ApiMajor(1),
                    targetWithoutModule,
                ).get(5, TimeUnit.SECONDS)

        assertIs<ProjectDependencyUpdate.Conflict>(update)
        assertContentEquals(manifestBefore, manifestPath.readBytes())
        assertFalse(fixture.lockPath.toFile().exists())
    }

    @Test
    fun `compiler diagnostics are bounded DTOs tied to exact source snapshot`() {
        val job = fixture.coordinator.build(2, fixture.input(fixture.canonicalLock()))
        val compiling = job.started.get(5, TimeUnit.SECONDS)
        fixture.compilation.awaitInput()
        fixture.compilation.complete(
            ClientBuildResult.Diagnostics(
                compiling.identity,
                listOf(
                    WorkerDiagnostic(
                        DiagnosticSeverity.ERROR,
                        DiagnosticCategory.TYPE,
                        "TYPE",
                        "bad type",
                        VirtualSourcePath.kotlin("src/main.kt"),
                        0u,
                        3u,
                    ),
                ),
            ),
        )

        val diagnostics = assertIs<IdeBuildState.Diagnostics>(job.result.get(5, TimeUnit.SECONDS))
        assertEquals(compiling.sourceSnapshotId, diagnostics.sourceSnapshotId)
        assertEquals(EditorDiagnosticSeverity.Error, diagnostics.values.single().severity)
        assertEquals("bad type", diagnostics.values.single().message)
    }

    @Test
    fun `unsatisfied local profile fails before compiler and cancellation reaches active compilation`() {
        val mismatched =
            ProjectLockCodec
                .encode(ProjectLock.of(fixture.toolchain.copy(languageVersion = "1.0"), emptyList()))
                .encodeToByteArray()
        val failed =
            fixture.coordinator
                .build(3, fixture.input(mismatched))
                .result
                .get(5, TimeUnit.SECONDS)
        assertEquals(IdeBuildFailureKind.UnsatisfiedProfile, assertIs<IdeBuildState.Failed>(failed).kind)
        assertTrue(fixture.compilation.inputs.isEmpty())

        val active = fixture.coordinator.build(4, fixture.input(fixture.canonicalLock()))
        active.started.get(5, TimeUnit.SECONDS)
        fixture.compilation.awaitInput()
        assertTrue(active.cancel())
        assertEquals(1, fixture.compilation.cancelCalls)
    }
}

private class BuildFixture {
    val limits = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
    val toolchain = TEST_TOOLCHAIN
    val catalog = TEST_PLATFORM_CATALOG
    val descriptor: ProjectDescriptor = ProjectCatalog.open(createTempDirectory("compukters-build-")).create("demo")
    val lockPath = descriptor.handle.canonicalPath.resolve("compukter.lock")
    val compilation = ControlledCompilationService()
    val coordinator =
        IdeBuildCoordinator(
            IdeBuildServices(
                TEST_PROJECT_RESOLUTION,
                CompileProfileResolver(toolchain, catalog, limits),
                { project -> ProjectLockService(project.lockFileWriter()) },
                compilation,
            ),
            IdeControllerClock { 1234 },
        )

    fun canonicalLock(): ByteArray = ProjectLockCodec.encode(ProjectLock.of(toolchain, emptyList())).encodeToByteArray()

    fun input(lock: ByteArray?): IdeBuildInput =
        IdeBuildInput(
            descriptor.handle,
            ProjectManifestCodec.encode(descriptor.manifest).encodeToByteArray(),
            lock,
            ProjectSnapshot.of(
                listOf(ProjectSource(VirtualSourcePath.kotlin("src/main.kt"), BinaryValue.of("fun main() {}".encodeToByteArray()))),
                limits,
            ),
        )
}

private class ControlledCompilationService : ClientCompilationService {
    val inputs = mutableListOf<ClientBuildSnapshot>()
    private val submitted = LinkedBlockingQueue<ClientBuildSnapshot>()
    private val futures = ArrayDeque<CompletableFuture<ClientBuildResult>>()
    var cancelCalls = 0

    override fun build(input: ClientBuildSnapshot): CompletableFuture<ClientBuildResult> {
        synchronized(inputs) { inputs += input }
        submitted.add(input)
        return CompletableFuture<ClientBuildResult>().also(futures::addLast)
    }

    fun awaitInput(): ClientBuildSnapshot = requireNotNull(submitted.poll(5, TimeUnit.SECONDS))

    fun complete(result: ClientBuildResult) {
        futures.removeFirst().complete(result)
    }

    override fun cancel(future: CompletableFuture<ClientBuildResult>): Boolean {
        cancelCalls++
        return true
    }

    override fun close() = Unit
}

private fun hash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })
