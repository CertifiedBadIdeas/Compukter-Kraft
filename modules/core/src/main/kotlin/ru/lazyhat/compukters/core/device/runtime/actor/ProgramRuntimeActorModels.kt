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

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineFailure
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import java.util.Collections

@JvmInline
value class ProgramRuntimeRequestId(
    val value: Long,
) {
    init {
        require(value > 0) { "runtime request id must be positive" }
    }
}

@JvmInline
value class ProgramDeploymentToken(
    val value: Long,
) {
    init {
        require(value > 0) { "deployment token must be positive" }
    }
}

sealed interface ProgramRuntimeActorCommand {
    val requestId: ProgramRuntimeRequestId

    class Start(
        override val requestId: ProgramRuntimeRequestId,
        artifact: ByteArray,
    ) : ProgramRuntimeActorCommand {
        private val artifact = artifact.copyOf()

        internal fun artifactBytes(): ByteArray = artifact.copyOf()
    }

    data class StartBoot(
        override val requestId: ProgramRuntimeRequestId,
    ) : ProgramRuntimeActorCommand

    data class Advance(
        override val requestId: ProgramRuntimeRequestId,
        val worldTick: Long,
    ) : ProgramRuntimeActorCommand {
        init {
            require(worldTick >= 0) { "world tick must not be negative" }
        }
    }

    data class TerminalFullState(
        override val requestId: ProgramRuntimeRequestId,
    ) : ProgramRuntimeActorCommand

    data class TerminalChangesSince(
        override val requestId: ProgramRuntimeRequestId,
        val revision: Long,
    ) : ProgramRuntimeActorCommand {
        init {
            require(revision >= 0) { "terminal revision must not be negative" }
        }
    }

    class SendTerminalKey(
        override val requestId: ProgramRuntimeRequestId,
        val key: TerminalKey,
        val action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ) : ProgramRuntimeActorCommand {
        val modifiers: Set<TerminalModifier> = Collections.unmodifiableSet(modifiers.toSet())
    }

    data class SendTerminalText(
        override val requestId: ProgramRuntimeRequestId,
        val value: String,
    ) : ProgramRuntimeActorCommand

    data class FileSystemGeneration(
        override val requestId: ProgramRuntimeRequestId,
    ) : ProgramRuntimeActorCommand

    data class ResourceSnapshot(
        override val requestId: ProgramRuntimeRequestId,
    ) : ProgramRuntimeActorCommand

    data class FileStat(
        override val requestId: ProgramRuntimeRequestId,
        val path: VmVirtualPath,
    ) : ProgramRuntimeActorCommand

    data class FileList(
        override val requestId: ProgramRuntimeRequestId,
        val path: VmVirtualPath,
        val startAfter: String?,
        val maximumEntries: Int,
    ) : ProgramRuntimeActorCommand {
        init {
            require(maximumEntries > 0) { "maximum directory entries must be positive" }
        }
    }

    data class FileRead(
        override val requestId: ProgramRuntimeRequestId,
        val path: VmVirtualPath,
        val offset: Long,
        val maximumBytes: Int,
        val expectedGeneration: Long,
    ) : ProgramRuntimeActorCommand {
        init {
            require(offset >= 0) { "file offset must not be negative" }
            require(maximumBytes > 0) { "maximum file bytes must be positive" }
            require(expectedGeneration >= 0) { "filesystem generation must not be negative" }
        }
    }

    class PrepareDeployment(
        override val requestId: ProgramRuntimeRequestId,
        artifact: ByteArray,
    ) : ProgramRuntimeActorCommand {
        private val artifact = artifact.copyOf()

        internal fun artifactBytes(): ByteArray = artifact.copyOf()
    }

    data class DiscardDeployment(
        override val requestId: ProgramRuntimeRequestId,
        val token: ProgramDeploymentToken,
    ) : ProgramRuntimeActorCommand

    data class ExecutableRevision(
        override val requestId: ProgramRuntimeRequestId,
        val path: String,
    ) : ProgramRuntimeActorCommand

    data class Deploy(
        override val requestId: ProgramRuntimeRequestId,
        val path: String,
        val expected: VmExecutableRevision,
        val token: ProgramDeploymentToken,
    ) : ProgramRuntimeActorCommand

    class SubmitCanonicalLine(
        override val requestId: ProgramRuntimeRequestId,
        line: CharArray,
    ) : ProgramRuntimeActorCommand {
        private val line = line.copyOf()

        internal fun lineChars(): CharArray = line.copyOf()
    }

    data class SubmitRedstoneInput(
        override val requestId: ProgramRuntimeRequestId,
        val packet: Int,
    ) : ProgramRuntimeActorCommand

    data class CompleteRedstoneOutput(
        override val requestId: ProgramRuntimeRequestId,
        val outputRequestId: ProgramRuntimeRequestId,
        val packed: Int,
        val result: RedstoneCommitResult,
    ) : ProgramRuntimeActorCommand {
        init {
            require(result != RedstoneCommitResult.Deferred) { "redstone completion cannot be deferred" }
        }
    }

    data class Shutdown(
        override val requestId: ProgramRuntimeRequestId,
    ) : ProgramRuntimeActorCommand

    data class Reboot(
        override val requestId: ProgramRuntimeRequestId,
    ) : ProgramRuntimeActorCommand
}

data class ProgramRuntimeActorReply(
    val requestId: ProgramRuntimeRequestId,
    val state: ProgramRuntimeState,
    val value: ProgramRuntimeActorValue,
    val fileSystemGeneration: Long? = null,
)

sealed interface ProgramRuntimeActorValue {
    data object None : ProgramRuntimeActorValue

    data class Start(
        val result: ProgramStartResult,
    ) : ProgramRuntimeActorValue

    data class Accepted(
        val accepted: Boolean,
    ) : ProgramRuntimeActorValue

    data class TerminalStateValue(
        val state: TerminalState?,
    ) : ProgramRuntimeActorValue

    data class TerminalUpdateValue(
        val update: TerminalUpdate?,
    ) : ProgramRuntimeActorValue

    data class FileSystemGeneration(
        val generation: Long?,
    ) : ProgramRuntimeActorValue

    data class ResourceSnapshotValue(
        val snapshot: ProgramResourceSnapshot,
    ) : ProgramRuntimeActorValue

    data class FileStatValue(
        val stat: VmFileStat?,
    ) : ProgramRuntimeActorValue

    data class FileListValue(
        val listing: VmDirectoryListing?,
    ) : ProgramRuntimeActorValue

    class FileChunkValue(
        chunk: VmFileChunk?,
    ) : ProgramRuntimeActorValue {
        val chunk: VmFileChunk? = chunk?.copy(bytes = chunk.bytes.copyOf())
    }

    data class DeploymentPrepared(
        val token: ProgramDeploymentToken?,
    ) : ProgramRuntimeActorValue

    data class ExecutableRevisionValue(
        val revision: VmExecutableRevision?,
    ) : ProgramRuntimeActorValue

    data class DeployedRevision(
        val revision: VmExecutableRevision?,
    ) : ProgramRuntimeActorValue

    data class RedstoneOutputRequested(
        val packed: Int,
    ) : ProgramRuntimeActorValue

    data class Rejected(
        val failure: ProgramRuntimeActorFailure,
    ) : ProgramRuntimeActorValue
}

sealed interface ProgramRuntimeActorFailure {
    data class FileSystem(
        val failure: VmFileSystemReadFailure,
    ) : ProgramRuntimeActorFailure

    data class InvalidRequest(
        val detail: String,
    ) : ProgramRuntimeActorFailure

    data class Bridge(
        val detail: String,
    ) : ProgramRuntimeActorFailure

    data object Verification : ProgramRuntimeActorFailure

    data class CanonicalLine(
        val failure: VmCanonicalLineFailure,
    ) : ProgramRuntimeActorFailure

    data class Deployment(
        val failure: ProgramDeploymentFailure,
    ) : ProgramRuntimeActorFailure
}

enum class ProgramDeploymentFailure {
    CONFLICT,
    WRONG_MACHINE,
    PROFILE_CHANGED,
    FILESYSTEM,
    ADMISSION,
}
