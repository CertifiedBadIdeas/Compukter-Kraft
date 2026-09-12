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

package ru.lazyhat.compukters.ide.client.controller

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.build.IdeBuildCoordinator
import ru.lazyhat.compukters.ide.client.build.IdeBuildServices
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTransferState
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorSource
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeployResult
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeFileListResult
import ru.lazyhat.compukters.ide.client.target.IdeFileReadResult
import ru.lazyhat.compukters.ide.client.target.IdeFileStatResult
import ru.lazyhat.compukters.ide.client.target.IdeHeartbeatResult
import ru.lazyhat.compukters.ide.client.target.IdeRevisionResult
import ru.lazyhat.compukters.ide.client.target.IdeSubmissionResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetArtifact
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetCoordinator
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryEntry
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryListing
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileChunk
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileStat
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetPort
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.client.target.IdeVerificationTicket
import ru.lazyhat.compukters.ide.client.target.IdeVerifyResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildResult
import ru.lazyhat.compukters.ide.compiler.ClientBuildSnapshot
import ru.lazyhat.compukters.ide.compiler.ClientCompilationService
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfileResolver
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ProjectLockCodec
import ru.lazyhat.compukters.ide.project.ProjectLockService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdeTargetFlowTest {
    @Test
    fun `computer preview is read only and closes with its target`() {
        val fixture = fixture()
        fixture.startAttached()

        fixture.controller.dispatch(IdeCommand.OpenComputerFile(IdeTargetVirtualPath.of("/home/hello.kt")))
        fixture.controller.tick()

        val preview = assertIs<IdeEditorView.Text>(fixture.workspaceView().editor)
        assertIs<IdeEditorSource.Computer>(preview.source)
        assertEquals("Computer · /home/hello.kt · Read-only", preview.title)
        assertEquals(true, preview.readOnly)
        assertEquals("fun main() = Unit", preview.visibleLines.single())

        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("x")))
        assertEquals("fun main() = Unit", assertIs<IdeEditorView.Text>(fixture.workspaceView().editor).visibleLines.single())
        assertEquals(0, fixture.workspace.saveRequests.size)

        fixture.controller.detachTarget()
        assertIs<IdeEditorSource.Project>(assertIs<IdeEditorView.Text>(fixture.workspaceView().editor).source)
    }

    @Test
    fun `verify builds against captured target profile and publishes target state`() {
        val fixture = fixture()
        fixture.startAttached()

        fixture.controller.dispatch(IdeCommand.Verify)
        val input = fixture.compilation.awaitInput { fixture.controller.tick() }
        assertEquals(TARGET_LIMITS, input.profile.limits)
        val compiling = assertIs<IdeBuildState.Compiling>(fixture.workspaceView().build)
        fixture.compilation.complete(
            ClientBuildResult.Success(compiling.identity, BinaryValue.of(byteArrayOf(7, 8)), hash(8), cacheHit = false),
        )
        fixture.tickUntil { fixture.port.verifications.size == 1 }
        assertContentEquals(
            byteArrayOf(7, 8),
            fixture.port.verifications
                .single()
                .artifact
                .bytes(),
        )
        fixture.port.verifications.single().future.complete(
            IdeVerifyResult.Verified(
                ticket(
                    fixture.port.verifications
                        .single()
                        .artifact,
                ),
            ),
        )
        fixture.controller.tick()

        assertIs<IdeTargetState.Verified>(fixture.controller.viewState().target)
    }

    @Test
    fun `run saves builds deploys manifest program and submits canonical line`() {
        val fixture = fixture()
        fixture.startAttached()
        fixture.controller.dispatch(IdeCommand.Edit(IdeEditorInput.Type("// dirty")))

        fixture.controller.dispatch(IdeCommand.Run)
        assertEquals(1, fixture.workspace.saveRequests.size)
        fixture.workspace.completeSave()
        fixture.controller.tick()
        val input = fixture.compilation.awaitInput { fixture.controller.tick() }
        val compiling = assertIs<IdeBuildState.Compiling>(fixture.workspaceView().build)
        fixture.compilation.complete(
            ClientBuildResult.Success(compiling.identity, BinaryValue.of(byteArrayOf(3)), hash(3), cacheHit = false),
        )
        fixture.tickUntil { fixture.port.verifications.size == 1 }
        val artifact =
            fixture.port.verifications
                .single()
                .artifact
        fixture.port.verifications
            .single()
            .future
            .complete(IdeVerifyResult.Verified(ticket(artifact)))
        fixture.controller.tick()
        fixture.port.revisions
            .single()
            .future
            .complete(IdeRevisionResult.Observed(IdeExecutableRevision.Absent))
        fixture.controller.tick()
        assertEquals(
            "/home/demo",
            fixture.port.deployments
                .single()
                .path.value,
        )
        fixture.port.deployments
            .single()
            .future
            .complete(IdeDeployResult.Deployed(IdeExecutableRevision.Present(1)))
        fixture.controller.tick()
        assertEquals(
            "/home/demo",
            fixture.port.submissions
                .single()
                .line
                .concatToString(),
        )
        fixture.port.submissions
            .single()
            .future
            .complete(IdeSubmissionResult.Submitted)
        fixture.controller.tick()

        assertIs<IdeTargetState.CommandSubmitted>(fixture.controller.viewState().target)
        input.sources.sources.single()
    }

    @Test
    fun `target loss during build preserves project and suppresses artifact delivery`() {
        val fixture = fixture()
        fixture.startAttached()
        fixture.controller.dispatch(IdeCommand.Deploy)
        fixture.compilation.awaitInput { fixture.controller.tick() }
        val compiling = assertIs<IdeBuildState.Compiling>(fixture.workspaceView().build)

        fixture.controller.detachTarget()
        fixture.compilation.complete(
            ClientBuildResult.Success(compiling.identity, BinaryValue.of(byteArrayOf(1)), hash(1), cacheHit = false),
        )
        fixture.tickUntil { fixture.workspaceView().build is IdeBuildState.Succeeded }

        assertEquals(IdeTargetState.LocalOnly, fixture.controller.viewState().target)
        assertEquals("demo", fixture.workspaceView().project.directoryName)
        assertEquals(0, fixture.port.verifications.size)
    }

    @Test
    fun `overwrite confirmation can be cancelled without losing attachment`() {
        val fixture = fixture()
        fixture.startAttached()
        fixture.controller.dispatch(IdeCommand.Deploy)
        fixture.compilation.awaitInput { fixture.controller.tick() }
        val compiling = assertIs<IdeBuildState.Compiling>(fixture.workspaceView().build)
        fixture.compilation.complete(
            ClientBuildResult.Success(compiling.identity, BinaryValue.of(byteArrayOf(1)), hash(1), cacheHit = false),
        )
        fixture.tickUntil { fixture.port.verifications.size == 1 }
        val artifact =
            fixture.port.verifications
                .single()
                .artifact
        fixture.port.verifications
            .single()
            .future
            .complete(IdeVerifyResult.Verified(ticket(artifact)))
        fixture.controller.tick()
        fixture.port.revisions.single().future.complete(
            IdeRevisionResult.Observed(IdeExecutableRevision.Present(2)),
        )
        fixture.controller.tick()
        assertIs<IdeDialogState.TargetOverwrite>(fixture.controller.viewState().dialog)

        fixture.controller.dispatch(IdeCommand.CancelTargetDeployment)

        assertEquals(null, fixture.controller.viewState().dialog)
        assertIs<IdeTargetState.Attached>(fixture.controller.viewState().target)
    }

    @Test
    fun `target file import is atomic and an existing destination requires confirmation`() {
        val fixture = fixture()
        fixture.startAttached()

        fixture.controller.dispatch(
            IdeCommand.DropComputerEntry(
                IdeTargetVirtualPath.of("/home/hello.kt"),
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src"),
            ),
        )
        fixture.tickUntil {
            fixture
                .workspaceView()
                .tree
                .flatten()
                .any { it.path.value == "src/hello.kt" }
        }
        assertEquals(IdeComputerTransferState.Idle, fixture.workspaceView().computerTransfer)
        assertContentEquals(
            "fun main() = Unit".encodeToByteArray(),
            fixture.workspace.descriptor.handle.canonicalPath
                .resolve("src/hello.kt")
                .toFile()
                .readBytes(),
        )

        fixture.controller.dispatch(
            IdeCommand.DropComputerEntry(
                IdeTargetVirtualPath.of("/home/hello.kt"),
                ru.lazyhat.compukters.ide.project.fs.ProjectPath
                    .file("src"),
            ),
        )
        fixture.tickUntil { fixture.controller.viewState().dialog is IdeDialogState.ComputerImport }
        assertIs<IdeComputerTransferState.ConfirmationRequired>(fixture.workspaceView().computerTransfer)
        fixture.controller.dispatch(IdeCommand.CancelComputerImport)
        assertEquals(IdeComputerTransferState.Idle, fixture.workspaceView().computerTransfer)
        assertEquals(null, fixture.controller.viewState().dialog)
    }

    private fun fixture(): TargetFixture {
        val port = TargetPort()
        val compilation = TargetCompilation()
        val fixture =
            ControllerFixture(
                preferences = preferences("demo", "src/main.kt"),
                buildCoordinatorFactory = { _, clock -> buildCoordinator(compilation, clock) },
                targetCoordinatorFactory = { clock -> IdeTargetCoordinator(port, clock) },
            )
        return TargetFixture(fixture, port, compilation)
    }
}

private class TargetFixture(
    private val fixture: ControllerFixture,
    val port: TargetPort,
    val compilation: TargetCompilation,
) {
    val controller get() = fixture.controller
    val workspace get() = fixture.workspace

    fun workspaceView() = fixture.workspaceView()

    fun startAttached() {
        fixture.workspace.installLock(ProjectLockCodec.encode(ProjectLock.of(toolchain(), emptyList())).encodeToByteArray())
        fixture.startAndTick()
        controller.attachTarget(IdeTargetClaim.of(byteArrayOf(1)))
        controller.tick()
        assertIs<IdeTargetState.Attached>(controller.viewState().target)
    }

    fun tickUntil(predicate: () -> Boolean) {
        repeat(500) {
            controller.tick()
            if (predicate()) return
            Thread.sleep(5)
        }
        error("condition was not reached")
    }
}

private class TargetCompilation : ClientCompilationService {
    private val submitted = LinkedBlockingQueue<ClientBuildSnapshot>()
    private val futures = ArrayDeque<CompletableFuture<ClientBuildResult>>()

    override fun build(input: ClientBuildSnapshot): CompletableFuture<ClientBuildResult> {
        submitted.add(input)
        return CompletableFuture<ClientBuildResult>().also(futures::addLast)
    }

    fun awaitInput(tick: () -> Unit): ClientBuildSnapshot {
        repeat(500) {
            tick()
            submitted.poll()?.let { return it }
            Thread.sleep(5)
        }
        error("compilation input was not submitted")
    }

    fun complete(result: ClientBuildResult) {
        futures.removeFirst().complete(result)
    }

    override fun cancel(future: CompletableFuture<ClientBuildResult>): Boolean = true

    override fun close() = Unit
}

private class TargetPort : IdeTargetPort {
    private val previewText = "fun main() = Unit".encodeToByteArray()
    val verifications = mutableListOf<Verification>()
    val revisions = mutableListOf<Revision>()
    val deployments = mutableListOf<Deployment>()
    val submissions = mutableListOf<Submission>()

    override fun attach(claim: IdeTargetClaim): CompletableFuture<IdeAttachResult> =
        CompletableFuture.completedFuture(IdeAttachResult.Attached(target()))

    override fun verify(
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
    ) = CompletableFuture<IdeVerifyResult>().also { verifications += Verification(artifact, it) }

    override fun fileStat(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
    ): CompletableFuture<IdeFileStatResult> {
        val metadata =
            when (path.value) {
                "/" -> IdeTargetFileMetadata(IdeTargetFileKind.Directory, 0, 1, false)
                "/home/hello.kt" -> IdeTargetFileMetadata(IdeTargetFileKind.File, previewText.size.toLong(), 2, false)
                else -> IdeTargetFileMetadata(IdeTargetFileKind.Directory, 0, 3, false)
            }
        return CompletableFuture.completedFuture(IdeFileStatResult.Observed(IdeTargetFileStat(metadata.generation, metadata)))
    }

    override fun fileList(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): CompletableFuture<IdeFileListResult> {
        val entries =
            if (path.value == "/") {
                listOf(IdeTargetDirectoryEntry("home", IdeTargetFileMetadata(IdeTargetFileKind.Directory, 0, 3, false)))
            } else {
                listOf(
                    IdeTargetDirectoryEntry(
                        "hello.kt",
                        IdeTargetFileMetadata(IdeTargetFileKind.File, previewText.size.toLong(), 2, false),
                    ),
                )
            }
        val generation = if (path.value == "/") 1L else 3L
        return CompletableFuture.completedFuture(
            IdeFileListResult.Listed(IdeTargetDirectoryListing(generation, generation, true, entries)),
        )
    }

    override fun fileRead(
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): CompletableFuture<IdeFileReadResult> {
        val start = offset.toInt()
        val end = minOf(previewText.size, start + maximumBytes)
        return CompletableFuture.completedFuture(
            IdeFileReadResult.Read(
                IdeTargetFileChunk(expectedGeneration, end.toLong(), end == previewText.size, previewText.copyOfRange(start, end)),
            ),
        )
    }

    override fun executableRevision(
        target: IdeAttachedTarget,
        path: IdeDeploymentPath,
    ) = CompletableFuture<IdeRevisionResult>().also { revisions += Revision(path, it) }

    override fun deploy(
        target: IdeAttachedTarget,
        ticket: IdeVerificationTicket,
        path: IdeDeploymentPath,
        expected: IdeExecutableRevision,
    ) = CompletableFuture<IdeDeployResult>().also { deployments += Deployment(path, it) }

    override fun submitCanonicalLine(
        target: IdeAttachedTarget,
        line: CharArray,
    ) = CompletableFuture<IdeSubmissionResult>().also { submissions += Submission(line.copyOf(), it) }

    override fun heartbeat(target: IdeAttachedTarget) = CompletableFuture.completedFuture<IdeHeartbeatResult>(IdeHeartbeatResult.Alive)

    override fun detach(target: IdeAttachedTarget) = CompletableFuture.completedFuture(Unit)

    data class Verification(
        val artifact: IdeTargetArtifact,
        val future: CompletableFuture<IdeVerifyResult>,
    )

    data class Revision(
        val path: IdeDeploymentPath,
        val future: CompletableFuture<IdeRevisionResult>,
    )

    data class Deployment(
        val path: IdeDeploymentPath,
        val future: CompletableFuture<IdeDeployResult>,
    )

    data class Submission(
        val line: CharArray,
        val future: CompletableFuture<IdeSubmissionResult>,
    )
}

private fun buildCoordinator(
    compilation: TargetCompilation,
    clock: IdeControllerClock,
): IdeBuildCoordinator =
    IdeBuildCoordinator(
        IdeBuildServices(
            TEST_PROJECT_RESOLUTION,
            CompileProfileResolver(toolchain(), TEST_PLATFORM_CATALOG, LOCAL_LIMITS),
            { project -> ProjectLockService(project.lockFileWriter()) },
            compilation,
        ),
        clock,
    )

private fun target() =
    IdeAttachedTarget(
        IdeTargetId("computer-1"),
        IdeTargetProfileId(hash(7)),
        TargetCompileProfile(toolchain(), emptyList(), TARGET_LIMITS),
        IdeTargetCapabilities(
            writableFileSystem = true,
            canonicalInput = true,
            terminal = false,
            readableFileSystem = true,
        ),
        "Computer",
    )

private fun ticket(artifact: IdeTargetArtifact) = IdeVerificationTicket.of(byteArrayOf(4), target(), artifact)

private fun toolchain() = TEST_TOOLCHAIN

private fun hash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })

private val LOCAL_LIMITS = WorkerLimits(sourceFiles = 8, sourceFileBytes = 4096, sourceBytes = 8192)
private val TARGET_LIMITS = LOCAL_LIMITS.copy(artifactBytes = LOCAL_LIMITS.artifactBytes + 1)
