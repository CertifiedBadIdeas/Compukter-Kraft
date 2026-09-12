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

package ru.lazyhat.compukters.impl.ide.target

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetDirectoryListing
import ru.lazyhat.compukters.ide.client.target.IdeTargetFailure
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileChunk
import ru.lazyhat.compukters.ide.client.target.IdeTargetFileStat
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath

object IdeTargetProtocolLimits {
    const val CHUNK_BYTES = 32 * 1024
    const val CANONICAL_LINE_CODE_UNITS = 4_096
    const val FILE_LIST_ENTRIES = 256
    const val FILE_READ_BYTES = 32 * 1024
}

class IdeCanonicalLine private constructor(
    value: CharArray,
) {
    private val value = value.copyOf()

    fun chars(): CharArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is IdeCanonicalLine && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    companion object {
        fun of(value: CharArray): IdeCanonicalLine {
            require(value.size <= IdeTargetProtocolLimits.CANONICAL_LINE_CODE_UNITS) { "canonical line is too long" }
            return IdeCanonicalLine(value)
        }
    }
}

sealed interface IdeTargetRequest {
    data class Attach(
        val claim: BinaryValue,
    ) : IdeTargetRequest

    data class BeginUpload(
        val target: IdeTargetReference,
        val artifactHash: Hash256,
        val bytes: Int,
    ) : IdeTargetRequest {
        init {
            require(bytes > 0) { "artifact size must be positive" }
        }
    }

    data class UploadChunk(
        val target: IdeTargetReference,
        val offset: Int,
        val bytes: BinaryValue,
    ) : IdeTargetRequest {
        init {
            require(offset >= 0) { "artifact chunk offset must not be negative" }
        }
    }

    data class Verify(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class ExecutableRevision(
        val target: IdeTargetReference,
        val path: IdeDeploymentPath,
    ) : IdeTargetRequest

    data class Deploy(
        val target: IdeTargetReference,
        val ticket: BinaryValue,
        val artifactHash: Hash256,
        val artifactBytes: Int,
        val path: IdeDeploymentPath,
        val expected: IdeExecutableRevision,
    ) : IdeTargetRequest {
        init {
            require(artifactBytes > 0) { "ticket artifact size must be positive" }
        }
    }

    data class SubmitCanonicalLine(
        val target: IdeTargetReference,
        val line: IdeCanonicalLine,
    ) : IdeTargetRequest

    data class Heartbeat(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class Detach(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class FileStat(
        val target: IdeTargetReference,
        val path: IdeTargetVirtualPath,
    ) : IdeTargetRequest

    data class FileList(
        val target: IdeTargetReference,
        val path: IdeTargetVirtualPath,
        val startAfter: String?,
        val maximumEntries: Int,
    ) : IdeTargetRequest {
        init {
            require(maximumEntries in 1..IdeTargetProtocolLimits.FILE_LIST_ENTRIES)
            startAfter?.let { name ->
                require(name.isNotEmpty() && name != "." && name != "..")
                require(name.none { it == '/' || it == '\\' || it.isISOControl() })
            }
        }
    }

    data class FileRead(
        val target: IdeTargetReference,
        val path: IdeTargetVirtualPath,
        val offset: Long,
        val maximumBytes: Int,
        val expectedGeneration: Long,
    ) : IdeTargetRequest {
        init {
            require(offset >= 0 && expectedGeneration >= 0)
            require(maximumBytes in 1..IdeTargetProtocolLimits.FILE_READ_BYTES)
        }
    }
}

sealed interface IdeTargetReply {
    data class Attached(
        val target: IdeAttachedTarget,
    ) : IdeTargetReply

    data object UploadAccepted : IdeTargetReply

    data class Verified(
        val ticket: BinaryValue,
        val target: IdeTargetReference,
        val artifactHash: Hash256,
        val artifactBytes: Int,
    ) : IdeTargetReply {
        init {
            require(artifactBytes > 0) { "ticket artifact size must be positive" }
        }
    }

    data class RevisionObserved(
        val revision: IdeExecutableRevision,
    ) : IdeTargetReply

    data class Deployed(
        val revision: IdeExecutableRevision.Present,
    ) : IdeTargetReply

    data class StaleRevision(
        val actual: IdeExecutableRevision,
    ) : IdeTargetReply

    data object Submitted : IdeTargetReply

    data object Alive : IdeTargetReply

    data object Detached : IdeTargetReply

    data class Failed(
        val failure: IdeTargetFailure,
        val retryable: Boolean,
    ) : IdeTargetReply

    data class FileStatObserved(
        val stat: IdeTargetFileStat,
    ) : IdeTargetReply

    data class FileListed(
        val listing: IdeTargetDirectoryListing,
    ) : IdeTargetReply

    data class FileRead(
        val chunk: IdeTargetFileChunk,
    ) : IdeTargetReply
}
