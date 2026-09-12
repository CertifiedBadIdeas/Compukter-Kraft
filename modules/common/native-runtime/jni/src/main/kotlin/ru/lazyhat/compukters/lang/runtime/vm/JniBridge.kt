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
import java.nio.file.Path

internal class JniBridge private constructor() : LowLevelVmBridge {
    private val maximumOutcomeBytes =
        JniNative
            .maximumOutcomeBytes()
            .takeIf { it in 1..MAXIMUM_OUTCOME_BYTES }
            ?.toInt()
            ?: throw VmBridgeException("invalid maximum JNI outcome size")

    override fun storeOpen(
        rootUtf8: ByteArray,
        limitsWire: ByteArray,
    ): ByteArray =
        fixedOutput("filesystem store open", MAXIMUM_STORE_OPEN_BYTES) { output, written ->
            JniNative.storeOpen(rootUtf8, limitsWire, output, written)
        }

    override fun storeHealth(handle: Long): ByteArray =
        fixedOutput("filesystem store health", MAXIMUM_STORE_HEALTH_BYTES) { output, written ->
            JniNative.storeHealth(handle, output, written)
        }

    override fun storeDurableGeneration(
        handle: Long,
        id: ByteArray,
    ): ByteArray {
        requireComputerId(id)
        return fixedOutput("filesystem durable generation", MAXIMUM_STORE_GENERATION_BYTES) { output, written ->
            JniNative.storeDurableGeneration(handle, id, output, written)
        }
    }

    override fun storeFlush(
        handle: Long,
        id: ByteArray,
        generation: Long,
    ) {
        requireComputerId(id)
        requireSuccess("filesystem flush", JniNative.storeFlush(handle, id, generation))
    }

    override fun storeTombstone(
        handle: Long,
        id: ByteArray,
    ) = storeIdOperation("filesystem tombstone", handle, id, JniNative::storeTombstone)

    override fun storeRecover(
        handle: Long,
        id: ByteArray,
    ) = storeIdOperation("filesystem recovery", handle, id, JniNative::storeRecover)

    override fun storeClose(handle: Long) = requireSuccess("filesystem store close", JniNative.storeClose(handle))

    override fun verifyArtifact(artifact: ByteArray): Boolean =
        when (val status = JniNative.verifyArtifact(artifact)) {
            STATUS_OK -> true
            STATUS_VERIFICATION -> false
            else -> throw failure("artifact verification", status)
        }

    override fun create(artifact: ByteArray): ByteArray {
        val maximum = maximumCreateBytes()
        return fixedOutput("create", maximum) { output, written ->
            JniNative.create(artifact, output, written)
        }
    }

    override fun createInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
        artifact: ByteArray,
    ): ByteArray {
        requireComputerId(id)
        val maximum = maximumCreateBytes()
        return fixedOutput("create in filesystem store", maximum) { output, written ->
            JniNative.createInStore(storeHandle, id, rom, artifact, output, written)
        }
    }

    override fun createBootInStore(
        storeHandle: Long,
        id: ByteArray,
        rom: ByteArray,
    ): ByteArray {
        requireComputerId(id)
        val maximum = maximumCreateBytes()
        return fixedOutput("boot in filesystem store", maximum) { output, written ->
            JniNative.createBootInStore(storeHandle, id, rom, output, written)
        }
    }

    override fun filesystemGeneration(handle: Long): ByteArray =
        fixedOutput("filesystem generation", MAXIMUM_STORE_GENERATION_BYTES) { output, written ->
            JniNative.filesystemGeneration(handle, output, written)
        }

    override fun resourceSnapshot(handle: Long): ByteArray =
        fixedOutput("resource snapshot", RESOURCE_SNAPSHOT_BYTES) { output, written ->
            JniNative.resourceSnapshot(handle, output, written)
        }

    override fun fileStat(
        handle: Long,
        pathUtf8: ByteArray,
    ): ByteArray =
        variableFilesystemOutput("filesystem stat") { output, written ->
            JniNative.filesystemStat(handle, pathUtf8, output, written)
        }

    override fun fileList(
        handle: Long,
        pathUtf8: ByteArray,
        startAfterUtf8: ByteArray,
        maximumEntries: Int,
    ): ByteArray =
        variableFilesystemOutput("filesystem list") { output, written ->
            JniNative.filesystemList(handle, pathUtf8, startAfterUtf8, maximumEntries, output, written)
        }

    override fun fileRead(
        handle: Long,
        pathUtf8: ByteArray,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): ByteArray =
        variableFilesystemOutput("filesystem read") { output, written ->
            JniNative.filesystemRead(handle, pathUtf8, offset, maximumBytes, expectedGeneration, output, written)
        }

    override fun verifyForDeploy(
        handle: Long,
        artifact: ByteArray,
    ): Long {
        val candidate = LongArray(1)
        return when (val status = JniNative.verifyForDeploy(handle, artifact, candidate)) {
            STATUS_OK -> {
                candidate.single().also {
                    if (it == 0L) throw VmBridgeException("native deployment verification returned a zero handle")
                }
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
        requireSuccess("deployment candidate close", JniNative.deploymentCandidateClose(handle))

    override fun executableRevision(
        handle: Long,
        pathUtf8: ByteArray,
    ): ByteArray =
        deploymentOutput("executable revision") { output, written ->
            JniNative.executableRevision(handle, pathUtf8, output, written)
        }

    override fun deploy(
        handle: Long,
        candidateHandle: Long,
        pathUtf8: ByteArray,
        expectedKind: Int,
        expectedGeneration: Long,
    ): ByteArray =
        deploymentOutput("deployment") { output, written ->
            JniNative.deploy(handle, candidateHandle, pathUtf8, expectedKind, expectedGeneration, output, written)
        }

    override fun submitCanonicalLine(
        handle: Long,
        line: CharArray,
    ) {
        val status = JniNative.submitCanonicalLine(handle, line)
        if (status != STATUS_OK) throw canonicalLineFailure(status)
    }

    override fun submitRedstoneInput(
        handle: Long,
        packet: Int,
    ) = requireSuccess("submit redstone input", JniNative.submitRedstoneInput(handle, packet))

    override fun confirmRedstoneOutput(
        handle: Long,
        packed: Int,
    ) = requireSuccess("confirm redstone output", JniNative.confirmRedstoneOutput(handle, packed))

    override fun advance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
        hostRequestBudget: Int,
    ): ByteArray =
        fixedOutput("advance", maximumOutcomeBytes) { output, written ->
            JniNative.advance(handle, guestBudget, maintenanceBudget, hostRequestBudget, output, written)
        }

    override fun compilationRequest(
        handle: Long,
        token: Long,
    ): ByteArray {
        val required = LongArray(1)
        requireSuccess("compilation request size", JniNative.compilationRequestSize(handle, token, required))
        val size = required.single()
        if (size !in 1..MAXIMUM_COMPILATION_REQUEST_BYTES.toLong()) {
            throw VmBridgeException("invalid JNI compilation request size")
        }
        val output = ByteArray(size.toInt())
        val written = LongArray(1)
        requireSuccess("compilation request copy", JniNative.compilationRequestCopy(handle, token, output, written))
        if (written.single() != size) throw VmBridgeException("invalid JNI compilation request length")
        return output
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
    ) = requireSuccess("resume unit", JniNative.resumeUnit(handle, taskId, requestId))

    override fun resumeBool(
        handle: Long,
        taskId: Int,
        requestId: Long,
        value: Boolean,
    ) = requireSuccess("resume bool", JniNative.resumeBool(handle, taskId, requestId, value))

    override fun resumeString(
        handle: Long,
        taskId: Int,
        requestId: Long,
        value: CharArray,
    ) = requireSuccess("resume string", JniNative.resumeString(handle, taskId, requestId, value))

    override fun resumeFailure(
        handle: Long,
        taskId: Int,
        requestId: Long,
        kind: Int,
        code: Long,
    ) = requireSuccess("resume failure", JniNative.resumeFailure(handle, taskId, requestId, kind, code.toInt()))

    override fun close(handle: Long) = requireSuccess("close", JniNative.close(handle))

    override fun terminalCommit(handle: Long) = requireSuccess("terminal commit", JniNative.terminalCommit(handle))

    override fun terminalFullState(handle: Long): ByteArray =
        fixedOutput("terminal full state", maximumOutcomeBytes) { output, written ->
            JniNative.terminalFullState(handle, output, written)
        }

    override fun terminalChangesSince(
        handle: Long,
        revision: Long,
    ): ByteArray =
        fixedOutput("terminal changes", maximumOutcomeBytes) { output, written ->
            JniNative.terminalChangesSince(handle, revision, output, written)
        }

    override fun terminalKey(
        handle: Long,
        key: Int,
        action: Int,
        modifiers: Int,
    ) = requireSuccess("terminal key", JniNative.terminalKey(handle, key, action, modifiers))

    override fun terminalText(
        handle: Long,
        codePoints: IntArray,
    ) = requireSuccess("terminal text", JniNative.terminalText(handle, codePoints))

    private fun maximumCreateBytes(): Int =
        JniNative
            .maximumCreateBytes()
            .takeIf { it in 1..MAXIMUM_CREATE_BYTES }
            ?.toInt()
            ?: throw VmBridgeException("invalid maximum JNI create size")

    private fun fixedOutput(
        operation: String,
        maximum: Int,
        call: (ByteArray, LongArray) -> Int,
    ): ByteArray {
        val output = ByteArray(maximum)
        val written = LongArray(1)
        requireSuccess(operation, call(output, written))
        return copyResult(operation, output, written.single(), maximum)
    }

    private fun variableFilesystemOutput(
        operation: String,
        call: (ByteArray, LongArray) -> Int,
    ): ByteArray {
        val required = LongArray(1)
        val sizingStatus = call(ByteArray(0), required)
        if (sizingStatus != STATUS_BUFFER_TOO_SMALL) throw filesystemFailure(operation, sizingStatus)
        val size = required.single()
        if (size !in 1..MAXIMUM_FILESYSTEM_RESULT_BYTES.toLong()) {
            throw VmBridgeException("invalid JNI $operation required length")
        }
        val output = ByteArray(size.toInt())
        val written = LongArray(1)
        val status = call(output, written)
        if (status != STATUS_OK) throw filesystemFailure(operation, status)
        return copyResult(operation, output, written.single(), output.size)
    }

    private fun deploymentOutput(
        operation: String,
        call: (ByteArray, LongArray) -> Int,
    ): ByteArray {
        val output = ByteArray(MAXIMUM_EXECUTABLE_REVISION_BYTES)
        val written = LongArray(1)
        val status = call(output, written)
        if (status != STATUS_OK) throw deploymentFailure(operation, status)
        return copyResult(operation, output, written.single(), output.size)
    }

    private fun copyResult(
        operation: String,
        output: ByteArray,
        written: Long,
        maximum: Int,
    ): ByteArray {
        if (written !in 1..maximum.toLong()) throw VmBridgeException("invalid JNI $operation length")
        return output.copyOf(written.toInt())
    }

    private fun completeCompilation(
        handle: Long,
        token: Long,
        kind: Int,
        payload: ByteArray,
    ) = requireSuccess("compilation completion", JniNative.compilationComplete(handle, token, kind, payload))

    private fun storeIdOperation(
        operation: String,
        handle: Long,
        id: ByteArray,
        call: (Long, ByteArray) -> Int,
    ) {
        requireComputerId(id)
        requireSuccess(operation, call(handle, id))
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
            STATUS_INPUT_NO_PENDING_READ -> VmCanonicalLineException(VmCanonicalLineFailure.NO_PENDING_READ)
            STATUS_INPUT_BUSY -> VmCanonicalLineException(VmCanonicalLineFailure.INPUT_BUSY)
            STATUS_INPUT_PARTIAL -> VmCanonicalLineException(VmCanonicalLineFailure.PARTIAL_INPUT)
            STATUS_INPUT_UNSUPPORTED_CODE_UNIT -> VmCanonicalLineException(VmCanonicalLineFailure.UNSUPPORTED_CODE_UNIT)
            STATUS_INPUT_LINE_TOO_LONG -> VmCanonicalLineException(VmCanonicalLineFailure.LINE_TOO_LONG)
            STATUS_INPUT_TERMINAL -> VmCanonicalLineException(VmCanonicalLineFailure.TERMINAL)
            STATUS_INPUT_RESUME -> VmCanonicalLineException(VmCanonicalLineFailure.RESUME)
            else -> failure("canonical line submission", status)
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

    private fun failure(
        operation: String,
        status: Int,
    ): VmBridgeException = VmBridgeException("JNI $operation failed with status $status")

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

        fun open(library: Path): JniBridge {
            System.load(library.toAbsolutePath().normalize().toString())
            if (JniNative.abiVersion() != 12) throw VmBridgeException("unsupported Compukter JNI ABI")
            return JniBridge()
        }
    }
}
