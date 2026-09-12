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

import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadException
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

internal class FfmBridge private constructor(
    private val arena: Arena,
    private val abiVersionHandle: MethodHandle,
    private val maximumCreateBytesHandle: MethodHandle,
    private val maximumOutcomeBytesHandle: MethodHandle,
    private val storeOpenHandle: MethodHandle,
    private val storeHealthHandle: MethodHandle,
    private val storeDurableGenerationHandle: MethodHandle,
    private val storeFlushHandle: MethodHandle,
    private val storeTombstoneHandle: MethodHandle,
    private val storeRecoverHandle: MethodHandle,
    private val storeCloseHandle: MethodHandle,
    private val verifyArtifactHandle: MethodHandle,
    private val createHandle: MethodHandle,
    private val createInStoreHandle: MethodHandle,
    private val createBootInStoreHandle: MethodHandle,
    private val filesystemGenerationHandle: MethodHandle,
    private val resourceSnapshotHandle: MethodHandle,
    private val filesystemStatHandle: MethodHandle,
    private val filesystemListHandle: MethodHandle,
    private val filesystemReadHandle: MethodHandle,
    private val verifyForDeployHandle: MethodHandle,
    private val deploymentCandidateCloseHandle: MethodHandle,
    private val executableRevisionHandle: MethodHandle,
    private val deployHandle: MethodHandle,
    private val submitCanonicalLineHandle: MethodHandle,
    private val submitRedstoneInputHandle: MethodHandle,
    private val confirmRedstoneOutputHandle: MethodHandle,
    private val advanceHandle: MethodHandle,
    private val compilationRequestSizeHandle: MethodHandle,
    private val compilationRequestCopyHandle: MethodHandle,
    private val compilationCompleteHandle: MethodHandle,
    private val resumeUnitHandle: MethodHandle,
    private val resumeBoolHandle: MethodHandle,
    private val resumeStringHandle: MethodHandle,
    private val resumeFailureHandle: MethodHandle,
    private val closeHandle: MethodHandle,
    private val terminalCommitHandle: MethodHandle,
    private val terminalFullStateHandle: MethodHandle,
    private val terminalChangesSinceHandle: MethodHandle,
    private val terminalKeyHandle: MethodHandle,
    private val terminalTextHandle: MethodHandle,
) : LowLevelVmBridge,
    AutoCloseable {
    private val maximumOutcomeBytes: Int =
        (maximumOutcomeBytesHandle.invokeExact() as Long)
            .takeIf { it in 1..MAXIMUM_OUTCOME_BYTES }
            ?.toInt()
            ?: throw VmBridgeException("invalid maximum FFM outcome size")

    fun abiVersion(): Int = abiVersionHandle.invokeExact() as Int

    override fun openTerminalTransport(): TerminalWireTransport =
        ReusableTerminalWireTransport(
            maximumBytes = maximumOutcomeBytes,
            fullStateCall =
                TerminalFullStateCall { handle, output, maximum, written ->
                    terminalFullStateHandle.invokeExact(handle, output, maximum, written) as Int
                },
            changesSinceCall =
                TerminalChangesSinceCall { handle, revision, output, maximum, written ->
                    terminalChangesSinceHandle.invokeExact(handle, revision, output, maximum, written) as Int
                },
        )

    override fun storeOpen(
        rootUtf8: ByteArray,
        limitsWire: ByteArray,
    ): ByteArray =
        fixedOutput("filesystem store open", MAXIMUM_STORE_OPEN_BYTES) { callArena, output, written ->
            storeOpenHandle.invokeExact(
                callArena.nativeBytes(rootUtf8),
                rootUtf8.size.toLong(),
                callArena.nativeBytes(limitsWire),
                limitsWire.size.toLong(),
                output,
                MAXIMUM_STORE_OPEN_BYTES.toLong(),
                written,
            ) as Int
        }

    override fun storeHealth(handle: Long): ByteArray =
        fixedOutput("filesystem store health", MAXIMUM_STORE_HEALTH_BYTES) { _, output, written ->
            storeHealthHandle.invokeExact(handle, output, MAXIMUM_STORE_HEALTH_BYTES.toLong(), written) as Int
        }

    override fun storeDurableGeneration(
        handle: Long,
        id: ByteArray,
    ): ByteArray {
        requireComputerId(id)
        return fixedOutput("filesystem durable generation", MAXIMUM_STORE_GENERATION_BYTES) { callArena, output, written ->
            storeDurableGenerationHandle.invokeExact(
                handle,
                callArena.nativeBytes(id),
                output,
                MAXIMUM_STORE_GENERATION_BYTES.toLong(),
                written,
            ) as Int
        }
    }

    override fun storeFlush(
        handle: Long,
        id: ByteArray,
        generation: Long,
    ) {
        requireComputerId(id)
        Arena.ofConfined().use { callArena ->
            requireSuccess(
                "filesystem flush",
                storeFlushHandle.invokeExact(handle, callArena.nativeBytes(id), generation) as Int,
            )
        }
    }

    override fun storeTombstone(
        handle: Long,
        id: ByteArray,
    ) = storeIdOperation("filesystem tombstone", storeTombstoneHandle, handle, id)

    override fun storeRecover(
        handle: Long,
        id: ByteArray,
    ) = storeIdOperation("filesystem recovery", storeRecoverHandle, handle, id)

    override fun storeClose(handle: Long) = requireSuccess("filesystem store close", storeCloseHandle.invokeExact(handle) as Int)

    override fun verifyArtifact(artifact: ByteArray): Boolean =
        Arena.ofConfined().use { callArena ->
            when (
                val status =
                    verifyArtifactHandle.invokeExact(
                        callArena.nativeBytes(artifact),
                        artifact.size.toLong(),
                    ) as Int
            ) {
                STATUS_OK -> true
                STATUS_VERIFICATION -> false
                else -> throw failure("artifact verification", status)
            }
        }

    override fun create(artifact: ByteArray): ByteArray =
        Arena.ofConfined().use { callArena ->
            val maximum = maximumCreateBytes()
            val output = callArena.allocate(maximum.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val status =
                createHandle.invokeExact(
                    callArena.nativeBytes(artifact),
                    artifact.size.toLong(),
                    output,
                    maximum.toLong(),
                    written,
                ) as Int
            requireSuccess("create", status)
            copyResult("create", output, written, maximum)
        }

    override fun createInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
        artifact: ByteArray,
    ): ByteArray {
        requireComputerId(id)
        return Arena.ofConfined().use { callArena ->
            val maximum = maximumCreateBytes()
            val output = callArena.allocate(maximum.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val status =
                createInStoreHandle.invokeExact(
                    storeHandle,
                    callArena.nativeBytes(id),
                    callArena.nativeBytes(rom),
                    rom.size.toLong(),
                    callArena.nativeBytes(artifact),
                    artifact.size.toLong(),
                    output,
                    maximum.toLong(),
                    written,
                ) as Int
            requireSuccess("create in filesystem store", status)
            copyResult("create in filesystem store", output, written, maximum)
        }
    }

    override fun createBootInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
    ): ByteArray {
        requireComputerId(id)
        return Arena.ofConfined().use { callArena ->
            val maximum = maximumCreateBytes()
            val output = callArena.allocate(maximum.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val status =
                createBootInStoreHandle.invokeExact(
                    storeHandle,
                    callArena.nativeBytes(id),
                    callArena.nativeBytes(rom),
                    rom.size.toLong(),
                    output,
                    maximum.toLong(),
                    written,
                ) as Int
            requireSuccess("boot in filesystem store", status)
            copyResult("boot in filesystem store", output, written, maximum)
        }
    }

    override fun filesystemGeneration(handle: Long): ByteArray =
        fixedOutput("filesystem generation", MAXIMUM_STORE_GENERATION_BYTES) { _, output, written ->
            filesystemGenerationHandle.invokeExact(
                handle,
                output,
                MAXIMUM_STORE_GENERATION_BYTES.toLong(),
                written,
            ) as Int
        }

    override fun resourceSnapshot(handle: Long): ByteArray =
        fixedOutput("resource snapshot", RESOURCE_SNAPSHOT_BYTES) { _, output, written ->
            resourceSnapshotHandle.invokeExact(
                handle,
                output,
                RESOURCE_SNAPSHOT_BYTES.toLong(),
                written,
            ) as Int
        }

    override fun fileStat(
        handle: Long,
        pathUtf8: ByteArray,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val path = callArena.nativeBytes(pathUtf8)
            variableFilesystemOutput("filesystem stat") { output, capacity, written ->
                filesystemStatHandle.invokeExact(
                    handle,
                    path,
                    pathUtf8.size.toLong(),
                    output,
                    capacity,
                    written,
                ) as Int
            }
        }

    override fun fileList(
        handle: Long,
        pathUtf8: ByteArray,
        startAfterUtf8: ByteArray,
        maximumEntries: Int,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val path = callArena.nativeBytes(pathUtf8)
            val cursor = callArena.nativeBytes(startAfterUtf8)
            variableFilesystemOutput("filesystem list") { output, capacity, written ->
                filesystemListHandle.invokeExact(
                    handle,
                    path,
                    pathUtf8.size.toLong(),
                    cursor,
                    startAfterUtf8.size.toLong(),
                    maximumEntries,
                    output,
                    capacity,
                    written,
                ) as Int
            }
        }

    override fun fileRead(
        handle: Long,
        pathUtf8: ByteArray,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val path = callArena.nativeBytes(pathUtf8)
            variableFilesystemOutput("filesystem read") { output, capacity, written ->
                filesystemReadHandle.invokeExact(
                    handle,
                    path,
                    pathUtf8.size.toLong(),
                    offset,
                    maximumBytes,
                    expectedGeneration,
                    output,
                    capacity,
                    written,
                ) as Int
            }
        }

    override fun verifyForDeploy(
        handle: Long,
        artifact: ByteArray,
    ): Long =
        Arena.ofConfined().use { callArena ->
            val candidateOut = callArena.allocate(ValueLayout.JAVA_LONG)
            when (
                val status =
                    verifyForDeployHandle.invokeExact(
                        handle,
                        callArena.nativeBytes(artifact),
                        artifact.size.toLong(),
                        candidateOut,
                    ) as Int
            ) {
                STATUS_OK -> {
                    candidateOut
                        .get(ValueLayout.JAVA_LONG, 0)
                        .also { if (it == 0L) throw VmBridgeException("native deployment verification returned a zero handle") }
                }

                STATUS_VERIFICATION -> {
                    throw VmVerificationException()
                }

                STATUS_ADMISSION -> {
                    throw VmDeploymentAdmissionException()
                }

                else -> {
                    throw failure("deployment verification", status)
                }
            }
        }

    override fun deploymentCandidateClose(handle: Long) =
        requireSuccess("deployment candidate close", deploymentCandidateCloseHandle.invokeExact(handle) as Int)

    override fun executableRevision(
        handle: Long,
        pathUtf8: ByteArray,
    ): ByteArray =
        deploymentOutput("executable revision") { callArena, output, written ->
            executableRevisionHandle.invokeExact(
                handle,
                callArena.nativeBytes(pathUtf8),
                pathUtf8.size.toLong(),
                output,
                MAXIMUM_EXECUTABLE_REVISION_BYTES.toLong(),
                written,
            ) as Int
        }

    override fun deploy(
        handle: Long,
        candidateHandle: Long,
        pathUtf8: ByteArray,
        expectedKind: Int,
        expectedGeneration: Long,
    ): ByteArray =
        deploymentOutput("deployment") { callArena, output, written ->
            deployHandle.invokeExact(
                handle,
                candidateHandle,
                callArena.nativeBytes(pathUtf8),
                pathUtf8.size.toLong(),
                expectedKind,
                expectedGeneration,
                output,
                MAXIMUM_EXECUTABLE_REVISION_BYTES.toLong(),
                written,
            ) as Int
        }

    override fun submitCanonicalLine(
        handle: Long,
        line: CharArray,
    ) {
        Arena.ofConfined().use { callArena ->
            val status =
                submitCanonicalLineHandle.invokeExact(
                    handle,
                    callArena.nativeChars(line),
                    line.size.toLong(),
                ) as Int
            if (status != STATUS_OK) throw canonicalLineFailure(status)
        }
    }

    override fun submitRedstoneInput(
        handle: Long,
        packet: Int,
    ) = requireSuccess(
        "submit redstone input",
        submitRedstoneInputHandle.invokeExact(handle, packet) as Int,
    )

    override fun confirmRedstoneOutput(
        handle: Long,
        packed: Int,
    ) = requireSuccess(
        "confirm redstone output",
        confirmRedstoneOutputHandle.invokeExact(handle, packed) as Int,
    )

    override fun advance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
        hostRequestBudget: Int,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val maximum = maximumOutcomeBytes
            val output = callArena.allocate(maximum.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val status =
                advanceHandle.invokeExact(
                    handle,
                    guestBudget,
                    maintenanceBudget,
                    hostRequestBudget,
                    output,
                    maximum.toLong(),
                    written,
                ) as Int
            requireSuccess("advance", status)
            copyResult("advance", output, written, maximum)
        }

    override fun compilationRequest(
        handle: Long,
        token: Long,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val requiredOut = callArena.allocate(ValueLayout.JAVA_LONG)
            requireSuccess(
                "compilation request size",
                compilationRequestSizeHandle.invokeExact(handle, token, requiredOut) as Int,
            )
            val required = requiredOut.get(ValueLayout.JAVA_LONG, 0)
            if (required !in 1..MAXIMUM_COMPILATION_REQUEST_BYTES.toLong()) {
                throw VmBridgeException("invalid FFM compilation request size")
            }
            val output = callArena.allocate(required)
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            requireSuccess(
                "compilation request copy",
                compilationRequestCopyHandle.invokeExact(handle, token, output, required, written) as Int,
            )
            val actual = written.get(ValueLayout.JAVA_LONG, 0)
            if (actual != required) throw VmBridgeException("invalid FFM compilation request length")
            output.toArray(ValueLayout.JAVA_BYTE)
        }

    override fun completeCompilationArtifact(
        handle: Long,
        token: Long,
        artifact: ByteArray,
    ) = completeCompilation(handle, token, COMPILATION_ARTIFACT, artifact)

    override fun completeCompilationFailure(
        handle: Long,
        token: Long,
        diagnostics: String,
    ) = completeCompilation(handle, token, COMPILATION_FAILURE, diagnostics.encodeToByteArray())

    override fun resumeUnit(
        handle: Long,
        taskId: Int,
        requestId: Long,
    ) = requireSuccess("resume unit", resumeUnitHandle.invokeExact(handle, taskId, requestId) as Int)

    override fun resumeBool(
        handle: Long,
        taskId: Int,
        requestId: Long,
        value: Boolean,
    ) = requireSuccess(
        "resume bool",
        resumeBoolHandle.invokeExact(handle, taskId, requestId, if (value) 1 else 0) as Int,
    )

    override fun resumeString(
        handle: Long,
        taskId: Int,
        requestId: Long,
        value: CharArray,
    ) {
        Arena.ofConfined().use { callArena ->
            requireSuccess(
                "resume string",
                resumeStringHandle.invokeExact(
                    handle,
                    taskId,
                    requestId,
                    callArena.nativeChars(value),
                    value.size.toLong(),
                ) as Int,
            )
        }
    }

    override fun resumeFailure(
        handle: Long,
        taskId: Int,
        requestId: Long,
        kind: Int,
        code: Long,
    ) = requireSuccess(
        "resume failure",
        resumeFailureHandle.invokeExact(handle, taskId, requestId, kind, code.toInt()) as Int,
    )

    override fun close(handle: Long) = requireSuccess("close", closeHandle.invokeExact(handle) as Int)

    override fun terminalCommit(handle: Long) = requireSuccess("terminal commit", terminalCommitHandle.invokeExact(handle) as Int)

    override fun terminalKey(
        handle: Long,
        key: Int,
        action: Int,
        modifiers: Int,
    ) = requireSuccess(
        "terminal key",
        terminalKeyHandle.invokeExact(handle, key.toShort(), action, modifiers) as Int,
    )

    override fun terminalText(
        handle: Long,
        codePoints: IntArray,
    ) {
        Arena.ofConfined().use { callArena ->
            requireSuccess(
                "terminal text",
                terminalTextHandle.invokeExact(
                    handle,
                    callArena.nativeInts(codePoints),
                    codePoints.size.toLong(),
                ) as Int,
            )
        }
    }

    override fun close() = arena.close()

    private fun completeCompilation(
        handle: Long,
        token: Long,
        kind: Int,
        payload: ByteArray,
    ) {
        Arena.ofConfined().use { callArena ->
            requireSuccess(
                "compilation completion",
                compilationCompleteHandle.invokeExact(
                    handle,
                    token,
                    kind,
                    callArena.nativeBytes(payload),
                    payload.size.toLong(),
                ) as Int,
            )
        }
    }

    private fun maximumCreateBytes(): Int {
        val value = maximumCreateBytesHandle.invokeExact() as Long
        if (value !in 1..MAXIMUM_CREATE_BYTES) throw VmBridgeException("invalid maximum FFM create size")
        return value.toInt()
    }

    private fun copyResult(
        operation: String,
        output: MemorySegment,
        written: MemorySegment,
        maximum: Int,
    ): ByteArray {
        val length = written.get(ValueLayout.JAVA_LONG, 0)
        if (length !in 1..maximum.toLong()) throw VmBridgeException("invalid FFM $operation length")
        return output.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE)
    }

    private inline fun fixedOutput(
        operation: String,
        maximum: Int,
        call: (Arena, MemorySegment, MemorySegment) -> Int,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val output = callArena.allocate(maximum.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            requireSuccess(operation, call(callArena, output, written))
            copyResult(operation, output, written, maximum)
        }

    private inline fun variableFilesystemOutput(
        operation: String,
        call: (MemorySegment, Long, MemorySegment) -> Int,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val sizingStatus = call(MemorySegment.NULL, 0, written)
            if (sizingStatus != STATUS_BUFFER_TOO_SMALL) throw filesystemFailure(operation, sizingStatus)
            val required = written.get(ValueLayout.JAVA_LONG, 0)
            if (required !in 1..MAXIMUM_FILESYSTEM_RESULT_BYTES.toLong()) {
                throw VmBridgeException("invalid FFM $operation required length")
            }
            val output = callArena.allocate(required)
            val status = call(output, required, written)
            if (status != STATUS_OK) throw filesystemFailure(operation, status)
            copyResult(operation, output, written, required.toInt())
        }

    private inline fun deploymentOutput(
        operation: String,
        call: (Arena, MemorySegment, MemorySegment) -> Int,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val output = callArena.allocate(MAXIMUM_EXECUTABLE_REVISION_BYTES.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val status = call(callArena, output, written)
            if (status != STATUS_OK) throw deploymentFailure(operation, status)
            copyResult(operation, output, written, MAXIMUM_EXECUTABLE_REVISION_BYTES)
        }

    private fun deploymentFailure(
        operation: String,
        status: Int,
    ): RuntimeException =
        when (status) {
            STATUS_DEPLOYMENT_CONFLICT -> VmDeploymentConflictException()
            STATUS_DEPLOYMENT_WRONG_MACHINE -> VmDeploymentWrongMachineException()
            STATUS_DEPLOYMENT_PROFILE_CHANGED -> VmDeploymentProfileChangedException()
            STATUS_DEPLOYMENT_FILESYSTEM -> VmDeploymentFileSystemException()
            else -> failure(operation, status)
        }

    private fun canonicalLineFailure(status: Int): RuntimeException =
        when (status) {
            STATUS_INPUT_NO_PENDING_READ -> {
                VmCanonicalLineException(VmCanonicalLineFailure.NO_PENDING_READ)
            }

            STATUS_INPUT_BUSY -> {
                VmCanonicalLineException(VmCanonicalLineFailure.INPUT_BUSY)
            }

            STATUS_INPUT_PARTIAL -> {
                VmCanonicalLineException(VmCanonicalLineFailure.PARTIAL_INPUT)
            }

            STATUS_INPUT_UNSUPPORTED_CODE_UNIT -> {
                VmCanonicalLineException(VmCanonicalLineFailure.UNSUPPORTED_CODE_UNIT)
            }

            STATUS_INPUT_LINE_TOO_LONG -> {
                VmCanonicalLineException(VmCanonicalLineFailure.LINE_TOO_LONG)
            }

            STATUS_INPUT_TERMINAL -> {
                VmCanonicalLineException(VmCanonicalLineFailure.TERMINAL)
            }

            STATUS_INPUT_RESUME -> {
                VmCanonicalLineException(VmCanonicalLineFailure.RESUME)
            }

            else -> {
                failure("canonical line submission", status)
            }
        }

    private fun filesystemFailure(
        operation: String,
        status: Int,
    ): RuntimeException =
        when (status) {
            STATUS_FILESYSTEM_INVALID_PATH -> VmFileSystemReadException(VmFileSystemReadFailure.INVALID_PATH)

            STATUS_FILESYSTEM_NOT_FOUND -> VmFileSystemReadException(VmFileSystemReadFailure.NOT_FOUND)

            STATUS_FILESYSTEM_NOT_DIRECTORY -> VmFileSystemReadException(VmFileSystemReadFailure.NOT_DIRECTORY)

            STATUS_FILESYSTEM_IS_DIRECTORY -> VmFileSystemReadException(VmFileSystemReadFailure.NOT_FILE)

            STATUS_FILESYSTEM_READ_ONLY,
            STATUS_FILESYSTEM_PERMISSION_DENIED,
            -> VmFileSystemReadException(VmFileSystemReadFailure.PERMISSION)

            STATUS_FILESYSTEM_STALE -> VmFileSystemReadException(VmFileSystemReadFailure.STALE_GENERATION)

            STATUS_FILESYSTEM_LIMIT_EXCEEDED -> VmFileSystemReadException(VmFileSystemReadFailure.LIMIT)

            STATUS_FILESYSTEM_BUSY,
            STATUS_FILESYSTEM_FAULTED,
            STATUS_FILESYSTEM_CLOSED,
            STATUS_FILESYSTEM_OTHER,
            STATUS_FILESYSTEM_INVALID_RANGE,
            -> VmFileSystemReadException(VmFileSystemReadFailure.STORAGE)

            else -> failure(operation, status)
        }

    private fun storeIdOperation(
        operation: String,
        method: MethodHandle,
        handle: Long,
        id: ByteArray,
    ) {
        requireComputerId(id)
        Arena.ofConfined().use { callArena ->
            requireSuccess(operation, method.invokeExact(handle, callArena.nativeBytes(id)) as Int)
        }
    }

    private fun requireComputerId(id: ByteArray) {
        require(id.size == 16) { "computer identity must contain exactly 16 bytes" }
    }

    private fun requireSuccess(
        operation: String,
        status: Int,
    ) {
        if (status != STATUS_OK) throw failure(operation, status)
    }

    private fun failure(
        operation: String,
        status: Int,
    ): VmBridgeException = VmBridgeException("FFM $operation failed with status $status")

    private fun Arena.nativeBytes(value: ByteArray): MemorySegment {
        if (value.isEmpty()) return MemorySegment.NULL
        return allocate(ValueLayout.JAVA_BYTE, value.size.toLong()).also { destination ->
            MemorySegment.copy(value, 0, destination, ValueLayout.JAVA_BYTE, 0, value.size)
        }
    }

    private fun Arena.nativeChars(value: CharArray): MemorySegment {
        if (value.isEmpty()) return MemorySegment.NULL
        return allocate(ValueLayout.JAVA_CHAR, value.size.toLong()).also { destination ->
            MemorySegment.copy(value, 0, destination, ValueLayout.JAVA_CHAR, 0, value.size)
        }
    }

    private fun Arena.nativeInts(value: IntArray): MemorySegment {
        if (value.isEmpty()) return MemorySegment.NULL
        return allocate(ValueLayout.JAVA_INT, value.size.toLong()).also { destination ->
            MemorySegment.copy(value, 0, destination, ValueLayout.JAVA_INT, 0, value.size)
        }
    }

    companion object {
        private const val STATUS_OK = 0
        private const val STATUS_BUFFER_TOO_SMALL = 10
        private const val STATUS_VERIFICATION = 2
        private const val STATUS_ADMISSION = 3
        private const val STATUS_DEPLOYMENT_CONFLICT = 19
        private const val STATUS_DEPLOYMENT_WRONG_MACHINE = 20
        private const val STATUS_DEPLOYMENT_PROFILE_CHANGED = 21
        private const val STATUS_DEPLOYMENT_FILESYSTEM = 22
        private const val STATUS_INPUT_NO_PENDING_READ = 23
        private const val STATUS_INPUT_BUSY = 24
        private const val STATUS_INPUT_PARTIAL = 25
        private const val STATUS_INPUT_UNSUPPORTED_CODE_UNIT = 26
        private const val STATUS_INPUT_LINE_TOO_LONG = 27
        private const val STATUS_INPUT_TERMINAL = 28
        private const val STATUS_INPUT_RESUME = 29
        private const val STATUS_FILESYSTEM_INVALID_PATH = 30
        private const val STATUS_FILESYSTEM_NOT_FOUND = 31
        private const val STATUS_FILESYSTEM_NOT_DIRECTORY = 32
        private const val STATUS_FILESYSTEM_IS_DIRECTORY = 33
        private const val STATUS_FILESYSTEM_READ_ONLY = 34
        private const val STATUS_FILESYSTEM_PERMISSION_DENIED = 35
        private const val STATUS_FILESYSTEM_STALE = 36
        private const val STATUS_FILESYSTEM_LIMIT_EXCEEDED = 37
        private const val STATUS_FILESYSTEM_BUSY = 38
        private const val STATUS_FILESYSTEM_FAULTED = 39
        private const val STATUS_FILESYSTEM_CLOSED = 40
        private const val STATUS_FILESYSTEM_OTHER = 41
        private const val STATUS_FILESYSTEM_INVALID_RANGE = 42
        private const val MAXIMUM_CREATE_BYTES = 1024
        private const val MAXIMUM_OUTCOME_BYTES = 1024 * 1024
        private const val MAXIMUM_STORE_OPEN_BYTES = 10
        private const val MAXIMUM_STORE_HEALTH_BYTES = 2
        private const val MAXIMUM_STORE_GENERATION_BYTES = 9
        private const val RESOURCE_SNAPSHOT_BYTES = 138
        private const val MAXIMUM_EXECUTABLE_REVISION_BYTES = 10
        private const val MAXIMUM_COMPILATION_REQUEST_BYTES = 512 * 1024
        private const val MAXIMUM_FILESYSTEM_RESULT_BYTES = 2 * 1024 * 1024
        private const val COMPILATION_ARTIFACT = 0
        private const val COMPILATION_FAILURE = 1

        fun open(library: Path): FfmBridge {
            val arena = Arena.ofShared()
            try {
                val lookup = SymbolLookup.libraryLookup(library, arena)
                val linker = Linker.nativeLinker()

                fun downcall(function: FfmAbiFunction): MethodHandle =
                    linker.downcallHandle(lookup.find(function.symbol).orElseThrow(), function.descriptor)

                return FfmBridge(
                    arena = arena,
                    abiVersionHandle = downcall(FfmAbiFunction.ABI_VERSION),
                    maximumCreateBytesHandle =
                        downcall(FfmAbiFunction.MAXIMUM_CREATE_BYTES),
                    maximumOutcomeBytesHandle =
                        downcall(FfmAbiFunction.MAXIMUM_OUTCOME_BYTES),
                    storeOpenHandle =
                        downcall(FfmAbiFunction.STORE_OPEN),
                    storeHealthHandle =
                        downcall(FfmAbiFunction.STORE_HEALTH),
                    storeDurableGenerationHandle =
                        downcall(FfmAbiFunction.STORE_DURABLE_GENERATION),
                    storeFlushHandle =
                        downcall(FfmAbiFunction.STORE_FLUSH),
                    storeTombstoneHandle =
                        downcall(FfmAbiFunction.STORE_TOMBSTONE),
                    storeRecoverHandle =
                        downcall(FfmAbiFunction.STORE_RECOVER),
                    storeCloseHandle =
                        downcall(FfmAbiFunction.STORE_CLOSE),
                    verifyArtifactHandle =
                        downcall(FfmAbiFunction.VERIFY_ARTIFACT),
                    createHandle =
                        downcall(FfmAbiFunction.CREATE),
                    createInStoreHandle =
                        downcall(FfmAbiFunction.CREATE_IN_STORE),
                    createBootInStoreHandle =
                        downcall(FfmAbiFunction.CREATE_BOOT_IN_STORE),
                    filesystemGenerationHandle =
                        downcall(FfmAbiFunction.FILESYSTEM_GENERATION),
                    resourceSnapshotHandle =
                        downcall(FfmAbiFunction.RESOURCE_SNAPSHOT),
                    filesystemStatHandle =
                        downcall(FfmAbiFunction.FILESYSTEM_STAT),
                    filesystemListHandle =
                        downcall(FfmAbiFunction.FILESYSTEM_LIST),
                    filesystemReadHandle =
                        downcall(FfmAbiFunction.FILESYSTEM_READ),
                    verifyForDeployHandle =
                        downcall(FfmAbiFunction.VERIFY_FOR_DEPLOY),
                    deploymentCandidateCloseHandle =
                        downcall(FfmAbiFunction.DEPLOYMENT_CANDIDATE_CLOSE),
                    executableRevisionHandle =
                        downcall(FfmAbiFunction.EXECUTABLE_REVISION),
                    deployHandle =
                        downcall(FfmAbiFunction.DEPLOY),
                    submitCanonicalLineHandle =
                        downcall(FfmAbiFunction.SUBMIT_CANONICAL_LINE),
                    submitRedstoneInputHandle =
                        downcall(FfmAbiFunction.REDSTONE_SUBMIT_INPUT),
                    confirmRedstoneOutputHandle =
                        downcall(FfmAbiFunction.REDSTONE_CONFIRM_OUTPUT),
                    advanceHandle =
                        downcall(FfmAbiFunction.ADVANCE),
                    compilationRequestSizeHandle =
                        downcall(FfmAbiFunction.COMPILATION_REQUEST_SIZE),
                    compilationRequestCopyHandle =
                        downcall(FfmAbiFunction.COMPILATION_REQUEST_COPY),
                    compilationCompleteHandle =
                        downcall(FfmAbiFunction.COMPILATION_COMPLETE),
                    resumeUnitHandle =
                        downcall(FfmAbiFunction.RESUME_UNIT),
                    resumeBoolHandle =
                        downcall(FfmAbiFunction.RESUME_BOOL),
                    resumeStringHandle =
                        downcall(FfmAbiFunction.RESUME_STRING),
                    resumeFailureHandle =
                        downcall(FfmAbiFunction.RESUME_FAILURE),
                    closeHandle =
                        downcall(FfmAbiFunction.CLOSE),
                    terminalCommitHandle =
                        downcall(FfmAbiFunction.TERMINAL_COMMIT),
                    terminalFullStateHandle =
                        downcall(FfmAbiFunction.TERMINAL_FULL_STATE),
                    terminalChangesSinceHandle =
                        downcall(FfmAbiFunction.TERMINAL_CHANGES_SINCE),
                    terminalKeyHandle =
                        downcall(FfmAbiFunction.TERMINAL_KEY),
                    terminalTextHandle =
                        downcall(FfmAbiFunction.TERMINAL_TEXT),
                ).also { bridge ->
                    if (bridge.abiVersion() != 12) throw VmBridgeException("unsupported Compukter FFM ABI")
                }
            } catch (error: Throwable) {
                arena.close()
                throw error
            }
        }
    }
}
