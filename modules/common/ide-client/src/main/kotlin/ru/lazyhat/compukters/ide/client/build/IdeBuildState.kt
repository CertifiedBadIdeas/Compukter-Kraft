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

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import java.util.Collections

class IdeBuiltArtifact private constructor(
    val hash: Hash256,
    private val value: BinaryValue,
) {
    val size: Int get() = value.size

    fun bytes(): ByteArray = value.toByteArray()

    override fun equals(other: Any?): Boolean = other is IdeBuiltArtifact && hash == other.hash && value == other.value

    override fun hashCode(): Int = 31 * hash.hashCode() + value.hashCode()

    companion object {
        fun of(
            hash: Hash256,
            bytes: ByteArray,
        ): IdeBuiltArtifact = IdeBuiltArtifact(hash, BinaryValue.of(bytes))

        internal fun admit(
            hash: Hash256,
            value: BinaryValue,
        ): IdeBuiltArtifact = IdeBuiltArtifact(hash, value)
    }
}

sealed interface IdeBuildState {
    data object Idle : IdeBuildState

    data class Saving(
        val operationId: Long,
    ) : IdeBuildState

    data class Compiling(
        val operationId: Long,
        val identity: Hash256,
        val sourceSnapshotId: SourceSnapshotId,
    ) : IdeBuildState

    data class Succeeded(
        val identity: Hash256,
        val artifact: IdeBuiltArtifact,
        val programName: String,
        val cacheHit: Boolean,
        val completedAtMillis: Long,
    ) : IdeBuildState {
        val artifactHash: Hash256 get() = artifact.hash
        val bytes: Int get() = artifact.size

        init {
            require(programName.isNotBlank()) { "program name must not be blank" }
            require(completedAtMillis >= 0) { "completion time must be non-negative" }
        }
    }

    class Diagnostics(
        val identity: Hash256,
        val sourceSnapshotId: SourceSnapshotId,
        values: List<EditorDiagnostic>,
    ) : IdeBuildState {
        val values: List<EditorDiagnostic> = Collections.unmodifiableList(values.toList())
    }

    data class Failed(
        val kind: IdeBuildFailureKind,
        val detail: String,
    ) : IdeBuildState
}

enum class IdeBuildFailureKind {
    MissingLock,
    Conflict,
    InvalidManifest,
    InvalidLock,
    UnsatisfiedProfile,
    QueueFull,
    Cancelled,
    Platform,
    Closed,
}

sealed interface IdeResolveResult {
    data object Created : IdeResolveResult

    data object Updated : IdeResolveResult

    data object UpToDate : IdeResolveResult

    data object ConfirmationRequired : IdeResolveResult

    data class Failed(
        val detail: String,
    ) : IdeResolveResult
}
