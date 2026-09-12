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

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryEntry
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileKind
import ru.lazyhat.compukters.lang.runtime.fs.VmFileMetadata
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadException
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class VmSession private constructor(
    handle: Long,
    private val bridge: LowLevelVmBridge,
    private val terminalTransport: TerminalWireTransport,
) : AutoCloseable {
    private val handle = AtomicLong(handle)
    private val deploymentOwner = Any()

    fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
        hostRequestBudget: Int,
    ): VmOutcome {
        require(guestBudget >= 0 && maintenanceBudget >= 0 && hostRequestBudget >= 0) {
            "VM budgets must be non-negative"
        }
        val activeHandle = requireHandle()
        return decodeNative {
            WireDecoder(
                bridge.advance(activeHandle, guestBudget, maintenanceBudget, hostRequestBudget),
            ).outcome { token ->
                val request = CompilationWireDecoder(bridge.compilationRequest(activeHandle, token)).request()
                require(request.token == token) { "native compilation token mismatch" }
                VmOutcome.CompilationRequested(request)
            }
        }
    }

    fun completeCompilationArtifact(
        token: Long,
        artifact: ByteArray,
    ) {
        require(token > 0) { "compilation token must be positive" }
        bridge.completeCompilationArtifact(requireHandle(), token, artifact)
    }

    fun completeCompilationFailure(
        token: Long,
        diagnostics: String,
    ) {
        require(token > 0) { "compilation token must be positive" }
        bridge.completeCompilationFailure(requireHandle(), token, diagnostics)
    }

    fun resume(
        identity: VmHostRequestIdentity,
        response: HostResponse,
    ) {
        when (response) {
            HostResponse.UnitSuccess -> resumeUnit(identity)
            is HostResponse.BoolSuccess -> resumeBool(identity, response.value)
            is HostResponse.StringSuccess -> resumeString(identity, response.value)
            is HostResponse.Failure -> resumeFailure(identity, response.kind, response.code)
        }
    }

    fun resume(
        requestId: Long,
        response: HostResponse,
    ) = resume(VmHostRequestIdentity(1, requestId), response)

    fun resumeUnit(identity: VmHostRequestIdentity) = bridge.resumeUnit(requireHandle(), identity.taskId, identity.requestId)

    fun resumeUnit(requestId: Long) = resumeUnit(VmHostRequestIdentity(1, requestId))

    fun resumeBool(
        identity: VmHostRequestIdentity,
        value: Boolean,
    ) = bridge.resumeBool(requireHandle(), identity.taskId, identity.requestId, value)

    fun resumeBool(
        requestId: Long,
        value: Boolean,
    ) = resumeBool(VmHostRequestIdentity(1, requestId), value)

    fun resumeString(
        identity: VmHostRequestIdentity,
        value: String,
    ) = bridge.resumeString(requireHandle(), identity.taskId, identity.requestId, value.toCharArray())

    fun resumeString(
        requestId: Long,
        value: String,
    ) = resumeString(VmHostRequestIdentity(1, requestId), value)

    fun resumeFailure(
        identity: VmHostRequestIdentity,
        kind: HostFailureKind,
        code: Long,
    ) {
        require(code in 0..UInt.MAX_VALUE.toLong()) { "host failure code must fit u32" }
        bridge.resumeFailure(requireHandle(), identity.taskId, identity.requestId, kind.wireCode, code)
    }

    fun resumeFailure(
        requestId: Long,
        kind: HostFailureKind,
        code: Long,
    ) = resumeFailure(VmHostRequestIdentity(1, requestId), kind, code)

    fun commitTerminal(): Unit = bridge.terminalCommit(requireHandle())

    fun terminalFullState(): TerminalState = decodeNative { terminalTransport.fullState(requireHandle()) }

    fun terminalChangesSince(revision: Long): TerminalUpdate {
        require(revision >= 0) { "terminal revision must not be negative" }
        return decodeNative { terminalTransport.changesSince(requireHandle(), revision) }
    }

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): Unit =
        bridge.terminalKey(
            requireHandle(),
            key.wireCode,
            action.wireCode,
            modifiers.fold(0) { bits, modifier -> bits or modifier.mask },
        )

    fun sendTerminalText(value: String): Unit = bridge.terminalText(requireHandle(), value.codePoints().toArray())

    fun filesystemGeneration(): Long = decodeNative { GenerationWireDecoder(bridge.filesystemGeneration(requireHandle())).generation() }

    fun resourceSnapshot(): VmResourceSnapshot =
        decodeNative { ResourceSnapshotWireDecoder(bridge.resourceSnapshot(requireHandle())).snapshot() }

    fun fileStat(path: VmVirtualPath): VmFileStat =
        decodeNative {
            FileInspectionWireDecoder(bridge.fileStat(requireHandle(), path.value.encodeToByteArray())).stat()
        }

    fun fileList(
        path: VmVirtualPath,
        startAfter: String? = null,
        maximumEntries: Int = 128,
    ): VmDirectoryListing {
        require(maximumEntries in 1..256) { "filesystem list page must contain 1..256 entries" }
        val cursor = startAfter?.also(::requireFileName)?.encodeToByteArray() ?: ByteArray(0)
        return decodeNative {
            FileInspectionWireDecoder(
                bridge.fileList(requireHandle(), path.value.encodeToByteArray(), cursor, maximumEntries),
            ).listing()
        }
    }

    fun fileRead(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): VmFileChunk {
        require(offset >= 0) { "filesystem read offset must not be negative" }
        require(maximumBytes in 1..32 * 1024) { "filesystem read must request 1..32768 bytes" }
        require(expectedGeneration >= 0) { "filesystem generation must not be negative" }
        return decodeNative {
            FileInspectionWireDecoder(
                bridge.fileRead(
                    requireHandle(),
                    path.value.encodeToByteArray(),
                    offset,
                    maximumBytes,
                    expectedGeneration,
                ),
            ).chunk()
        }
    }

    fun verifyForDeploy(artifact: ByteArray): VmDeploymentCandidate {
        val candidateHandle = bridge.verifyForDeploy(requireHandle(), artifact.copyOf())
        return VmDeploymentCandidate(candidateHandle, bridge, deploymentOwner)
    }

    fun executableRevision(path: String): VmExecutableRevision =
        decodeNative {
            ExecutableRevisionWireDecoder(bridge.executableRevision(requireHandle(), path.encodeToByteArray())).revision()
        }

    fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: VmDeploymentCandidate,
    ): VmExecutableRevision {
        val activeHandle = requireHandle()
        val pathUtf8 = path.encodeToByteArray()
        val (expectedKind, expectedGeneration) =
            when (expected) {
                VmExecutableRevision.Absent -> 0 to 0L
                is VmExecutableRevision.Present -> 1 to expected.generation
            }
        val result =
            candidate.consume(deploymentOwner) { candidateHandle ->
                bridge.deploy(activeHandle, candidateHandle, pathUtf8, expectedKind, expectedGeneration)
            }
        return decodeNative { ExecutableRevisionWireDecoder(result).revision() }
    }

    fun submitCanonicalLine(line: CharArray): Unit = bridge.submitCanonicalLine(requireHandle(), line.copyOf())

    fun submitRedstoneInput(packet: Int): Unit = bridge.submitRedstoneInput(requireHandle(), RedstoneWire.requireInputPacket(packet))

    fun confirmRedstoneOutput(packed: Int): Unit = bridge.confirmRedstoneOutput(requireHandle(), RedstoneWire.requireOutputRegister(packed))

    override fun close() {
        val closing = handle.getAndSet(CLOSED)
        if (closing != CLOSED) {
            try {
                terminalTransport.close()
            } finally {
                bridge.close(closing)
            }
        }
    }

    private fun requireHandle(): Long = handle.get().takeIf { it != CLOSED } ?: error("VM session is closed")

    private fun requireFileName(value: String) {
        require(value.isNotEmpty() && value != "." && value != "..") { "filesystem cursor is not a file name" }
        require(value.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            "filesystem cursor contains a forbidden character"
        }
        require(value.encodeToByteArray().size <= 4 * 1024) { "filesystem cursor is too long" }
    }

    companion object {
        private const val CLOSED = 0L

        fun open(artifact: ByteArray): VmSession = open(artifact, VmRuntime.bridge())

        fun openInStore(
            artifact: ByteArray,
            store: WorldFileSystemStore,
            id: ComputerId,
            romImage: ByteArray,
        ): VmSession {
            val (bridge, result) = store.createMachine(id, romImage.copyOf(), artifact.copyOf())
            val handle = decodeNative { WireDecoder(result).createdHandle() }
            return admitted(handle, bridge)
        }

        fun bootInStore(
            store: WorldFileSystemStore,
            id: ComputerId,
            romImage: ByteArray,
        ): VmSession {
            val (bridge, result) = store.createBootMachine(id, romImage.copyOf())
            val handle = decodeNative { WireDecoder(result).createdHandle() }
            return admitted(handle, bridge)
        }

        internal fun open(
            artifact: ByteArray,
            bridge: LowLevelVmBridge,
        ): VmSession {
            val handle = decodeNative { WireDecoder(bridge.create(artifact.copyOf())).createdHandle() }
            return admitted(handle, bridge)
        }

        private fun admitted(
            handle: Long,
            bridge: LowLevelVmBridge,
        ): VmSession =
            try {
                VmSession(handle, bridge, bridge.openTerminalTransport())
            } catch (error: Throwable) {
                try {
                    bridge.close(handle)
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
                throw error
            }

        private inline fun <T> decodeNative(block: () -> T): T =
            try {
                block()
            } catch (error: VmVerificationException) {
                throw error
            } catch (error: VmAdmissionException) {
                throw error
            } catch (error: VmStartException) {
                throw error
            } catch (error: VmBootException) {
                throw error
            } catch (error: VmBridgeException) {
                throw error
            } catch (error: VmFileSystemReadException) {
                throw error
            } catch (error: Exception) {
                throw VmBridgeException("invalid native VM result", error)
            }
    }
}

class VmDeploymentCandidate internal constructor(
    handle: Long,
    private val bridge: LowLevelVmBridge,
    private val owner: Any,
) : AutoCloseable {
    private var handle = handle.also { require(it != CLOSED) { "deployment candidate handle must not be zero" } }

    internal fun <T> consume(
        expectedOwner: Any,
        action: (Long) -> T,
    ): T =
        synchronized(this) {
            require(owner === expectedOwner) { "deployment candidate belongs to another VM session" }
            check(handle != CLOSED) { "deployment candidate is closed or consumed" }
            action(handle).also { handle = CLOSED }
        }

    override fun close() {
        synchronized(this) {
            if (handle != CLOSED) {
                bridge.deploymentCandidateClose(handle)
                handle = CLOSED
            }
        }
    }

    private companion object {
        const val CLOSED = 0L
    }
}

private class FileInspectionWireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun stat(): VmFileStat {
        version()
        val kind = kind()
        val executable = boolean()
        require(u8() == 0) { "filesystem stat reserved byte is not zero" }
        val metadata = VmFileMetadata(kind, nonNegativeLong("file size"), nonNegativeLong("node generation"), executable)
        val result = VmFileStat(nonNegativeLong("filesystem generation"), metadata)
        end()
        return result
    }

    fun listing(): VmDirectoryListing {
        version()
        val fileSystemGeneration = nonNegativeLong("filesystem generation")
        val directoryGeneration = nonNegativeLong("directory generation")
        val complete = boolean()
        val count = u32Count(256)
        var previous: ByteArray? = null
        val entries =
            List(count) {
                val nameBytes = shortBytes()
                require(nameBytes.isNotEmpty()) { "filesystem entry name is empty" }
                previous?.let { require(compareUnsigned(it, nameBytes) < 0) { "filesystem names are not strictly ordered" } }
                previous = nameBytes
                VmDirectoryEntry(strictUtf8(nameBytes), metadata())
            }
        end()
        return VmDirectoryListing(fileSystemGeneration, directoryGeneration, complete, entries)
    }

    fun chunk(): VmFileChunk {
        version()
        val generation = nonNegativeLong("node generation")
        val nextOffset = nonNegativeLong("next file offset")
        val eof = boolean()
        val length = u32Count(32 * 1024)
        require(length == buffer.remaining()) { "filesystem chunk byte count is not exact" }
        val content = ByteArray(length).also(buffer::get)
        end()
        return VmFileChunk(generation, nextOffset, eof, content)
    }

    private fun metadata(): VmFileMetadata =
        VmFileMetadata(
            kind = kind(),
            executable = boolean(),
            logicalBytes = nonNegativeLong("file size"),
            generation = nonNegativeLong("node generation"),
        )

    private fun version() = require(u8() == 1) { "unsupported filesystem inspection wire version" }

    private fun kind(): VmFileKind =
        when (u8()) {
            0 -> VmFileKind.FILE
            1 -> VmFileKind.DIRECTORY
            else -> error("invalid filesystem file kind")
        }

    private fun boolean(): Boolean =
        when (u8()) {
            0 -> false
            1 -> true
            else -> error("invalid filesystem boolean")
        }

    private fun shortBytes(): ByteArray {
        val length = u16()
        require(length <= buffer.remaining()) { "filesystem name length exceeds its result" }
        return ByteArray(length).also(buffer::get)
    }

    private fun strictUtf8(value: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()

    private fun nonNegativeLong(name: String): Long = buffer.long.also { require(it >= 0) { "$name exceeds the JVM range" } }

    private fun u32Count(maximum: Int): Int {
        val value = buffer.int.toLong() and 0xffff_ffffL
        require(value <= maximum) { "filesystem result count exceeds its bound" }
        return value.toInt()
    }

    private fun u8(): Int = buffer.get().toInt() and 0xff

    private fun u16(): Int = buffer.short.toInt() and 0xffff

    private fun end() = require(!buffer.hasRemaining()) { "filesystem inspection result contains trailing bytes" }

    private fun compareUnsigned(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return left.size - right.size
    }
}

private class GenerationWireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun generation(): Long {
        require(buffer.get().toInt() and 0xff == 1) { "unsupported filesystem generation wire version" }
        val generation = buffer.long
        require(generation >= 0) { "native filesystem generation exceeds the JVM range" }
        require(!buffer.hasRemaining()) { "native filesystem generation contains trailing bytes" }
        return generation
    }
}

private class ResourceSnapshotWireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun snapshot(): VmResourceSnapshot {
        require(u8() == 2) { "unsupported resource snapshot wire version" }
        val flags = u8()
        require(flags and 0xfe == 0) { "invalid resource snapshot flags" }
        val snapshot =
            VmResourceSnapshot(
                fixedGuestUnits = nonNegativeLong(),
                dynamicGuestUnits = nonNegativeLong(),
                maintenanceUnits = nonNegativeLong(),
                enteredBlocks = nonNegativeLong(),
                executedInstructions = nonNegativeLong(),
                heapCapacityBytes = nonNegativeLong(),
                heapUsedBytes = nonNegativeLong(),
                liveObjects = nonNegativeLong(),
                mutableExecutionResidentBytes = nonNegativeLong(),
                taskCapacity = nonNegativeLong(),
                liveTasks = nonNegativeLong(),
                runnableTasks = nonNegativeLong(),
                suspendedTasks = nonNegativeLong(),
                completedTasks = nonNegativeLong(),
                filesystemLogicalBytes = nonNegativeLong(),
                filesystemLogicalCapacityBytes = nonNegativeLong(),
                filesystemNodes = unsignedInt(),
                filesystemNodeCapacity = unsignedInt(),
                countersSaturated = flags and 1 != 0,
            )
        require(!buffer.hasRemaining()) { "resource snapshot contains trailing bytes" }
        return snapshot
    }

    private fun nonNegativeLong(): Long = buffer.long.also { require(it >= 0) { "resource snapshot value exceeds JVM range" } }

    private fun unsignedInt(): Long = buffer.int.toLong() and 0xffff_ffffL

    private fun u8(): Int = buffer.get().toInt() and 0xff
}

private class ExecutableRevisionWireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun revision(): VmExecutableRevision {
        require(buffer.get().toInt() and 0xff == 1) { "unsupported executable revision wire version" }
        val revision =
            when (buffer.get().toInt() and 0xff) {
                0 -> VmExecutableRevision.Absent
                1 -> VmExecutableRevision.Present(buffer.long.also { require(it >= 0) })
                else -> error("invalid executable revision wire kind")
            }
        require(!buffer.hasRemaining()) { "executable revision contains trailing bytes" }
        return revision
    }
}

private class WireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun createdHandle(): Long =
        when (u8()) {
            0 -> i64().also { require(it != 0L) { "native VM returned a zero handle" } }
            1 -> throw VmVerificationException()
            2 -> throw VmAdmissionException(u16())
            3 -> throw VmStartException(u16())
            4 -> throw VmBridgeException("native VM handle allocation failed with code ${u8()}")
            5 -> throw VmBootException(u8())
            else -> invalid()
        }.also { end() }

    fun outcome(compilation: (Long) -> VmOutcome): VmOutcome =
        when (u8()) {
            0 -> VmOutcome.SliceExhausted
            1 -> hostRequestBatch()
            2 -> VmOutcome.AllocationExhausted(boolean())
            3 -> VmOutcome.QuotaExhausted(quotaKind(u8()), i64(), i64())
            4 -> VmOutcome.Halted(optionalValue())
            5 -> VmOutcome.Crashed(guestTrap(u8()))
            6 -> VmOutcome.Faulted(vmFault(u8()))
            7 -> VmOutcome.HostFailed(hostFailureKind(u8()), u32())
            9 -> VmOutcome.WaitingForTerminalEvent
            10 -> compilation(i64().also { require(it > 0) { "invalid native compilation token" } })
            11 -> VmOutcome.WaitingForHostQuota
            else -> invalid()
        }.also { end() }

    private fun hostRequestBatch(): VmOutcome.HostRequestBatch {
        val count = i32().boundedCount().also { require(it > 0) { "empty native host request batch" } }
        val requests = List(count) { request() }
        require(requests.map(VmHostRequest::identity).toSet().size == requests.size) {
            "duplicate native host request identity"
        }
        return VmOutcome.HostRequestBatch(requests)
    }

    private fun request(): VmHostRequest =
        VmHostRequest(
            taskId = i32().also { require(it > 0) { "invalid native task id" } },
            id = i64().also { require(it > 0) { "invalid native request id" } },
            capability = CapabilityIdentity(text(), text(), u16(), u16()),
            operation = i32(),
            arguments = List(i32().boundedCount()) { value() },
            merge = merge(),
        )

    private fun merge(): VmHostMerge =
        when (u8()) {
            0 -> {
                VmHostMerge.Ordinary
            }

            1 -> {
                VmHostMerge.LastWriteWins(
                    groupBits = i32(),
                    entries =
                        List(i32().boundedCount().also { require(it > 0) { "empty native merge entries" } }) {
                            VmHostMergeEntry(i32(), i32())
                        },
                )
            }

            else -> {
                invalid()
            }
        }

    private fun value(): VmValue =
        when (u8()) {
            1 -> VmValue.I32(i32())
            2 -> VmValue.I64(i64())
            3 -> VmValue.F32(i32())
            4 -> VmValue.F64(i64())
            5 -> VmValue.Bool(boolean())
            6 -> VmValue.CharValue(u16().toChar())
            7 -> VmValue.StringValue(String(CharArray(i32().boundedCount()) { u16().toChar() }))
            else -> invalid()
        }

    private fun text(): String {
        val bytes = ByteArray(i32().boundedCount())
        buffer.get(bytes)
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun Int.boundedCount(): Int = also { require(it >= 0 && it <= buffer.remaining()) { "invalid native VM length" } }

    private fun u8(): Int = buffer.get().toInt() and 0xff

    private fun u16(): Int = buffer.short.toInt() and 0xffff

    private fun i32(): Int = buffer.int

    private fun u32(): Long = buffer.int.toLong() and 0xffff_ffffL

    private fun i64(): Long = buffer.long

    private fun optionalValue(): VmValue? =
        when (u8()) {
            0 -> null
            1 -> value()
            else -> invalid()
        }

    private fun boolean(): Boolean =
        when (u8()) {
            0 -> false
            1 -> true
            else -> invalid()
        }

    private fun guestTrap(code: Int): GuestTrap = GuestTrap.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun vmFault(code: Int): VmFault = VmFault.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun quotaKind(code: Int): QuotaKind = QuotaKind.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun hostFailureKind(code: Int): HostFailureKind = HostFailureKind.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun end() = require(!buffer.hasRemaining()) { "native VM result contains trailing bytes" }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid native VM result")
}

private class CompilationWireDecoder(
    private val bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun request(): VmCompilationRequest {
        require(u16() == VERSION) { "unsupported compilation request wire version" }
        val token = i64().also { require(it > 0) { "invalid compilation request token" } }
        val sourceCount = count(MAXIMUM_SOURCES)
        require(sourceCount > 0) { "compilation request contains no sources" }
        val sources =
            List(sourceCount) {
                val path = strictUtf8(byteArray())
                require(path.startsWith('/')) { "compilation source path is not absolute" }
                val source = byteArray()
                strictUtf8(source)
                VmCompilationSource(path, source)
            }
        require(sources.zipWithNext().all { (left, right) -> left.path < right.path }) {
            "compilation source paths are not strictly ordered"
        }
        require(!buffer.hasRemaining()) { "compilation request contains trailing bytes" }
        return VmCompilationRequest(token, sources)
    }

    private fun byteArray(): ByteArray = ByteArray(count(buffer.remaining())).also(buffer::get)

    private fun strictUtf8(value: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()

    private fun count(maximum: Int): Int =
        buffer.int.also { require(it in 0..maximum && it <= buffer.remaining()) { "invalid compilation request length" } }

    private fun u16(): Int = buffer.short.toInt() and 0xffff

    private fun i64(): Long = buffer.long

    private companion object {
        const val VERSION = 1
        const val MAXIMUM_SOURCES = 64
    }
}

internal class TerminalWireDecoder(
    private val buffer: ByteBuffer,
) {
    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    fun fullState(): TerminalState {
        require(u8() == 2) { "native terminal result is not a full state" }
        return state().also { end() }
    }

    fun update(): TerminalUpdate =
        when (u8()) {
            0 -> {
                TerminalUpdate.Unchanged(revision())
            }

            1 -> {
                val base = revision()
                val target = revision()
                require(target > base) { "invalid terminal delta revisions" }
                TerminalUpdate.Delta(base, target, List(count(MAX_CHANGES)) { change() })
            }

            2 -> {
                TerminalUpdate.Full(state())
            }

            else -> {
                invalid()
            }
        }.also { end() }

    private fun state(): TerminalState {
        val revision = revision()
        val width = u16()
        val height = u16()
        require(width == WIDTH && height == HEIGHT) { "unsupported terminal dimensions" }
        val cells = List(count(CELL_COUNT)) { cell() }
        require(cells.size == CELL_COUNT) { "invalid terminal cell count" }
        return TerminalState(revision, width, height, cells, position(), boolean())
    }

    private fun change(): TerminalChange =
        when (u8()) {
            0 -> {
                val start = u16()
                val cells = List(u16()) { cell() }
                require(cells.isNotEmpty() && start + cells.size <= CELL_COUNT) { "invalid terminal patch" }
                TerminalChange.Patch(start, cells)
            }

            1 -> {
                val x = u16()
                val y = u16()
                val width = u16()
                val height = u16()
                require(width > 0 && height > 0 && x + width <= WIDTH && y + height <= HEIGHT) {
                    "invalid terminal fill"
                }
                TerminalChange.Fill(x, y, width, height, cell())
            }

            2 -> {
                val rows = u16()
                require(rows in 1..HEIGHT) { "invalid terminal scroll" }
                TerminalChange.Scroll(rows, cell())
            }

            3 -> {
                TerminalChange.Cursor(position(), boolean())
            }

            4 -> {
                TerminalChange.Reset
            }

            else -> {
                invalid()
            }
        }

    private fun cell(): TerminalCell {
        val codePoint = buffer.int
        val foreground = u8()
        val background = u8()
        require(
            Character.isValidCodePoint(codePoint) && codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code,
        ) { "invalid terminal Unicode scalar" }
        require(foreground in 0 until PALETTE_SIZE && background in 0 until PALETTE_SIZE) {
            "invalid terminal palette index"
        }
        return TerminalCell(codePoint, foreground, background)
    }

    private fun position(): TerminalPosition {
        val x = u16()
        val y = u16()
        require(x in 0 until WIDTH && y in 0 until HEIGHT) { "invalid terminal cursor" }
        return TerminalPosition(x, y)
    }

    private fun revision(): Long = buffer.long.also { require(it >= 0) { "terminal revision exceeds JVM range" } }

    private fun count(maximum: Int): Int = buffer.int.also { require(it in 0..maximum) { "invalid terminal count" } }

    private fun u8(): Int = buffer.get().toInt() and 0xff

    private fun u16(): Int = buffer.short.toInt() and 0xffff

    private fun boolean(): Boolean =
        when (u8()) {
            0 -> false
            1 -> true
            else -> invalid()
        }

    private fun end() = require(!buffer.hasRemaining()) { "trailing terminal wire bytes" }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid terminal wire result")

    private companion object {
        const val WIDTH = 51
        const val HEIGHT = 19
        const val CELL_COUNT = WIDTH * HEIGHT
        const val PALETTE_SIZE = 16
        const val MAX_CHANGES = 4_096
    }
}
