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
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VmSessionTest {
    @Test
    fun `redstone synchronization validates and delegates exact unsigned bits`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        val input = RedstoneWire.packInput(1, intArrayOf(15, 0, 0, 0, 0, 0))
        val output = RedstoneWire.replaceOutput(0, 5, 0x1f)

        session.submitRedstoneInput(input)
        session.confirmRedstoneOutput(output)

        assertEquals(listOf(7L to input), bridge.redstoneInputs)
        assertEquals(listOf(7L to output), bridge.redstoneOutputs)
        assertFailsWith<IllegalArgumentException> { session.submitRedstoneInput(1 shl 30) }
        assertFailsWith<IllegalArgumentException> { session.confirmRedstoneOutput(1 shl 31) }
    }

    @Test
    fun `host request batch owns an immutable request snapshot`() {
        val requests =
            mutableListOf(
                VmHostRequest(1, CapabilityIdentity("app", "device", 1, 0), 0, emptyList()),
            )
        val batch = VmOutcome.HostRequestBatch(requests)

        requests.clear()

        assertEquals(1, batch.requests.size)
    }

    @Test
    fun `last write wins merge owns an immutable entry snapshot`() {
        val entries = mutableListOf(VmHostMergeEntry(keyBits = 2, valueBits = 15))
        val merge = VmHostMerge.LastWriteWins(groupBits = 7, entries = entries)

        entries.clear()

        assertEquals(listOf(VmHostMergeEntry(keyBits = 2, valueBits = 15)), merge.entries)
    }

    @Test
    fun `deployment candidate is retryable on failure and consumed only by success`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        val artifact = byteArrayOf(4, 5, 6)
        val candidate = session.verifyForDeploy(artifact)
        artifact.fill(0)
        bridge.revisionResult = bytes(1, 0)

        assertEquals(VmExecutableRevision.Absent, session.executableRevision("/home/example"))
        bridge.deployFailure = VmDeploymentConflictException()
        assertFailsWith<VmDeploymentConflictException> {
            session.deploy("/home/example", VmExecutableRevision.Absent, candidate)
        }
        bridge.deployFailure = null
        bridge.deployResult = bytes(1, 1, long(9))
        assertEquals(
            VmExecutableRevision.Present(9),
            session.deploy("/home/example", VmExecutableRevision.Absent, candidate),
        )

        assertEquals(listOf<Byte>(4, 5, 6), bridge.verifiedArtifacts.single())
        assertEquals(2, bridge.deployments.size)
        assertFailsWith<IllegalStateException> {
            session.deploy("/home/example", VmExecutableRevision.Present(9), candidate)
        }
        candidate.close()
        assertEquals(emptyList(), bridge.closedCandidates)
    }

    @Test
    fun `deployment candidates are session bound and explicit close is idempotent`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val first = VmSession.open(byteArrayOf(1), bridge)
        val second = VmSession.open(byteArrayOf(1), bridge)
        val candidate = first.verifyForDeploy(byteArrayOf(2))

        assertFailsWith<IllegalArgumentException> {
            second.deploy("/home/example", VmExecutableRevision.Absent, candidate)
        }
        candidate.close()
        candidate.close()
        assertEquals(listOf(41L), bridge.closedCandidates)
        assertFailsWith<IllegalStateException> {
            first.deploy("/home/example", VmExecutableRevision.Absent, candidate)
        }
    }

    @Test
    fun `canonical command submission preserves exact UTF16 code units`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val session = VmSession.open(byteArrayOf(1), bridge)

        session.submitCanonicalLine("run\ud800now".toCharArray())

        assertEquals(listOf("run\ud800now".toList()), bridge.canonicalLines)
        assertFailsWith<VmBridgeException> { session.executableRevision("/home/broken") }
    }

    @Test
    fun `boot session copies ROM and does not pass a separate artifact`() {
        val bridge = FakeBridge(createResult = bytes(0, long(19)))
        val store = WorldFileSystemStore.open(Path.of("/tmp/compukters-boot-store"), bridge)
        val rom = byteArrayOf(4, 5, 6)
        val id = ComputerId.fromLongs(9, 10)

        val session = VmSession.bootInStore(store, id, rom)
        rom.fill(0)

        assertEquals(23L, bridge.bootCreate?.storeHandle)
        assertEquals(id.toByteArray().toList(), bridge.bootCreate?.id?.toList())
        assertEquals(listOf<Byte>(4, 5, 6), bridge.bootCreate?.rom?.toList())
        session.close()
        store.close()
    }

    @Test
    fun `boot failure retains its typed Kotlin surface`() {
        val bridge = FakeBridge(createResult = bytes(5, 9))
        val store = WorldFileSystemStore.open(Path.of("/tmp/compukters-boot-failure-store"), bridge)

        assertEquals(
            9,
            assertFailsWith<VmBootException> {
                VmSession.bootInStore(store, ComputerId.fromLongs(1, 2), byteArrayOf(1))
            }.code,
        )
        store.close()
    }

    @Test
    fun `persistent session copies launch inputs and uses its world store handle`() {
        val bridge = FakeBridge(createResult = bytes(0, long(17)))
        val store = WorldFileSystemStore.open(Path.of("/tmp/compukters-session-store"), bridge)
        val artifact = byteArrayOf(1, 2, 3)
        val rom = byteArrayOf(4, 5, 6)
        val id = ComputerId.fromLongs(7, 8)

        val session = VmSession.openInStore(artifact, store, id, rom)
        artifact.fill(0)
        rom.fill(0)

        assertEquals(23L, bridge.persistentCreate?.storeHandle)
        assertEquals(id.toByteArray().toList(), bridge.persistentCreate?.id?.toList())
        assertEquals(listOf<Byte>(4, 5, 6), bridge.persistentCreate?.rom?.toList())
        assertEquals(listOf<Byte>(1, 2, 3), bridge.persistentCreate?.artifact?.toList())
        assertEquals(3, session.filesystemGeneration())
        session.close()
        store.close()
    }

    @Test
    fun `session owns its opaque handle and closes idempotently`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val session = VmSession.open(byteArrayOf(1, 2, 3), bridge)

        session.close()
        session.close()

        assertEquals(listOf(7L), bridge.closed)
        assertFailsWith<IllegalStateException> { session.advance(64, 64, Int.MAX_VALUE) }
    }

    @Test
    fun `session owns one terminal transport and closes it before its native handle`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val state = TerminalState(3, 51, 19, List(969) { TerminalCell(' '.code, 15, 0) }, TerminalPosition(0, 0), true)
        val transport = RecordingTerminalTransport(bridge.lifecycleEvents, state)
        bridge.terminalTransportFactory = {
            bridge.terminalTransportOpens++
            transport
        }

        val session = VmSession.open(byteArrayOf(1), bridge)
        assertEquals(state, session.terminalFullState())
        assertEquals(TerminalUpdate.Unchanged(3), session.terminalChangesSince(3))
        session.close()
        session.close()

        assertEquals(1, bridge.terminalTransportOpens)
        assertEquals(listOf(7L), transport.fullStateHandles)
        assertEquals(listOf(7L to 3L), transport.changeRequests)
        assertEquals(1, transport.closeCount)
        assertEquals(listOf("transport", "handle:7"), bridge.lifecycleEvents)
    }

    @Test
    fun `terminal transport construction failure closes admitted native handle`() {
        val bridge = FakeBridge(createResult = bytes(0, long(13)))
        bridge.terminalTransportFactory = { error("transport failed") }

        assertFailsWith<IllegalStateException> { VmSession.open(byteArrayOf(1), bridge) }

        assertEquals(listOf(13L), bridge.closed)
    }

    @Test
    fun `advance maps copied slice request waits trap fault quota and host failure outcomes`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.outcomes += bytes(0)
        bridge.outcomes += request()
        bridge.outcomes += bytes(2, 1)
        bridge.outcomes += bytes(4, 1, 1, int(23))
        bridge.outcomes += bytes(5, 0)
        bridge.outcomes += bytes(6, 7)
        bridge.outcomes += bytes(3, 1, long(4), long(3))
        bridge.outcomes += bytes(7, 0, int(17))
        bridge.outcomes += bytes(9)

        assertEquals(VmOutcome.SliceExhausted, session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(
            VmOutcome.HostRequestBatch(
                listOf(
                    VmHostRequest(
                        taskId = 2,
                        id = 9,
                        capability = CapabilityIdentity("compukter", "terminal", 1, 0),
                        operation = 0,
                        arguments = listOf(VmValue.StringValue("A\ud800\udc00")),
                        merge = VmHostMerge.Ordinary,
                    ),
                ),
            ),
            session.advance(64, 64, Int.MAX_VALUE),
        )
        assertEquals(VmOutcome.AllocationExhausted(collectionAttempted = true), session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(VmOutcome.Halted(VmValue.I32(23)), session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(VmOutcome.Crashed(GuestTrap.DIVISION_BY_ZERO), session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(VmOutcome.Faulted(VmFault.HANDLE_EXHAUSTED), session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(VmOutcome.QuotaExhausted(QuotaKind.HOST_REQUESTS, 4, 3), session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(VmOutcome.HostFailed(HostFailureKind.END_OF_FILE, 17), session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(VmOutcome.WaitingForTerminalEvent, session.advance(64, 64, Int.MAX_VALUE))
    }

    @Test
    fun `advance forwards host request budget and maps quota waiting`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.outcomes += bytes(11)

        assertEquals(VmOutcome.WaitingForHostQuota, session.advance(64, 32, 7))
        assertEquals(AdvanceCall(11, 64, 32, 7), bridge.advances.single())
    }

    @Test
    fun `advance preserves every request and last write wins entry in a batch`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.outcomes +=
            bytes(
                1,
                int(2),
                encodedRequest(taskId = 2, requestId = 9, operation = 4, merge = bytes(0)),
                encodedRequest(
                    taskId = 3,
                    requestId = 10,
                    operation = 5,
                    merge = bytes(1, int(7), int(2), int(2), int(15), int(4), int(0)),
                ),
            )

        assertEquals(
            VmOutcome.HostRequestBatch(
                listOf(
                    VmHostRequest(9, CapabilityIdentity("app", "device", 1, 0), 4, emptyList(), taskId = 2),
                    VmHostRequest(
                        10,
                        CapabilityIdentity("app", "device", 1, 0),
                        5,
                        emptyList(),
                        taskId = 3,
                        merge =
                            VmHostMerge.LastWriteWins(
                                groupBits = 7,
                                entries =
                                    listOf(
                                        VmHostMergeEntry(keyBits = 2, valueBits = 15),
                                        VmHostMergeEntry(keyBits = 4, valueBits = 0),
                                    ),
                            ),
                    ),
                ),
            ),
            session.advance(64, 64, Int.MAX_VALUE),
        )
    }

    @Test
    fun `compilation request is copied only for its outcome and completes through a dedicated bridge`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        val token = 31L
        bridge.outcomes += bytes(0)
        bridge.outcomes += bytes(10, long(token))
        bridge.compilationRequests[token] = compilationRequest(token, "/home/main.kt", "fun main() = 42\n".encodeToByteArray())

        assertEquals(VmOutcome.SliceExhausted, session.advance(64, 64, Int.MAX_VALUE))
        assertEquals(emptyList(), bridge.compilationRequestCalls)
        val outcome = session.advance(64, 64, Int.MAX_VALUE) as VmOutcome.CompilationRequested
        assertEquals(token, outcome.request.token)
        assertEquals(
            "/home/main.kt",
            outcome.request.sources
                .single()
                .path,
        )
        val source =
            outcome.request.sources
                .single()
                .utf8Bytes()
        assertEquals("fun main() = 42\n", source.decodeToString())
        source.fill(0)
        assertEquals(
            "fun main() = 42\n",
            outcome.request.sources
                .single()
                .utf8Bytes()
                .decodeToString(),
        )
        assertEquals(listOf(11L to token), bridge.compilationRequestCalls)

        val artifact = byteArrayOf(1, 2, 3)
        session.completeCompilationArtifact(token, artifact)
        artifact.fill(0)
        session.completeCompilationFailure(token + 1, "compiler failed")
        assertEquals(listOf(CompilationArtifact(11, token, listOf<Byte>(1, 2, 3))), bridge.compilationArtifacts)
        assertEquals(listOf(CompilationFailure(11, token + 1, "compiler failed")), bridge.compilationFailures)
    }

    @Test
    fun `malformed compilation snapshots are bridge failures`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.outcomes += bytes(10, long(7))
        bridge.compilationRequests[7] = compilationRequest(7, "/home/main.kt", byteArrayOf(0xff.toByte()))
        assertFailsWith<VmBridgeException> { session.advance(64, 64, Int.MAX_VALUE) }

        bridge.outcomes += bytes(10, long(8))
        bridge.compilationRequests[8] = compilationRequest(8, "/home/main.kt", byteArrayOf()).plus(99)
        assertFailsWith<VmBridgeException> { session.advance(64, 64, Int.MAX_VALUE) }
    }

    @Test
    fun `typed host responses are forwarded with the pending request id`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)

        session.resume(VmHostRequestIdentity(2, 7), HostResponse.UnitSuccess)
        session.resume(VmHostRequestIdentity(3, 8), HostResponse.StringSuccess("A\ud800B"))
        session.resume(VmHostRequestIdentity(4, 9), HostResponse.Failure(HostFailureKind.END_OF_FILE, 17))

        assertEquals(listOf(UnitResponse(11, 2, 7)), bridge.unitResponses)
        assertEquals(listOf(StringResponse(11, 3, 8, "A\ud800B".toCharArray().toList())), bridge.stringResponses)
        assertEquals(listOf(FailureResponse(11, 4, 9, 0, 17)), bridge.failures)
    }

    @Test
    fun `malformed native output is rejected as a bridge failure`() {
        val truncatedCreate = FakeBridge(createResult = bytes(0, 1))
        assertFailsWith<VmBridgeException> { VmSession.open(byteArrayOf(1), truncatedCreate) }

        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.outcomes += bytes(0, 99)
        assertFailsWith<VmBridgeException> { session.advance(64, 64, Int.MAX_VALUE) }

        bridge.outcomes += bytes(2, 2)
        assertFailsWith<VmBridgeException> { session.advance(64, 64, Int.MAX_VALUE) }
    }

    @Test
    fun `terminal state and inputs use immutable bounded bridge values`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        val cells =
            buildList {
                add(cell('>'.code, 15, 0))
                repeat(968) { add(cell(' '.code, 15, 0)) }
            }.fold(ByteArray(0), ByteArray::plus)
        bridge.terminalState =
            bytes(2, long(3), short(51), short(19), int(969), cells, short(50), short(18), 1)
        bridge.terminalUpdate = bytes(0, long(3))

        val state = session.terminalFullState()
        assertEquals(3, state.revision)
        assertEquals(TerminalCell('>'.code, 15, 0), state.cells.first())
        assertEquals(TerminalPosition(50, 18), state.cursor)
        assertEquals(TerminalUpdate.Unchanged(3), session.terminalChangesSince(3))

        session.commitTerminal()
        session.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.REPEAT, setOf(TerminalModifier.CONTROL))
        session.sendTerminalText("λ😀")
        assertEquals(1, bridge.terminalCommits)
        assertEquals(listOf(TerminalKeyInput(13, 1, 2)), bridge.terminalKeys)
        assertEquals(listOf(listOf('λ'.code, 0x1f600)), bridge.terminalTexts.map(IntArray::toList))
    }

    @Test
    fun `malformed terminal counts and trailing bytes are bridge failures`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.terminalState = bytes(2, long(0), short(51), short(19), int(Int.MAX_VALUE))
        assertFailsWith<VmBridgeException> { session.terminalFullState() }

        bridge.terminalUpdate = bytes(0, long(0), 99)
        assertFailsWith<VmBridgeException> { session.terminalChangesSince(0) }
    }

    @Test
    fun `create failures retain their typed Kotlin surface`() {
        assertFailsWith<VmVerificationException> { VmSession.open(byteArrayOf(1), FakeBridge(bytes(1))) }
        assertEquals(
            7,
            assertFailsWith<VmAdmissionException> {
                VmSession.open(byteArrayOf(1), FakeBridge(bytes(2, short(7))))
            }.code,
        )
        assertEquals(
            8,
            assertFailsWith<VmStartException> {
                VmSession.open(byteArrayOf(1), FakeBridge(bytes(3, short(8))))
            }.code,
        )
        assertFailsWith<VmBridgeException> { VmSession.open(byteArrayOf(1), FakeBridge(bytes(4, 9))) }
    }

    private fun request(): ByteArray =
        bytes(
            1,
            int(1),
            int(2),
            long(9),
            int(9),
            "compukter".encodeToByteArray(),
            int(8),
            "terminal".encodeToByteArray(),
            short(1),
            short(0),
            int(0),
            int(1),
            7,
            int(3),
            short(0x41),
            short(0xd800),
            short(0xdc00),
            0,
        )

    private fun encodedRequest(
        taskId: Int,
        requestId: Long,
        operation: Int,
        merge: ByteArray,
    ): ByteArray =
        bytes(
            int(taskId),
            long(requestId),
            int(3),
            "app".encodeToByteArray(),
            int(6),
            "device".encodeToByteArray(),
            short(1),
            short(0),
            int(operation),
            int(0),
            merge,
        )

    private class FakeBridge(
        private val createResult: ByteArray,
    ) : LowLevelVmBridge {
        val outcomes = ArrayDeque<ByteArray>()
        val advances = mutableListOf<AdvanceCall>()
        val closed = mutableListOf<Long>()
        val unitResponses = mutableListOf<UnitResponse>()
        val stringResponses = mutableListOf<StringResponse>()
        val failures = mutableListOf<FailureResponse>()
        var terminalState = ByteArray(0)
        var terminalUpdate = ByteArray(0)
        var terminalCommits = 0
        val terminalKeys = mutableListOf<TerminalKeyInput>()
        val terminalTexts = mutableListOf<IntArray>()
        var persistentCreate: PersistentCreate? = null
        var bootCreate: BootCreate? = null
        var terminalTransportFactory: (() -> TerminalWireTransport)? = null
        var terminalTransportOpens = 0
        val lifecycleEvents = mutableListOf<String>()
        val compilationRequests = mutableMapOf<Long, ByteArray>()
        val compilationRequestCalls = mutableListOf<Pair<Long, Long>>()
        val compilationArtifacts = mutableListOf<CompilationArtifact>()
        val compilationFailures = mutableListOf<CompilationFailure>()
        val verifiedArtifacts = mutableListOf<List<Byte>>()
        val closedCandidates = mutableListOf<Long>()
        val deployments = mutableListOf<Deployment>()
        val canonicalLines = mutableListOf<List<Char>>()
        val redstoneInputs = mutableListOf<Pair<Long, Int>>()
        val redstoneOutputs = mutableListOf<Pair<Long, Int>>()
        var revisionResult: ByteArray = byteArrayOf(99)
        var deployResult: ByteArray = byteArrayOf(99)
        var deployFailure: RuntimeException? = null

        override fun openTerminalTransport(): TerminalWireTransport =
            terminalTransportFactory?.invoke() ?: super<LowLevelVmBridge>.openTerminalTransport()

        override fun filesystemGeneration(handle: Long): ByteArray = bytes(1, long(3))

        override fun verifyForDeploy(
            handle: Long,
            artifact: ByteArray,
        ): Long = 41L.also { verifiedArtifacts += artifact.toList() }

        override fun deploymentCandidateClose(handle: Long) {
            closedCandidates += handle
        }

        override fun executableRevision(
            handle: Long,
            pathUtf8: ByteArray,
        ): ByteArray = revisionResult

        override fun deploy(
            handle: Long,
            candidateHandle: Long,
            pathUtf8: ByteArray,
            expectedKind: Int,
            expectedGeneration: Long,
        ): ByteArray {
            deployments += Deployment(handle, candidateHandle, pathUtf8.decodeToString(), expectedKind, expectedGeneration)
            deployFailure?.let { throw it }
            return deployResult
        }

        override fun submitCanonicalLine(
            handle: Long,
            line: CharArray,
        ) {
            canonicalLines += line.toList()
        }

        override fun submitRedstoneInput(
            handle: Long,
            packet: Int,
        ) {
            redstoneInputs += handle to packet
        }

        override fun confirmRedstoneOutput(
            handle: Long,
            packed: Int,
        ) {
            redstoneOutputs += handle to packed
        }

        override fun storeOpen(
            rootUtf8: ByteArray,
            limitsWire: ByteArray,
        ): ByteArray = bytes(1, 0, long(23))

        override fun storeClose(handle: Long) = Unit

        override fun createInStore(
            storeHandle: Long,
            id: ByteArray,
            rom: ByteArray,
            artifact: ByteArray,
        ): ByteArray {
            persistentCreate = PersistentCreate(storeHandle, id.copyOf(), rom.copyOf(), artifact.copyOf())
            return createResult
        }

        override fun createBootInStore(
            storeHandle: Long,
            id: ByteArray,
            rom: ByteArray,
        ): ByteArray {
            bootCreate = BootCreate(storeHandle, id.copyOf(), rom.copyOf())
            return createResult
        }

        override fun create(artifact: ByteArray): ByteArray = createResult

        override fun advance(
            handle: Long,
            guestBudget: Int,
            maintenanceBudget: Int,
            hostRequestBudget: Int,
        ): ByteArray {
            advances += AdvanceCall(handle, guestBudget, maintenanceBudget, hostRequestBudget)
            return outcomes.removeFirst()
        }

        override fun compilationRequest(
            handle: Long,
            token: Long,
        ): ByteArray = compilationRequests.getValue(token).also { compilationRequestCalls += handle to token }

        override fun completeCompilationArtifact(
            handle: Long,
            token: Long,
            artifact: ByteArray,
        ) {
            compilationArtifacts += CompilationArtifact(handle, token, artifact.toList())
        }

        override fun completeCompilationFailure(
            handle: Long,
            token: Long,
            diagnostics: String,
        ) {
            compilationFailures += CompilationFailure(handle, token, diagnostics)
        }

        override fun resumeUnit(
            handle: Long,
            taskId: Int,
            requestId: Long,
        ) {
            unitResponses += UnitResponse(handle, taskId, requestId)
        }

        override fun resumeString(
            handle: Long,
            taskId: Int,
            requestId: Long,
            value: CharArray,
        ) {
            stringResponses += StringResponse(handle, taskId, requestId, value.toList())
        }

        override fun resumeFailure(
            handle: Long,
            taskId: Int,
            requestId: Long,
            kind: Int,
            code: Long,
        ) {
            failures += FailureResponse(handle, taskId, requestId, kind, code)
        }

        override fun close(handle: Long) {
            closed += handle
            lifecycleEvents += "handle:$handle"
        }

        override fun terminalCommit(handle: Long) {
            terminalCommits++
        }

        override fun terminalFullState(handle: Long): ByteArray = terminalState

        override fun terminalChangesSince(
            handle: Long,
            revision: Long,
        ): ByteArray = terminalUpdate

        override fun terminalKey(
            handle: Long,
            key: Int,
            action: Int,
            modifiers: Int,
        ) {
            terminalKeys += TerminalKeyInput(key, action, modifiers)
        }

        override fun terminalText(
            handle: Long,
            codePoints: IntArray,
        ) {
            terminalTexts += codePoints
        }
    }

    private class RecordingTerminalTransport(
        private val lifecycleEvents: MutableList<String>,
        private val state: TerminalState,
    ) : TerminalWireTransport {
        val fullStateHandles = mutableListOf<Long>()
        val changeRequests = mutableListOf<Pair<Long, Long>>()
        var closeCount = 0

        override fun fullState(handle: Long): TerminalState = state.also { fullStateHandles += handle }

        override fun changesSince(
            handle: Long,
            revision: Long,
        ): TerminalUpdate = TerminalUpdate.Unchanged(state.revision).also { changeRequests += handle to revision }

        override fun close() {
            closeCount++
            lifecycleEvents += "transport"
        }
    }

    private data class UnitResponse(
        val handle: Long,
        val taskId: Int,
        val requestId: Long,
    )

    private data class AdvanceCall(
        val handle: Long,
        val guestBudget: Int,
        val maintenanceBudget: Int,
        val hostRequestBudget: Int,
    )

    private data class StringResponse(
        val handle: Long,
        val taskId: Int,
        val requestId: Long,
        val value: List<Char>,
    )

    private data class FailureResponse(
        val handle: Long,
        val taskId: Int,
        val requestId: Long,
        val kind: Int,
        val code: Long,
    )

    private data class TerminalKeyInput(
        val key: Int,
        val action: Int,
        val modifiers: Int,
    )

    private data class PersistentCreate(
        val storeHandle: Long,
        val id: ByteArray,
        val rom: ByteArray,
        val artifact: ByteArray,
    )

    private data class BootCreate(
        val storeHandle: Long,
        val id: ByteArray,
        val rom: ByteArray,
    )

    private data class CompilationArtifact(
        val handle: Long,
        val token: Long,
        val artifact: List<Byte>,
    )

    private data class CompilationFailure(
        val handle: Long,
        val token: Long,
        val diagnostics: String,
    )

    private data class Deployment(
        val handle: Long,
        val candidateHandle: Long,
        val path: String,
        val expectedKind: Int,
        val expectedGeneration: Long,
    )
}

private fun cell(
    codePoint: Int,
    foreground: Int,
    background: Int,
): ByteArray = bytes(int(codePoint), foreground, background)

private fun bytes(vararg parts: Any): ByteArray =
    parts
        .flatMap { part ->
            when (part) {
                is Int -> listOf(part.toByte())
                is ByteArray -> part.toList()
                else -> error("unsupported test wire part: $part")
            }
        }.toByteArray()

private fun long(value: Long): ByteArray =
    ByteBuffer
        .allocate(Long.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(value)
        .array()

private fun int(value: Int): ByteArray =
    ByteBuffer
        .allocate(Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

private fun short(value: Int): ByteArray =
    ByteBuffer
        .allocate(Short.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value.toShort())
        .array()

private fun compilationRequest(
    token: Long,
    path: String,
    source: ByteArray,
): ByteArray {
    val pathBytes = path.encodeToByteArray()
    return bytes(short(1), long(token), int(1), int(pathBytes.size), pathBytes, int(source.size), source)
}
