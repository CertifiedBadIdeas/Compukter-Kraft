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

package ru.lazyhat.compukters.compiler.artifact.write

enum class ArtifactWriteErrorCode {
    INVALID_RANGE,
    OVERFLOW,
    DUPLICATE_VALUE,
    NON_CANONICAL_ORDER,
    INVALID_UTF8,
    INVALID_PATH,
    BAD_REFERENCE,
    INCONSISTENT_RANGE,
    UNSUPPORTED_INSTRUCTION,
    INCOMPATIBLE_FEATURE_SET,
    LIMIT_EXCEEDED,
}

data class ArtifactWriteLocation(
    val module: UInt? = null,
    val table: String? = null,
    val record: UInt? = null,
    val instruction: UInt? = null,
)

data class ArtifactWriteError(
    val code: ArtifactWriteErrorCode,
    val location: ArtifactWriteLocation? = null,
    val detail: String,
)

sealed interface ArtifactWriteResult {
    class Success(
        bytes: ByteArray,
        sha256: ByteArray,
    ) : ArtifactWriteResult {
        val bytes: ByteArray = bytes.copyOf()
        val sha256: ByteArray = sha256.copyOf()
    }

    data class Failure(
        val errors: List<ArtifactWriteError>,
    ) : ArtifactWriteResult {
        init {
            require(errors.isNotEmpty()) { "writer failure must contain at least one diagnostic" }
        }
    }
}

data class ArtifactWriteLimits(
    val artifactBytes: Int = 16 * 1024 * 1024,
    val sections: Int = 4_096,
    val recordsPerSection: Int = 1_000_000,
    val metadataUtf8Bytes: Int = 4 * 1024 * 1024,
    val utf16CodeUnits: Int = 4 * 1024 * 1024,
    val codeBytes: Int = 8 * 1024 * 1024,
    val modules: Int = 65_536,
    val functions: Int = 1_000_000,
    val registersPerFunction: Int = 65_535,
    val physicalComponentsPerValue: Int = 65_535,
    val safepointsPerFunction: Int = 1_000_000,
    val rootsPerSafepoint: Int = 65_535,
    val blocks: Int = 1_000_000,
    val imports: Int = 1_000_000,
    val exceptions: Int = 1_000_000,
    val capabilities: Int = 65_536,
    val debugBytes: Int = 8 * 1024 * 1024,
    val diagnostics: Int = 64,
)

internal class ArtifactEncodingException(
    val code: ArtifactWriteErrorCode,
    message: String,
) : IllegalArgumentException(message)
