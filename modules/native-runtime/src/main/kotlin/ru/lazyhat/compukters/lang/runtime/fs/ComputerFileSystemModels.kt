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

package ru.lazyhat.compukters.lang.runtime.fs

@JvmInline
value class VmVirtualPath private constructor(
    val value: String,
) {
    companion object {
        private const val MAXIMUM_UTF8_BYTES = 4 * 1024

        fun of(value: String): VmVirtualPath {
            require(value.startsWith('/')) { "virtual path must be absolute" }
            require(value.length == 1 || !value.endsWith('/')) { "virtual path must not end with a separator" }
            require(value.none { it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
                "virtual path contains a forbidden character"
            }
            if (value != "/") {
                require(value.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                    "virtual path contains a non-canonical component"
                }
            }
            require(value.encodeToByteArray().size <= MAXIMUM_UTF8_BYTES) { "virtual path is too long" }
            return VmVirtualPath(value)
        }
    }
}

enum class VmFileKind {
    FILE,
    DIRECTORY,
}

data class VmFileMetadata(
    val kind: VmFileKind,
    val logicalBytes: Long,
    val generation: Long,
    val executable: Boolean,
)

data class VmFileStat(
    val fileSystemGeneration: Long,
    val metadata: VmFileMetadata,
)

data class VmDirectoryEntry(
    val name: String,
    val metadata: VmFileMetadata,
)

data class VmDirectoryListing(
    val fileSystemGeneration: Long,
    val directoryGeneration: Long,
    val complete: Boolean,
    val entries: List<VmDirectoryEntry>,
)

data class VmFileChunk(
    val generation: Long,
    val nextOffset: Long,
    val eof: Boolean,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is VmFileChunk &&
                    generation == other.generation &&
                    nextOffset == other.nextOffset &&
                    eof == other.eof &&
                    bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int =
        31 * (31 * (31 * generation.hashCode() + nextOffset.hashCode()) + eof.hashCode()) + bytes.contentHashCode()
}

enum class VmFileSystemReadFailure {
    INVALID_PATH,
    NOT_FOUND,
    NOT_DIRECTORY,
    NOT_FILE,
    PERMISSION,
    STALE_GENERATION,
    LIMIT,
    STORAGE,
}

class VmFileSystemReadException(
    val failure: VmFileSystemReadFailure,
) : IllegalStateException("native filesystem inspection failed: $failure")
