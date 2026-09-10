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

package ru.lazyhat.compukters.lang.runtime.vm

data class CapabilityIdentity(
    val namespace: String,
    val name: String,
    val abiMajor: Int,
    val abiMinor: Int,
)

sealed interface VmValue {
    data class I32(
        val value: Int,
    ) : VmValue

    data class I64(
        val value: Long,
    ) : VmValue

    data class F32(
        val bits: Int,
    ) : VmValue

    data class F64(
        val bits: Long,
    ) : VmValue

    data class Bool(
        val value: Boolean,
    ) : VmValue

    data class CharValue(
        val value: Char,
    ) : VmValue

    data class StringValue(
        val value: String,
    ) : VmValue
}

data class VmHostRequest(
    val id: Long,
    val capability: CapabilityIdentity,
    val operation: Int,
    val arguments: List<VmValue>,
    val taskId: Int = 1,
    val merge: VmHostMerge = VmHostMerge.Ordinary,
) {
    val identity: VmHostRequestIdentity
        get() = VmHostRequestIdentity(taskId, id)

    init {
        require(taskId > 0) { "host request task id must be positive" }
        require(id > 0) { "host request id must be positive" }
    }
}

data class VmHostRequestIdentity(
    val taskId: Int,
    val requestId: Long,
) {
    init {
        require(taskId > 0) { "host request task id must be positive" }
        require(requestId > 0) { "host request id must be positive" }
    }
}

data class VmHostMergeEntry(
    val keyBits: Int,
    val valueBits: Int,
)

sealed interface VmHostMerge {
    data object Ordinary : VmHostMerge

    class LastWriteWins(
        val groupBits: Int,
        entries: List<VmHostMergeEntry>,
    ) : VmHostMerge {
        val entries: List<VmHostMergeEntry> = entries.toList()

        init {
            require(this.entries.isNotEmpty()) { "mergeable host request must contain entries" }
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is LastWriteWins &&
                        groupBits == other.groupBits &&
                        entries == other.entries
                )

        override fun hashCode(): Int = 31 * groupBits + entries.hashCode()

        override fun toString(): String = "LastWriteWins(groupBits=$groupBits, entries=$entries)"
    }
}

class VmCompilationSource(
    val path: String,
    utf8: ByteArray,
) {
    private val utf8 = utf8.copyOf()

    fun utf8Bytes(): ByteArray = utf8.copyOf()

    override fun equals(other: Any?): Boolean = other is VmCompilationSource && path == other.path && utf8.contentEquals(other.utf8)

    override fun hashCode(): Int = 31 * path.hashCode() + utf8.contentHashCode()

    override fun toString(): String = "VmCompilationSource(path=$path, utf8Bytes=${utf8.size})"
}

class VmCompilationRequest(
    val token: Long,
    sources: List<VmCompilationSource>,
) {
    val sources = sources.toList()

    init {
        require(token > 0) { "compilation token must be positive" }
        require(this.sources.isNotEmpty()) { "compilation request must contain sources" }
    }

    override fun equals(other: Any?): Boolean = other is VmCompilationRequest && token == other.token && sources == other.sources

    override fun hashCode(): Int = 31 * token.hashCode() + sources.hashCode()

    override fun toString(): String = "VmCompilationRequest(token=$token, sources=$sources)"
}

enum class GuestTrap(
    internal val wireCode: Int,
) {
    DIVISION_BY_ZERO(0),
    STACK_OVERFLOW(1),
    NEGATIVE_ARRAY_SIZE(2),
    NULL_REFERENCE(3),
    INDEX_OUT_OF_BOUNDS(4),
    CLASS_CAST(5),
    INVALID_ARGUMENT(7),
}

enum class VmFault(
    internal val wireCode: Int,
) {
    INVALID_RESOLVED_ID(0),
    INVALID_VALUE_TYPE(1),
    ACCOUNTING_OVERFLOW(2),
    INVALID_STORAGE_PLAN(3),
    CORRUPT_LIFECYCLE(4),
    REACHED_UNREACHABLE(5),
    UNSUPPORTED_INSTRUCTION(6),
    HANDLE_EXHAUSTED(7),
    CORRUPT_HEAP(8),
    INVALID_REFERENCE(9),
}

enum class QuotaKind(
    internal val wireCode: Int,
) {
    HOST_REQUEST_CODE_UNITS(0),
    HOST_REQUESTS(1),
    ACCEPTED_RESPONSES(2),
}

enum class HostFailureKind(
    internal val wireCode: Int,
) {
    END_OF_FILE(0),
    UNAVAILABLE(1),
    INPUT_OUTPUT(2),
    CANCELLED(3),
    OTHER(4),
}

sealed interface VmOutcome {
    data object SliceExhausted : VmOutcome

    data object WaitingForHostQuota : VmOutcome

    data object WaitingForTerminalEvent : VmOutcome

    class HostRequestBatch(
        requests: List<VmHostRequest>,
    ) : VmOutcome {
        val requests: List<VmHostRequest> = requests.toList()

        init {
            require(this.requests.isNotEmpty()) { "host request batch must not be empty" }
            require(
                this.requests
                    .map(VmHostRequest::identity)
                    .toSet()
                    .size == this.requests.size,
            ) {
                "host request batch identities must be unique"
            }
        }

        override fun equals(other: Any?): Boolean = this === other || (other is HostRequestBatch && requests == other.requests)

        override fun hashCode(): Int = requests.hashCode()

        override fun toString(): String = "HostRequestBatch(requests=$requests)"
    }

    data class AllocationExhausted(
        val collectionAttempted: Boolean,
    ) : VmOutcome

    data class QuotaExhausted(
        val kind: QuotaKind,
        val limit: Long,
        val consumed: Long,
    ) : VmOutcome

    data class Halted(
        val value: VmValue?,
    ) : VmOutcome

    data class Crashed(
        val trap: GuestTrap,
    ) : VmOutcome

    data class Faulted(
        val fault: VmFault,
    ) : VmOutcome

    data class HostFailed(
        val kind: HostFailureKind,
        val code: Long,
    ) : VmOutcome

    data class CompilationRequested(
        val request: VmCompilationRequest,
    ) : VmOutcome
}

data class VmResourceSnapshot(
    val fixedGuestUnits: Long,
    val dynamicGuestUnits: Long,
    val maintenanceUnits: Long,
    val enteredBlocks: Long,
    val executedInstructions: Long,
    val heapCapacityBytes: Long,
    val heapUsedBytes: Long,
    val liveObjects: Long,
    val mutableExecutionResidentBytes: Long,
    val filesystemLogicalBytes: Long,
    val filesystemLogicalCapacityBytes: Long,
    val filesystemNodes: Long,
    val filesystemNodeCapacity: Long,
    val countersSaturated: Boolean,
) {
    init {
        require(
            listOf(
                fixedGuestUnits,
                dynamicGuestUnits,
                maintenanceUnits,
                enteredBlocks,
                executedInstructions,
                heapCapacityBytes,
                heapUsedBytes,
                liveObjects,
                mutableExecutionResidentBytes,
                filesystemLogicalBytes,
                filesystemLogicalCapacityBytes,
                filesystemNodes,
                filesystemNodeCapacity,
            ).all { it >= 0 },
        ) { "resource snapshot values must not be negative" }
        require(heapUsedBytes <= heapCapacityBytes) { "heap usage exceeds capacity" }
        require(filesystemLogicalBytes <= filesystemLogicalCapacityBytes) { "filesystem usage exceeds capacity" }
        require(filesystemNodes <= filesystemNodeCapacity) { "filesystem node usage exceeds capacity" }
    }
}

class VmVerificationException : IllegalArgumentException("native VM rejected the artifact")

class VmAdmissionException(
    val code: Int,
) : IllegalArgumentException("native VM admission failed with code $code")

class VmStartException(
    val code: Int,
) : IllegalStateException("native VM start failed with code $code")

class VmBootException(
    val code: Int,
) : IllegalStateException("native VM boot failed with process result $code")

sealed interface VmExecutableRevision {
    data object Absent : VmExecutableRevision

    data class Present(
        val generation: Long,
    ) : VmExecutableRevision {
        init {
            require(generation >= 0) { "executable generation must not be negative" }
        }
    }
}

class VmDeploymentConflictException : IllegalStateException("deployment target changed")

class VmDeploymentWrongMachineException : IllegalArgumentException("deployment candidate belongs to another VM")

class VmDeploymentProfileChangedException : IllegalStateException("VM execution profile changed after verification")

class VmDeploymentFileSystemException : IllegalStateException("deployment filesystem operation failed")

class VmDeploymentAdmissionException : IllegalArgumentException("VM rejected deployment admission")

enum class VmCanonicalLineFailure {
    NO_PENDING_READ,
    INPUT_BUSY,
    PARTIAL_INPUT,
    UNSUPPORTED_CODE_UNIT,
    LINE_TOO_LONG,
    TERMINAL,
    RESUME,
}

class VmCanonicalLineException(
    val failure: VmCanonicalLineFailure,
) : IllegalStateException("canonical line submission failed: $failure")

class VmBridgeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
