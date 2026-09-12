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
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectImportEntry
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeComputerTransferTest {
    @Test
    fun `file is published only after download and validation`() {
        val access = TransferAccess()
        val coordinator = IdeComputerFileCoordinator(access)
        coordinator.attach(transferTarget())

        assertTrue(coordinator.drop(transferPath("/home/a.kt"), ProjectPath.file("src")))
        assertFalse(coordinator.drop(transferPath("/home/b.kt"), ProjectPath.file("src")))
        access.completeStat("/home/a.kt", transferFile(7, 3), fileSystemGeneration = 20)
        coordinator.tick()
        assertIs<IdeComputerTransferState.Downloading>(coordinator.transfer())
        access.completeRead("/home/a.kt", byteArrayOf(1, 2, 3), generation = 7, eof = true)
        coordinator.tick()
        assertIs<IdeComputerTransferState.Downloading>(coordinator.transfer())
        access.completeStat("/home/a.kt", transferFile(7, 3), fileSystemGeneration = 20)
        coordinator.tick()

        val ready = assertIs<IdeComputerTransferState.ConfirmationRequired>(coordinator.transfer())
        assertEquals(ProjectPath.file("src/a.kt"), ready.import.destination)
        val imported = assertIs<ProjectImportEntry.File>(ready.import.entries.single())
        assertContentEquals(byteArrayOf(1, 2, 3), imported.bytes())
    }

    @Test
    fun `directory traversal is breadth first and import entries are parent first`() {
        val access = TransferAccess()
        val coordinator = IdeComputerFileCoordinator(access)
        coordinator.attach(transferTarget())
        coordinator.drop(transferPath("/home/lib"), ProjectPath.file("src"))
        access.completeStat("/home/lib", transferDirectory(1), 10)
        coordinator.tick()
        access.completeList("/home/lib", 10, 1, transferEntry("a", transferDirectory(2)), transferEntry("z.kt", transferFile(3, 1)))
        coordinator.tick()
        access.completeList("/home/lib/a", 10, 2, transferEntry("b.kt", transferFile(4, 1)))
        coordinator.tick()
        access.completeRead("/home/lib/z.kt", byteArrayOf(9), 3, true)
        coordinator.tick()
        access.completeRead("/home/lib/a/b.kt", byteArrayOf(8), 4, true)
        coordinator.tick()
        listOf(
            "/home/lib" to transferDirectory(1),
            "/home/lib/a" to transferDirectory(2),
            "/home/lib/z.kt" to transferFile(3, 1),
            "/home/lib/a/b.kt" to transferFile(4, 1),
        ).forEach { (path, metadata) ->
            access.completeStat(path, metadata, 10)
            coordinator.tick()
        }

        val ready = assertIs<IdeComputerTransferState.ConfirmationRequired>(coordinator.transfer())
        assertEquals(listOf("lib", "lib/a", "lib/a/b.kt", "lib/z.kt"), ready.import.entries.map { it.relativePath })
    }

    @Test
    fun `limits stale validation cancellation and target loss never publish an import`() {
        val oversized = TransferAccess()
        val first = IdeComputerFileCoordinator(oversized)
        first.attach(transferTarget())
        first.drop(transferPath("/huge"), ProjectPath.file("src"))
        oversized.completeStat("/huge", transferFile(1, 1024L * 1024L + 1), 1)
        first.tick()
        assertIs<IdeComputerTransferState.Failed>(first.transfer())

        val stale = TransferAccess()
        val second = IdeComputerFileCoordinator(stale)
        second.attach(transferTarget())
        second.drop(transferPath("/a"), ProjectPath.file("src"))
        stale.completeStat("/a", transferFile(1, 1), 2)
        second.tick()
        stale.completeRead("/a", byteArrayOf(1), 1, true)
        second.tick()
        stale.completeStat("/a", transferFile(2, 1), 3)
        second.tick()
        assertIs<IdeComputerTransferState.Failed>(second.transfer())

        val cancelled = TransferAccess()
        val third = IdeComputerFileCoordinator(cancelled)
        third.attach(transferTarget())
        third.drop(transferPath("/a"), ProjectPath.file("src"))
        third.cancelTransfer()
        assertEquals(IdeComputerTransferState.Idle, third.transfer())
        cancelled.completeStat("/a", transferFile(1, 1), 1)
        third.tick()
        assertEquals(IdeComputerTransferState.Idle, third.transfer())

        third.drop(transferPath("/a"), ProjectPath.file("src"))
        third.targetLost("gone")
        assertIs<IdeComputerTransferState.Failed>(third.transfer())
    }

    @Test
    fun `aggregate and generation boundaries abort the whole transfer`() {
        val aggregate = TransferAccess()
        val first = IdeComputerFileCoordinator(aggregate)
        first.attach(transferTarget())
        first.drop(transferPath("/many"), ProjectPath.file("src"))
        aggregate.completeStat("/many", transferDirectory(1), 9)
        first.tick()
        aggregate.completeList(
            "/many",
            9,
            1,
            *(0..8).map { transferEntry("$it.bin", transferFile((it + 2).toLong(), 1024L * 1024L)) }.toTypedArray(),
        )
        first.tick()
        assertIs<IdeComputerTransferState.Failed>(first.transfer())

        val staleListing = TransferAccess()
        val second = IdeComputerFileCoordinator(staleListing)
        second.attach(transferTarget())
        second.drop(transferPath("/dir"), ProjectPath.file("src"))
        staleListing.completeStat("/dir", transferDirectory(1), 4)
        second.tick()
        staleListing.completeList("/dir", 5, 1)
        second.tick()
        assertIs<IdeComputerTransferState.Failed>(second.transfer())

        val staleChunk = TransferAccess()
        val third = IdeComputerFileCoordinator(staleChunk)
        third.attach(transferTarget())
        third.drop(transferPath("/a"), ProjectPath.file("src"))
        staleChunk.completeStat("/a", transferFile(1, 1), 4)
        third.tick()
        staleChunk.completeRead("/a", byteArrayOf(1), 2, true)
        third.tick()
        assertIs<IdeComputerTransferState.Failed>(third.transfer())
    }

    @Test
    fun `node limit is enforced across paginated directories`() {
        val access = TransferAccess()
        val coordinator = IdeComputerFileCoordinator(access)
        coordinator.attach(transferTarget())
        coordinator.drop(transferPath("/wide"), ProjectPath.file("src"))
        access.completeStat("/wide", transferDirectory(1), 1)
        coordinator.tick()

        repeat(4) { page ->
            val entries =
                (page * 256 until (page + 1) * 256).map { index ->
                    transferEntry(index.toString().padStart(4, '0'), transferFile((index + 2).toLong(), 0))
                }
            access.completeList("/wide", 1, 1, *entries.toTypedArray(), complete = page == 3)
            coordinator.tick()
            if (page < 3) assertIs<IdeComputerTransferState.Downloading>(coordinator.transfer())
        }
        assertIs<IdeComputerTransferState.Failed>(coordinator.transfer())
    }
}

private class TransferAccess : IdeComputerFileAccess {
    private val stats = mutableListOf<Pair<IdeTargetVirtualPath, CompletableFuture<IdeFileStatResult>>>()
    private val lists = mutableListOf<Triple<IdeTargetVirtualPath, String?, CompletableFuture<IdeFileListResult>>>()
    private val reads = mutableListOf<Pair<IdeTargetVirtualPath, CompletableFuture<IdeFileReadResult>>>()

    override fun stat(path: IdeTargetVirtualPath) = CompletableFuture<IdeFileStatResult>().also { stats += path to it }

    override fun list(
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ) = CompletableFuture<IdeFileListResult>().also { lists += Triple(path, startAfter, it) }

    override fun read(
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ) = CompletableFuture<IdeFileReadResult>().also { reads += path to it }

    fun completeStat(
        path: String,
        metadata: IdeTargetFileMetadata,
        fileSystemGeneration: Long,
    ) {
        stats.last { it.first.value == path && !it.second.isDone }.second.complete(
            IdeFileStatResult.Observed(IdeTargetFileStat(fileSystemGeneration, metadata)),
        )
    }

    fun completeList(
        path: String,
        fileSystemGeneration: Long,
        directoryGeneration: Long,
        vararg entries: IdeTargetDirectoryEntry,
        complete: Boolean = true,
    ) {
        lists.last { it.first.value == path && !it.third.isDone }.third.complete(
            IdeFileListResult.Listed(IdeTargetDirectoryListing(fileSystemGeneration, directoryGeneration, complete, entries.toList())),
        )
    }

    fun completeRead(
        path: String,
        bytes: ByteArray,
        generation: Long,
        eof: Boolean,
    ) {
        reads.last { it.first.value == path && !it.second.isDone }.second.complete(
            IdeFileReadResult.Read(IdeTargetFileChunk(generation, bytes.size.toLong(), eof, bytes)),
        )
    }
}

private fun transferEntry(
    name: String,
    metadata: IdeTargetFileMetadata,
) = IdeTargetDirectoryEntry(name, metadata)

private fun transferPath(value: String) = IdeTargetVirtualPath.of(value)

private fun transferDirectory(generation: Long) = IdeTargetFileMetadata(IdeTargetFileKind.Directory, 0, generation, false)

private fun transferFile(
    generation: Long,
    bytes: Long,
) = IdeTargetFileMetadata(IdeTargetFileKind.File, bytes, generation, false)

private fun transferTarget() =
    IdeAttachedTarget(
        IdeTargetId("transfer-computer"),
        IdeTargetProfileId(Hash256.of(ByteArray(32) { 1 })),
        TargetCompileProfile(
            ToolchainLockIdentity(
                "2.4.10",
                "2.4",
                1u,
                1u,
                1u,
                Hash256.of(ByteArray(32) { 3 }),
                Hash256.of(ByteArray(32) { 4 }),
            ),
            emptyList(),
            WorkerLimits(),
        ),
        IdeTargetCapabilities(true, true, true, true),
        "Computer",
    )
