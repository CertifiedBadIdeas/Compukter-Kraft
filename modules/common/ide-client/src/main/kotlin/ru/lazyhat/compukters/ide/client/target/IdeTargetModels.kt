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

package ru.lazyhat.compukters.ide.client.target

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile

@JvmInline
value class IdeTargetId(
    val value: String,
) {
    init {
        requireBoundedText(value, 128, "target ID")
    }
}

@JvmInline
value class IdeTargetProfileId(
    val value: Hash256,
)

data class IdeTargetCapabilities(
    val writableFileSystem: Boolean,
    val canonicalInput: Boolean,
    val terminal: Boolean,
    val readableFileSystem: Boolean = false,
)

@JvmInline
value class IdeTargetVirtualPath private constructor(
    val value: String,
) {
    companion object {
        private const val MAXIMUM_UTF8_BYTES = 4 * 1024

        fun of(value: String): IdeTargetVirtualPath {
            require(value.startsWith('/')) { "target path must be absolute" }
            require(value.length == 1 || !value.endsWith('/')) { "target path must not end with a separator" }
            require(value.none { it == '\\' || it.isISOControl() }) { "target path contains a forbidden character" }
            if (value != "/") {
                require(value.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                    "target path contains a non-canonical component"
                }
            }
            require(value.encodeToByteArray().size <= MAXIMUM_UTF8_BYTES) { "target path is too long" }
            return IdeTargetVirtualPath(value)
        }
    }
}

enum class IdeTargetFileKind {
    File,
    Directory,
}

data class IdeTargetFileMetadata(
    val kind: IdeTargetFileKind,
    val logicalBytes: Long,
    val generation: Long,
    val executable: Boolean,
) {
    init {
        require(logicalBytes >= 0 && generation >= 0) { "target file metadata must not be negative" }
    }
}

data class IdeTargetFileStat(
    val fileSystemGeneration: Long,
    val metadata: IdeTargetFileMetadata,
) {
    init {
        require(fileSystemGeneration >= 0) { "target filesystem generation must not be negative" }
    }
}

data class IdeTargetDirectoryEntry(
    val name: String,
    val metadata: IdeTargetFileMetadata,
) {
    init {
        require(name.isNotEmpty() && name != "." && name != "..") { "target entry name is invalid" }
        require(name.none { it == '/' || it == '\\' || it.isISOControl() }) { "target entry name is invalid" }
    }
}

data class IdeTargetDirectoryListing(
    val fileSystemGeneration: Long,
    val directoryGeneration: Long,
    val complete: Boolean,
    val entries: List<IdeTargetDirectoryEntry>,
) {
    init {
        require(fileSystemGeneration >= 0 && directoryGeneration >= 0) { "target directory generations must not be negative" }
        require(entries.size <= 256) { "target directory page is too large" }
        require(entries.zipWithNext().all { (left, right) -> compareUtf8(left.name, right.name) < 0 }) {
            "target directory entries must be strictly UTF-8 ordered"
        }
    }
}

private fun compareUtf8(
    left: String,
    right: String,
): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
        val difference = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return leftBytes.size - rightBytes.size
}

class IdeTargetFileChunk(
    val generation: Long,
    val nextOffset: Long,
    val eof: Boolean,
    bytes: ByteArray,
) {
    private val value = BinaryValue.of(bytes)

    init {
        require(generation >= 0 && nextOffset >= 0) { "target file chunk metadata must not be negative" }
        require(bytes.size <= 32 * 1024) { "target file chunk is too large" }
    }

    fun bytes(): ByteArray = value.toByteArray()
}

sealed interface IdeFileStatResult {
    data class Observed(
        val stat: IdeTargetFileStat,
    ) : IdeFileStatResult

    data class Failed(
        val failure: IdeTargetFailure,
    ) : IdeFileStatResult
}

sealed interface IdeFileListResult {
    data class Listed(
        val listing: IdeTargetDirectoryListing,
    ) : IdeFileListResult

    data class Failed(
        val failure: IdeTargetFailure,
    ) : IdeFileListResult
}

sealed interface IdeFileReadResult {
    data class Read(
        val chunk: IdeTargetFileChunk,
    ) : IdeFileReadResult

    data class Failed(
        val failure: IdeTargetFailure,
    ) : IdeFileReadResult
}

data class IdeAttachedTarget(
    val id: IdeTargetId,
    val profile: IdeTargetProfileId,
    val compileProfile: TargetCompileProfile,
    val capabilities: IdeTargetCapabilities,
    val displayName: String,
) {
    init {
        requireBoundedText(displayName, 128, "target display name")
    }
}

class IdeTargetClaim private constructor(
    private val value: BinaryValue,
) {
    fun bytes(): ByteArray = value.toByteArray()

    companion object {
        fun of(bytes: ByteArray): IdeTargetClaim {
            require(bytes.isNotEmpty() && bytes.size <= 256) { "target claim must contain 1..256 bytes" }
            return IdeTargetClaim(BinaryValue.of(bytes))
        }
    }
}

class IdeTargetArtifact(
    val hash: Hash256,
    bytes: ByteArray,
) {
    private val value = BinaryValue.of(bytes)
    val size: Int = bytes.size

    init {
        require(bytes.isNotEmpty()) { "target artifact must not be empty" }
    }

    fun bytes(): ByteArray = value.toByteArray()
}

class IdeVerificationTicket private constructor(
    private val value: BinaryValue,
    val targetId: IdeTargetId,
    val profileId: IdeTargetProfileId,
    val artifactHash: Hash256,
    val artifactBytes: Int,
) {
    fun bytes(): ByteArray = value.toByteArray()

    fun matches(
        target: IdeAttachedTarget,
        artifact: IdeTargetArtifact,
    ): Boolean =
        targetId == target.id &&
            profileId == target.profile &&
            artifactHash == artifact.hash &&
            artifactBytes == artifact.size

    companion object {
        fun of(
            bytes: ByteArray,
            target: IdeAttachedTarget,
            artifact: IdeTargetArtifact,
        ): IdeVerificationTicket = of(bytes, target, artifact.hash, artifact.size)

        fun of(
            bytes: ByteArray,
            target: IdeAttachedTarget,
            artifactHash: Hash256,
            artifactBytes: Int,
        ): IdeVerificationTicket {
            require(bytes.isNotEmpty() && bytes.size <= 256) { "verification ticket must contain 1..256 bytes" }
            require(artifactBytes > 0) { "verification ticket artifact size must be positive" }
            return IdeVerificationTicket(BinaryValue.of(bytes), target.id, target.profile, artifactHash, artifactBytes)
        }
    }
}

@JvmInline
value class IdeDeploymentPath private constructor(
    val value: String,
) {
    companion object {
        fun fromProgramName(name: String): IdeDeploymentPath {
            requireBoundedText(name, 128, "program name")
            require(name != "." && name != "..") { "program name is reserved" }
            require(name.none { it == '/' || it == '\\' || it.isISOControl() }) { "program name contains a forbidden character" }
            return IdeDeploymentPath("/home/$name")
        }
    }
}

sealed interface IdeExecutableRevision {
    data object Absent : IdeExecutableRevision

    data class Present(
        val generation: Long,
    ) : IdeExecutableRevision {
        init {
            require(generation >= 0) { "executable generation must not be negative" }
        }
    }
}

enum class IdeLaunchStrategy {
    CanonicalInput,
}

enum class IdeTargetFailureKind {
    Permission,
    TargetLost,
    Unsupported,
    Profile,
    Upload,
    Verification,
    Admission,
    Conflict,
    FileSystem,
    InputUnavailable,
    InputBusy,
    InputPartial,
    InputTooLong,
    Timeout,
    Protocol,
    Closed,
    Other,
}

data class IdeTargetFailure(
    val kind: IdeTargetFailureKind,
    val detail: String,
) {
    init {
        requireBoundedText(detail, 512, "target failure detail")
    }
}

sealed interface IdeAttachResult {
    data class Attached(
        val target: IdeAttachedTarget,
    ) : IdeAttachResult

    data class Rejected(
        val failure: IdeTargetFailure,
    ) : IdeAttachResult
}

sealed interface IdeVerifyResult {
    data class Verified(
        val ticket: IdeVerificationTicket,
    ) : IdeVerifyResult

    data class Failed(
        val failure: IdeTargetFailure,
    ) : IdeVerifyResult
}

sealed interface IdeRevisionResult {
    data class Observed(
        val revision: IdeExecutableRevision,
    ) : IdeRevisionResult

    data class Failed(
        val failure: IdeTargetFailure,
    ) : IdeRevisionResult
}

sealed interface IdeDeployResult {
    data class Deployed(
        val revision: IdeExecutableRevision.Present,
    ) : IdeDeployResult

    data class StaleRevision(
        val actual: IdeExecutableRevision,
    ) : IdeDeployResult

    data class Failed(
        val failure: IdeTargetFailure,
        val retryable: Boolean,
    ) : IdeDeployResult
}

sealed interface IdeSubmissionResult {
    data object Submitted : IdeSubmissionResult

    data class Failed(
        val failure: IdeTargetFailure,
    ) : IdeSubmissionResult
}

sealed interface IdeHeartbeatResult {
    data object Alive : IdeHeartbeatResult

    data class Lost(
        val failure: IdeTargetFailure,
    ) : IdeHeartbeatResult
}

sealed interface IdeTargetState {
    data object LocalOnly : IdeTargetState

    data class Attaching(
        val operationId: Long,
    ) : IdeTargetState

    data class Attached(
        val target: IdeAttachedTarget,
    ) : IdeTargetState

    data class Uploading(
        val target: IdeAttachedTarget,
        val artifactHash: Hash256,
    ) : IdeTargetState

    data class Verified(
        val target: IdeAttachedTarget,
        val artifactHash: Hash256,
    ) : IdeTargetState

    data class Observing(
        val target: IdeAttachedTarget,
        val path: IdeDeploymentPath,
    ) : IdeTargetState

    data class ConfirmationRequired(
        val target: IdeAttachedTarget,
        val path: IdeDeploymentPath,
        val revision: IdeExecutableRevision.Present,
    ) : IdeTargetState

    data class Deploying(
        val target: IdeAttachedTarget,
        val path: IdeDeploymentPath,
    ) : IdeTargetState

    data class Deployed(
        val target: IdeAttachedTarget,
        val path: IdeDeploymentPath,
        val revision: IdeExecutableRevision.Present,
    ) : IdeTargetState

    data class Submitting(
        val target: IdeAttachedTarget,
        val path: IdeDeploymentPath,
        val revision: IdeExecutableRevision.Present,
    ) : IdeTargetState

    data class CommandSubmitted(
        val target: IdeAttachedTarget,
        val path: IdeDeploymentPath,
        val revision: IdeExecutableRevision.Present,
        val message: String = "Command submitted",
    ) : IdeTargetState

    data class Detached(
        val failure: IdeTargetFailure,
    ) : IdeTargetState

    data class Failed(
        val target: IdeAttachedTarget?,
        val failure: IdeTargetFailure,
        val deployed: Deployed? = null,
    ) : IdeTargetState
}

private fun requireBoundedText(
    value: String,
    maximumUtf8Bytes: Int,
    label: String,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.isWellFormedUtf16()) { "$label must be well-formed UTF-16" }
    require(value.encodeToByteArray().size <= maximumUtf8Bytes) { "$label is too long" }
}

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            current.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }

            current.isLowSurrogate() -> {
                return false
            }

            else -> {
                index++
            }
        }
    }
    return true
}
