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

package ru.lazyhat.compukters.ide.client.files

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeFileListResult
import ru.lazyhat.compukters.ide.client.target.IdeFileReadResult
import ru.lazyhat.compukters.ide.client.target.IdeFileStatResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryEntry
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryListing
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileChunk
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileStat
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdeComputerFileCoordinatorTest {
    @Test
    fun `attach loads root and expansion is lazy`() {
        val access = ControlledAccess()
        val coordinator = IdeComputerFileCoordinator(access)

        coordinator.attach(target())
        assertEquals(IdeComputerTreeState.Loading, coordinator.state())
        access.completeStat("/", directory(1))
        coordinator.tick()
        access.completeList("/", listing(1, entry("home", directory(2)), entry("rom", directory(3))))
        coordinator.tick()

        val available = assertIs<IdeComputerTreeState.Available>(coordinator.state())
        assertEquals(setOf(path("/")), available.expanded)
        assertIs<IdeComputerChildren.Unloaded>(available.root.childDirectory("home").children)

        coordinator.expand(path("/home"))
        assertIs<IdeComputerChildren.Loading>(availableNow(coordinator).root.childDirectory("home").children)
        access.completeList("/home", listing(2, entry("hello", file(4))))
        coordinator.tick()
        assertEquals(
            "hello",
            availableNow(coordinator)
                .root
                .childDirectory("home")
                .loadedChildren()
                .single()
                .name,
        )
    }

    @Test
    fun `refresh preserves surviving expansions and discards stale completions`() {
        val access = ControlledAccess()
        val coordinator = IdeComputerFileCoordinator(access)
        loadRoot(coordinator, access)
        coordinator.expand(path("/home"))
        access.completeList("/home", listing(2, entry("old", file(4))))
        coordinator.tick()

        coordinator.refresh()
        access.completeStat("/", directory(10))
        coordinator.tick()
        access.completeList("/", listing(10, entry("home", directory(11))))
        coordinator.tick()
        assertEquals(listOf("/home"), access.pendingListPaths())
        access.completeList("/home", listing(11, entry("new", file(12))))
        coordinator.tick()

        assertEquals(
            "new",
            availableNow(coordinator)
                .root
                .childDirectory("home")
                .loadedChildren()
                .single()
                .name,
        )
        assertEquals(setOf(path("/"), path("/home")), availableNow(coordinator).expanded)

        coordinator.refresh()
        val staleStat = access.statFuture("/")
        coordinator.detach()
        staleStat.complete(IdeFileStatResult.Observed(IdeTargetFileStat(20, directory(20))))
        coordinator.tick()
        assertEquals(IdeComputerTreeState.NoTarget, coordinator.state())
    }

    @Test
    fun `filesystem-less target is unavailable without requests`() {
        val access = ControlledAccess()
        val coordinator = IdeComputerFileCoordinator(access)

        coordinator.attach(target(readable = false))

        assertIs<IdeComputerTreeState.Unavailable>(coordinator.state())
        assertEquals(0, access.statRequests.size)
    }

    @Test
    fun `invalid page order is rejected without publishing children`() {
        val access = ControlledAccess()
        val coordinator = IdeComputerFileCoordinator(access)
        coordinator.attach(target())
        access.completeStat("/", directory(1))
        coordinator.tick()
        access.completeList("/", listing(1, entry("z", file(2)), complete = false))
        coordinator.tick()
        access.completeList("/", listing(1, entry("a", file(3))))
        coordinator.tick()

        assertIs<IdeComputerTreeState.Unavailable>(coordinator.state())
    }

    @Test
    fun `preview assembles bounded chunks with one generation and strict utf8`() {
        val access = ControlledAccess()
        val coordinator = IdeComputerFileCoordinator(access)
        loadRoot(coordinator, access)

        coordinator.open(path("/home/hello.kt"))
        access.completeStat("/home/hello.kt", file(30, logicalBytes = 5))
        coordinator.tick()
        access.completeRead("/home/hello.kt", IdeTargetFileChunk(30, 2, false, "he".encodeToByteArray()))
        coordinator.tick()
        access.completeRead("/home/hello.kt", IdeTargetFileChunk(30, 5, true, "llo".encodeToByteArray()))
        coordinator.tick()

        val preview = assertIs<IdeComputerPreviewState.Available>(coordinator.preview())
        assertEquals("hello", preview.text)
        assertEquals(30, preview.generation)
        assertEquals(listOf(0L, 2L), access.readOffsets)

        coordinator.open(path("/home/bad"))
        access.completeStat("/home/bad", file(31, logicalBytes = 1))
        coordinator.tick()
        access.completeRead("/home/bad", IdeTargetFileChunk(31, 1, true, byteArrayOf(0x80.toByte())))
        coordinator.tick()
        assertIs<IdeComputerPreviewState.Failed>(coordinator.preview())
    }

    @Test
    fun `preview rejects oversized and stale files and closes on detach`() {
        val access = ControlledAccess()
        val coordinator = IdeComputerFileCoordinator(access)
        loadRoot(coordinator, access)

        coordinator.open(path("/huge"))
        access.completeStat("/huge", file(40, logicalBytes = 1024L * 1024L + 1))
        coordinator.tick()
        assertIs<IdeComputerPreviewState.TooLarge>(coordinator.preview())
        assertEquals(0, access.readRequests.size)

        coordinator.open(path("/stale"))
        access.completeStat("/stale", file(41, logicalBytes = 1))
        coordinator.tick()
        access.completeRead(
            "/stale",
            IdeFileReadResult.Failed(
                ru.lazyhat.compukters.ide.client.target.IdeTargetFailure(
                    ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind.FileSystem,
                    "stale generation",
                ),
            ),
        )
        coordinator.tick()
        assertEquals(
            "File changed; refresh and reopen",
            assertIs<IdeComputerPreviewState.Failed>(coordinator.preview()).detail,
        )

        coordinator.detach()
        assertEquals(IdeComputerPreviewState.Closed, coordinator.preview())
    }

    private fun loadRoot(
        coordinator: IdeComputerFileCoordinator,
        access: ControlledAccess,
    ) {
        coordinator.attach(target())
        access.completeStat("/", directory(1))
        coordinator.tick()
        access.completeList("/", listing(1, entry("home", directory(2)), entry("rom", directory(3))))
        coordinator.tick()
    }

    private fun availableNow(coordinator: IdeComputerFileCoordinator) = assertIs<IdeComputerTreeState.Available>(coordinator.state())

    private fun IdeComputerNode.Directory.childDirectory(name: String) =
        assertIs<IdeComputerNode.Directory>(loadedChildren().single { it.name == name })

    private fun IdeComputerNode.Directory.loadedChildren() = assertIs<IdeComputerChildren.Loaded>(children).nodes
}

private class ControlledAccess : IdeComputerFileAccess {
    val statRequests = mutableListOf<Pair<IdeTargetVirtualPath, CompletableFuture<IdeFileStatResult>>>()
    private val listRequests = mutableListOf<ListRequest>()
    val readRequests = mutableListOf<ReadRequest>()
    val readOffsets: List<Long> get() = readRequests.map { it.offset }

    override fun stat(path: IdeTargetVirtualPath): CompletableFuture<IdeFileStatResult> =
        CompletableFuture<IdeFileStatResult>().also { statRequests += path to it }

    override fun list(
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): CompletableFuture<IdeFileListResult> =
        CompletableFuture<IdeFileListResult>().also { listRequests += ListRequest(path, startAfter, maximumEntries, it) }

    override fun read(
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): CompletableFuture<IdeFileReadResult> =
        CompletableFuture<IdeFileReadResult>().also {
            readRequests += ReadRequest(path, offset, maximumBytes, expectedGeneration, it)
        }

    fun statFuture(value: String) = statRequests.last { it.first == path(value) }.second

    fun completeStat(
        value: String,
        metadata: IdeTargetFileMetadata,
    ) {
        statFuture(value).complete(IdeFileStatResult.Observed(IdeTargetFileStat(metadata.generation, metadata)))
    }

    fun completeList(
        value: String,
        listing: IdeTargetDirectoryListing,
    ) {
        listRequests.last { it.path == path(value) && !it.future.isDone }.future.complete(IdeFileListResult.Listed(listing))
    }

    fun pendingListPaths() = listRequests.filterNot { it.future.isDone }.map { it.path.value }

    fun completeRead(
        value: String,
        chunk: IdeTargetFileChunk,
    ) = completeRead(value, IdeFileReadResult.Read(chunk))

    fun completeRead(
        value: String,
        result: IdeFileReadResult,
    ) {
        readRequests.last { it.path == path(value) && !it.future.isDone }.future.complete(result)
    }

    private data class ListRequest(
        val path: IdeTargetVirtualPath,
        val startAfter: String?,
        val maximumEntries: Int,
        val future: CompletableFuture<IdeFileListResult>,
    )

    data class ReadRequest(
        val path: IdeTargetVirtualPath,
        val offset: Long,
        val maximumBytes: Int,
        val expectedGeneration: Long,
        val future: CompletableFuture<IdeFileReadResult>,
    )
}

private fun target(readable: Boolean = true) =
    IdeAttachedTarget(
        IdeTargetId("computer"),
        IdeTargetProfileId(Hash256.of(ByteArray(32) { 1 })),
        TargetCompileProfile(toolchain(), emptyList(), WorkerLimits()),
        IdeTargetCapabilities(writableFileSystem = true, canonicalInput = true, terminal = true, readableFileSystem = readable),
        "Computer",
    )

private fun path(value: String) = IdeTargetVirtualPath.of(value)

private fun directory(generation: Long) = IdeTargetFileMetadata(IdeTargetFileKind.Directory, 0, generation, false)

private fun file(
    generation: Long,
    logicalBytes: Long = 4,
) = IdeTargetFileMetadata(IdeTargetFileKind.File, logicalBytes, generation, false)

private fun entry(
    name: String,
    metadata: IdeTargetFileMetadata,
) = IdeTargetDirectoryEntry(name, metadata)

private fun listing(
    generation: Long,
    vararg entries: IdeTargetDirectoryEntry,
    complete: Boolean = true,
) = IdeTargetDirectoryListing(generation, generation, complete, entries.toList())

private fun toolchain() =
    ToolchainLockIdentity(
        "2.4.10",
        "2.4",
        1u,
        1u,
        1u,
        Hash256.of(ByteArray(32) { 3 }),
        Hash256.of(ByteArray(32) { 4 }),
    )
