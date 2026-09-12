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

package ru.lazyhat.compukters.impl.ide.target

import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeFileListResult
import ru.lazyhat.compukters.ide.client.target.IdeFileReadResult
import ru.lazyhat.compukters.ide.client.target.IdeFileStatResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryEntry
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryListing
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileChunk
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileKind
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileMetadata
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileStat
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.VmFileKind
import ru.lazyhat.compukters.lang.runtime.fs.VmFileMetadata
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadException
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import java.util.UUID

class IdeTargetFileSystemService(
    private val leases: IdeTargetLeaseService,
) {
    suspend fun stat(
        player: UUID,
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        tick: Long,
    ): IdeFileStatResult =
        inspect(player, target, tick, { IdeFileStatResult.Failed(it) }) { operations ->
            val value = operations.stat(VmVirtualPath.of(path.value)) ?: return@inspect unavailableStat()
            IdeFileStatResult.Observed(IdeTargetFileStat(value.fileSystemGeneration, value.metadata.toIde()))
        }

    suspend fun list(
        player: UUID,
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
        tick: Long,
    ): IdeFileListResult =
        inspect(player, target, tick, { IdeFileListResult.Failed(it) }) { operations ->
            val value =
                operations.list(VmVirtualPath.of(path.value), startAfter, maximumEntries)
                    ?: return@inspect unavailableList()
            IdeFileListResult.Listed(
                IdeTargetDirectoryListing(
                    value.fileSystemGeneration,
                    value.directoryGeneration,
                    value.complete,
                    value.entries.map { IdeTargetDirectoryEntry(it.name, it.metadata.toIde()) },
                ),
            )
        }

    suspend fun read(
        player: UUID,
        target: IdeAttachedTarget,
        path: IdeTargetVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
        tick: Long,
    ): IdeFileReadResult =
        inspect(player, target, tick, { IdeFileReadResult.Failed(it) }) { operations ->
            val value =
                operations.read(VmVirtualPath.of(path.value), offset, maximumBytes, expectedGeneration)
                    ?: return@inspect unavailableRead()
            IdeFileReadResult.Read(IdeTargetFileChunk(value.generation, value.nextOffset, value.eof, value.bytes))
        }

    private suspend inline fun <T> inspect(
        player: UUID,
        target: IdeAttachedTarget,
        tick: Long,
        failed: (IdeTargetFailure) -> T,
        operation: suspend (IdeTargetFileSystemOperations) -> T,
    ): T {
        val resolved = leases.access(player, target, tick) ?: return failed(targetLost())
        if (!target.capabilities.readableFileSystem) return failed(unsupported())
        val operations = resolved.fileSystem ?: return failed(unsupported())
        return try {
            operation(operations)
        } catch (error: VmFileSystemReadException) {
            failed(error.toFailure())
        } catch (_: IllegalArgumentException) {
            failed(IdeTargetFailure(IdeTargetFailureKind.Protocol, "Invalid target filesystem request"))
        }
    }

    private fun VmFileMetadata.toIde() =
        IdeTargetFileMetadata(
            if (kind == VmFileKind.FILE) IdeTargetFileKind.File else IdeTargetFileKind.Directory,
            logicalBytes,
            generation,
            executable,
        )

    private fun VmFileSystemReadException.toFailure() =
        IdeTargetFailure(
            when (failure) {
                VmFileSystemReadFailure.INVALID_PATH -> IdeTargetFailureKind.Protocol

                VmFileSystemReadFailure.PERMISSION -> IdeTargetFailureKind.Permission

                VmFileSystemReadFailure.NOT_FOUND,
                VmFileSystemReadFailure.NOT_DIRECTORY,
                VmFileSystemReadFailure.NOT_FILE,
                VmFileSystemReadFailure.STALE_GENERATION,
                VmFileSystemReadFailure.LIMIT,
                VmFileSystemReadFailure.STORAGE,
                -> IdeTargetFailureKind.FileSystem
            },
            "Target filesystem ${failure.name.lowercase().replace('_', ' ')}",
        )

    private fun unavailableStat() = IdeFileStatResult.Failed(unavailable())

    private fun unavailableList() = IdeFileListResult.Failed(unavailable())

    private fun unavailableRead() = IdeFileReadResult.Failed(unavailable())

    private fun unavailable() = IdeTargetFailure(IdeTargetFailureKind.TargetLost, "Target VM is unavailable")

    private fun unsupported() = IdeTargetFailure(IdeTargetFailureKind.Unsupported, "Target filesystem is unavailable")

    private fun targetLost() = IdeTargetFailure(IdeTargetFailureKind.TargetLost, "Target lease is stale or unavailable")
}
