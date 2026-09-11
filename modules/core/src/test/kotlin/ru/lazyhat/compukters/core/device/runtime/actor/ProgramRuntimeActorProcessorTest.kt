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

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSession
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSessionFactory
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryEntry
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileKind
import ru.lazyhat.compukters.lang.runtime.fs.VmFileMetadata
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineException
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineFailure
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmResourceSnapshot
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgramRuntimeActorProcessorTest {
    @Test
    fun `carrier requests resource snapshots only on the actor worker and rejects them after close`() {
        val owner = Thread.currentThread()
        val session = RecordingSession()
        val host =
            ProgramRuntimeHost(
                object : ProgramVmSessionFactory {
                    override fun open(artifact: ByteArray): ProgramVmSession = session

                    override fun boot(): ProgramVmSession = session
                },
            )
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(70, 80), 1)
        ProgramRuntimeActorService(schedulerConfig()).use { service ->
            val carrier =
                ActorProgramComputer(
                    service,
                    requireNotNull(service.attach(endpoint, host)),
                    { RedstoneCommitResult.Committed },
                )
            val boot = carrier.turnOn()
            awaitQueuedResult(service)
            service.pump(1)
            assertTrue(boot.isDone)
            assertTrue(session.calls.none { it.startsWith("resourceSnapshot:") })

            val requested = carrier.resourceSnapshot()
            awaitQueuedResult(service)
            assertTrue(session.calls.single { it.startsWith("resourceSnapshot:") }.substringAfter(':') != owner.name)
            service.pump(1)
            val snapshot =
                assertIs<ProgramRuntimeActorValue.ResourceSnapshotValue>(requested.get().value).snapshot
            assertIs<ProgramResourceSnapshot.Available>(snapshot)

            carrier.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(carrier.resourceSnapshot().isCompletedExceptionally)
            assertEquals(1, session.calls.count { it.startsWith("resourceSnapshot:") })
        }
    }

    @Test
    fun `request rejection does not terminate the runtime actor`() {
        val session = RecordingSession()
        val processor = ProgramRuntimeActorProcessor(ProgramRuntimeHost(ProgramVmSessionFactory { session }))
        processor.use {
            processor.process(ProgramRuntimeActorCommand.Start(request(1), byteArrayOf(1)))
            session.rejectVerification = true
            val verification = processor.process(ProgramRuntimeActorCommand.PrepareDeployment(request(2), byteArrayOf(2)))
            assertEquals(ProgramRuntimeActorFailure.Verification, assertIs<ProgramRuntimeActorValue.Rejected>(verification.value).failure)
            session.rejectCanonicalLine = true
            val canonical = processor.process(ProgramRuntimeActorCommand.SubmitCanonicalLine(request(3), charArrayOf('x')))
            assertEquals(
                ProgramRuntimeActorFailure.CanonicalLine(VmCanonicalLineFailure.INPUT_BUSY),
                assertIs<ProgramRuntimeActorValue.Rejected>(canonical.value).failure,
            )
            assertIs<ProgramRuntimeActorValue.TerminalStateValue>(
                processor.process(ProgramRuntimeActorCommand.TerminalFullState(request(4))).value,
            )
        }
    }

    @Test
    fun `async carrier commits world output on its owner and closes without pumping late replies`() {
        val owner = Thread.currentThread()
        val session = RecordingSession()
        val port = ActorRedstoneHostPort()
        val host =
            ProgramRuntimeHost(
                object : ProgramVmSessionFactory {
                    override fun open(artifact: ByteArray): ProgramVmSession = session

                    override fun boot(): ProgramVmSession = session
                },
                redstoneHostPort = port,
            )
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(7, 8), 1)
        ProgramRuntimeActorService(schedulerConfig()).use { service ->
            var commits = 0
            val carrier =
                ActorProgramComputer(service, requireNotNull(service.attach(endpoint, host, port)), {
                    assertEquals(owner, Thread.currentThread())
                    commits++
                    RedstoneCommitResult.Committed
                })
            val boot = carrier.turnOn()

            fun awaitQueuedResult() {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
                while (service.metrics().queuedResults == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
                assertTrue(service.metrics().queuedResults > 0)
            }
            awaitQueuedResult()
            service.pump(1)
            assertTrue(boot.isDone)
            session.nextOutcome =
                VmOutcome.HostRequestBatch(
                    listOf(VmHostRequest(1, REDSTONE, 6, listOf(VmValue.I32(2), VmValue.I32(7)))),
                )
            carrier.serverTick(1)
            awaitQueuedResult()
            repeat(10) { carrier.serverTick(it.toLong() + 2) }
            assertEquals(1, service.metrics().queuedResults)
            service.pump(1)
            assertEquals(1, commits)
            assertEquals(1, service.runtimeMetrics().deferredWorldRequests)
            assertEquals(1, service.runtimeMetrics().totalDeferredWorldRequests)
            awaitQueuedResult()
            service.pump(1)
            assertEquals(0, service.runtimeMetrics().deferredWorldRequests)
            val closed = carrier.closeAsync()
            assertEquals(7L, closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, session.closeCalls)
            service.pump(32)
            assertEquals(ProgramRuntimeState.Closed, carrier.state)
            assertEquals(1, commits)
        }
    }

    @Test
    fun `async carrier emits sound only on its owner thread and returns admission`() {
        val owner = Thread.currentThread()
        val session = RecordingSession()
        val port = ActorSoundHostPort()
        val host =
            ProgramRuntimeHost(
                object : ProgramVmSessionFactory {
                    override fun open(artifact: ByteArray): ProgramVmSession = session

                    override fun boot(): ProgramVmSession = session
                },
                soundHostPort = port,
            )
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(9, 10), 1)
        ProgramRuntimeActorService(schedulerConfig()).use { service ->
            var emissions = 0
            val lease = requireNotNull(service.attach(endpoint, host, soundPort = port))
            val carrier =
                ActorProgramComputer(
                    service,
                    lease,
                    { RedstoneCommitResult.Committed },
                    SoundHostPort { requests ->
                        assertEquals(owner, Thread.currentThread())
                        assertEquals(listOf(SoundRequest(12, 75)), requests)
                        emissions++
                        SoundCommitResult.Completed(listOf(true))
                    },
                )
            carrier.turnOn()
            awaitQueuedResult(service)
            service.pump(1)
            session.nextOutcome =
                VmOutcome.HostRequestBatch(
                    listOf(VmHostRequest(1, SOUND, 0, listOf(VmValue.I32(12), VmValue.I32(75)))),
                )

            carrier.serverTick(1)
            awaitQueuedResult(service)
            service.pump(1)
            assertEquals(1, emissions)
            assertEquals(1, service.runtimeMetrics().deferredWorldRequests)
            awaitQueuedResult(service)
            carrier.serverTick(2)
            service.pump(1)

            assertEquals(listOf<HostResponse>(HostResponse.BoolSuccess(true)), session.responses)
            assertEquals(0, service.runtimeMetrics().deferredWorldRequests)
            carrier.serverTick(3)
            awaitQueuedResult(service)
            service.pump(1)
            val metrics = service.runtimeMetrics()
            assertEquals(1, metrics.hostContinuationSamples)
            assertEquals(2, metrics.totalHostContinuationDelayTicks)
            assertEquals(2, metrics.maximumHostContinuationDelayTicks)
            carrier.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `prepared deployments keep independent tokens until discarded`() {
        val session = RecordingSession()
        val host = ProgramRuntimeHost(ProgramVmSessionFactory { session })
        ProgramRuntimeActorProcessor(host).use { processor ->
            processor.process(ProgramRuntimeActorCommand.Start(request(1), byteArrayOf(1)))
            val first =
                assertIs<ProgramRuntimeActorValue.DeploymentPrepared>(
                    processor.process(ProgramRuntimeActorCommand.PrepareDeployment(request(2), byteArrayOf(2))).value,
                )
            val second =
                assertIs<ProgramRuntimeActorValue.DeploymentPrepared>(
                    processor.process(ProgramRuntimeActorCommand.PrepareDeployment(request(3), byteArrayOf(3))).value,
                )
            assertTrue(first.token != second.token)
            for ((index, prepared) in listOf(first, second).withIndex()) {
                val discarded =
                    processor.process(
                        ProgramRuntimeActorCommand.DiscardDeployment(request(4L + index), requireNotNull(prepared.token)),
                    )
                assertTrue(assertIs<ProgramRuntimeActorValue.Accepted>(discarded.value).accepted)
            }
        }
    }

    @Test
    fun `stale redstone acknowledgement cannot complete a newer identical output`() {
        val session = RecordingSession()
        val port = ActorRedstoneHostPort()
        val host = ProgramRuntimeHost(ProgramVmSessionFactory { session }, redstoneHostPort = port)
        ProgramRuntimeActorProcessor(host, port).use { processor ->
            processor.process(ProgramRuntimeActorCommand.Start(request(1), byteArrayOf(1)))

            fun output(id: Long): Int {
                session.nextOutcome =
                    VmOutcome.HostRequestBatch(
                        listOf(VmHostRequest(id, REDSTONE, 6, listOf(VmValue.I32(2), VmValue.I32(7)))),
                    )
                return assertIs<ProgramRuntimeActorValue.RedstoneOutputRequested>(
                    processor.process(ProgramRuntimeActorCommand.Advance(request(id), id)).value,
                ).packed
            }
            val packed = output(2)
            processor.process(ProgramRuntimeActorCommand.Shutdown(request(3)))
            processor.process(ProgramRuntimeActorCommand.Start(request(4), byteArrayOf(1)))
            assertEquals(packed, output(5))
            val stale =
                processor.process(
                    ProgramRuntimeActorCommand.CompleteRedstoneOutput(request(6), request(2), packed, RedstoneCommitResult.Committed),
                )
            assertEquals(false, assertIs<ProgramRuntimeActorValue.Accepted>(stale.value).accepted)
            val current =
                processor.process(
                    ProgramRuntimeActorCommand.CompleteRedstoneOutput(request(7), request(5), packed, RedstoneCommitResult.Committed),
                )
            assertTrue(assertIs<ProgramRuntimeActorValue.Accepted>(current.value).accepted)
        }
    }

    @Test
    fun `redstone output crosses the actor boundary as a deferred world request`() {
        val session = RecordingSession()
        session.nextOutcome =
            VmOutcome.HostRequestBatch(
                listOf(VmHostRequest(1, REDSTONE, 6, listOf(VmValue.I32(2), VmValue.I32(7)))),
            )
        val port = ActorRedstoneHostPort()
        val host = ProgramRuntimeHost(ProgramVmSessionFactory { session }, redstoneHostPort = port)
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(5, 6), 1)
        val expected = RedstoneWire.replaceOutput(0, 2, 7)
        VmActorScheduler<ProgramRuntimeActorCommand, ProgramRuntimeActorReply>(schedulerConfig()).use { scheduler ->
            assertTrue(scheduler.register(endpoint, ProgramRuntimeActorProcessor(host, port)))
            scheduler.submit(endpoint, ProgramRuntimeActorCommand.Start(request(1), byteArrayOf(1)))
            scheduler.awaitReplies(1)
            scheduler.submit(endpoint, ProgramRuntimeActorCommand.Advance(request(2), 100))

            val request = assertIs<ProgramRuntimeActorValue.RedstoneOutputRequested>(scheduler.awaitReplies(1).single().value)
            assertEquals(expected, request.packed)

            scheduler.submit(
                endpoint,
                ProgramRuntimeActorCommand.CompleteRedstoneOutput(
                    request(3),
                    request(2),
                    request.packed,
                    RedstoneCommitResult.Committed,
                ),
            )
            assertTrue(assertIs<ProgramRuntimeActorValue.Accepted>(scheduler.awaitReplies(1).single().value).accepted)
            assertTrue(session.calls.any { it.startsWith("resume:") })
        }
    }

    @Test
    fun `sound crosses the actor boundary and resumes with the server admission`() {
        val session = RecordingSession()
        session.nextOutcome =
            VmOutcome.HostRequestBatch(
                listOf(VmHostRequest(1, SOUND, 0, listOf(VmValue.I32(12), VmValue.I32(75)))),
            )
        val port = ActorSoundHostPort()
        val host = ProgramRuntimeHost(ProgramVmSessionFactory { session }, soundHostPort = port)
        ProgramRuntimeActorProcessor(host, soundPort = port).use { processor ->
            processor.process(ProgramRuntimeActorCommand.Start(request(1), byteArrayOf(1)))

            val emitted =
                assertIs<ProgramRuntimeActorValue.SoundRequested>(
                    processor.process(ProgramRuntimeActorCommand.Advance(request(2), 100)).value,
                )
            assertEquals(listOf(SoundRequest(12, 75)), emitted.requests)

            val completed =
                processor.process(
                    ProgramRuntimeActorCommand.CompleteSound(
                        request(3),
                        request(2),
                        SoundCommitResult.Completed(listOf(true)),
                    ),
                )
            assertTrue(assertIs<ProgramRuntimeActorValue.Accepted>(completed.value).accepted)
            assertEquals(listOf<HostResponse>(HostResponse.BoolSuccess(true)), session.responses)
        }
    }

    @Test
    fun `runtime operations and native resources remain on one worker-owned actor`() {
        val session = RecordingSession()
        val host =
            ProgramRuntimeHost(
                object : ProgramVmSessionFactory {
                    override fun open(artifact: ByteArray): ProgramVmSession = session

                    override fun boot(): ProgramVmSession = session
                },
            )
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, 2), 1)
        val path = VmVirtualPath.of("/home/demo")
        VmActorScheduler<ProgramRuntimeActorCommand, ProgramRuntimeActorReply>(schedulerConfig()).use { scheduler ->
            assertTrue(scheduler.register(endpoint, ProgramRuntimeActorProcessor(host)))
            val artifact = byteArrayOf(3, 4, 5)
            val prepare = ProgramRuntimeActorCommand.PrepareDeployment(request(6), artifact)
            artifact.fill(0)
            val commands =
                listOf(
                    ProgramRuntimeActorCommand.StartBoot(request(1)),
                    ProgramRuntimeActorCommand.Advance(request(2), worldTick = 100),
                    ProgramRuntimeActorCommand.SendTerminalText(request(3), "hello"),
                    ProgramRuntimeActorCommand.TerminalFullState(request(4)),
                    ProgramRuntimeActorCommand.FileRead(request(5), path, 0, 32, 7),
                    prepare,
                )
            commands.forEach { assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint, it)) }

            val replies = scheduler.awaitReplies(commands.size)
            assertIs<ProgramRuntimeActorValue.Start>(replies[0].value).also {
                assertEquals(ProgramStartResult.Started, it.result)
            }
            assertEquals(ProgramRuntimeState.Running, replies[1].state)
            assertEquals(true, assertIs<ProgramRuntimeActorValue.Accepted>(replies[2].value).accepted)
            assertEquals(session.terminal, assertIs<ProgramRuntimeActorValue.TerminalStateValue>(replies[3].value).state)
            assertContentEquals(
                byteArrayOf(9, 8),
                assertIs<ProgramRuntimeActorValue.FileChunkValue>(replies[4].value).chunk?.bytes,
            )
            val token = assertIs<ProgramRuntimeActorValue.DeploymentPrepared>(replies[5].value).token
            assertEquals(byteArrayOf(3, 4, 5).toList(), session.verifiedArtifact?.toList())

            val line = "run /home/demo".toCharArray()
            val remaining =
                listOf(
                    ProgramRuntimeActorCommand.Deploy(request(7), path.value, VmExecutableRevision.Absent, requireNotNull(token)),
                    ProgramRuntimeActorCommand.SubmitCanonicalLine(request(8), line),
                    ProgramRuntimeActorCommand.SubmitRedstoneInput(request(9), 0),
                    ProgramRuntimeActorCommand.Shutdown(request(10)),
                )
            line.fill('x')
            remaining.forEach { assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint, it)) }
            val remainingReplies = scheduler.awaitReplies(remaining.size)

            assertEquals(
                VmExecutableRevision.Present(8),
                assertIs<ProgramRuntimeActorValue.DeployedRevision>(remainingReplies[0].value).revision,
            )
            assertEquals("run /home/demo", session.canonicalLine)
            assertTrue(session.candidate.closed)
            assertEquals(ProgramRuntimeState.Idle, remainingReplies.last().state)
            assertTrue(scheduler.unregister(endpoint).get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

        assertTrue(session.calls.isNotEmpty())
        assertTrue(session.calls.all { it.substringAfterLast(':').startsWith("compukters-vm-worker-") })
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `mutable start input is copied before mailbox ownership transfer`() {
        val session = RecordingSession()
        var openedArtifact: ByteArray? = null
        val host =
            ProgramRuntimeHost(
                ProgramVmSessionFactory { artifact ->
                    openedArtifact = artifact.copyOf()
                    session
                },
            )
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(3, 4), 1)
        VmActorScheduler<ProgramRuntimeActorCommand, ProgramRuntimeActorReply>(schedulerConfig()).use { scheduler ->
            assertTrue(scheduler.register(endpoint, ProgramRuntimeActorProcessor(host)))
            val artifact = byteArrayOf(1, 2, 3)
            val command = ProgramRuntimeActorCommand.Start(request(1), artifact)
            artifact.fill(9)

            assertEquals(VmActorSubmission.ACCEPTED, scheduler.submit(endpoint, command))
            val reply = scheduler.awaitReplies(1).single()

            assertEquals(ProgramStartResult.Started, assertIs<ProgramRuntimeActorValue.Start>(reply.value).result)
            assertContentEquals(byteArrayOf(1, 2, 3), openedArtifact)
        }
    }

    private fun schedulerConfig(): VmActorSchedulerConfig =
        VmActorSchedulerConfig(
            workerCount = 1,
            maximumActors = 4,
            mailboxCapacity = 32,
            messagesPerTurn = 4,
            resultCapacityPerWorker = 32,
        )

    private fun request(value: Long) = ProgramRuntimeRequestId(value)

    private fun awaitQueuedResult(service: ProgramRuntimeActorService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (service.metrics().queuedResults == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(service.metrics().queuedResults > 0)
    }

    private fun VmActorScheduler<ProgramRuntimeActorCommand, ProgramRuntimeActorReply>.awaitReplies(
        count: Int,
    ): List<ProgramRuntimeActorReply> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        val replies = mutableListOf<ProgramRuntimeActorReply>()
        while (replies.size < count && System.nanoTime() < deadline) {
            drainEvents(count - replies.size).forEach { event ->
                replies += assertIs<VmActorEvent.Result<ProgramRuntimeActorReply>>(event).value
            }
            if (replies.size < count) Thread.onSpinWait()
        }
        assertEquals(count, replies.size, "runtime replies before timeout")
        return replies
    }

    private class RecordingSession : ProgramVmSession {
        val calls = mutableListOf<String>()
        val responses = mutableListOf<HostResponse>()
        var candidate = RecordingCandidate(calls)
        val terminal =
            TerminalState(
                revision = 1,
                width = 1,
                height = 1,
                cells = listOf(TerminalCell('A'.code, 1, 0)),
                cursor = TerminalPosition(0, 0),
                cursorVisible = true,
            )
        var verifiedArtifact: ByteArray? = null
        var canonicalLine: String? = null
        var closeCalls = 0
        var nextOutcome: VmOutcome = VmOutcome.SliceExhausted
        var rejectVerification = false
        var rejectCanonicalLine = false

        override fun advance(
            guestBudget: Int,
            maintenanceBudget: Int,
            hostRequestBudget: Int,
        ): VmOutcome =
            record("advance") {
                nextOutcome.also { nextOutcome = VmOutcome.SliceExhausted }
            }

        override fun resume(
            identity: VmHostRequestIdentity,
            response: HostResponse,
        ) = record("resume") { responses += response }

        override fun completeCompilationArtifact(
            token: Long,
            artifact: ByteArray,
        ) = record("completeCompilationArtifact") { }

        override fun completeCompilationFailure(
            token: Long,
            diagnostics: String,
        ) = record("completeCompilationFailure") { }

        override fun commitTerminal() = record("commitTerminal") { }

        override fun terminalFullState(): TerminalState = record("terminalFullState") { terminal }

        override fun terminalChangesSince(revision: Long): TerminalUpdate =
            record("terminalChangesSince") { TerminalUpdate.Unchanged(revision) }

        override fun sendTerminalKey(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier>,
        ) = record("sendTerminalKey") { }

        override fun sendTerminalText(value: String) = record("sendTerminalText") { }

        override fun filesystemGeneration(): Long = record("filesystemGeneration") { 7 }

        override fun resourceSnapshot(): VmResourceSnapshot =
            record("resourceSnapshot") {
                VmResourceSnapshot(1, 2, 3, 4, 5, 100, 40, 6, 30, 80, 100, 7, 10, false)
            }

        override fun fileStat(path: VmVirtualPath): VmFileStat = record("fileStat") { VmFileStat(7, metadata()) }

        override fun fileList(
            path: VmVirtualPath,
            startAfter: String?,
            maximumEntries: Int,
        ): VmDirectoryListing =
            record("fileList") {
                VmDirectoryListing(7, 3, true, listOf(VmDirectoryEntry("demo", metadata())))
            }

        override fun fileRead(
            path: VmVirtualPath,
            offset: Long,
            maximumBytes: Int,
            expectedGeneration: Long,
        ): VmFileChunk = record("fileRead") { VmFileChunk(7, 2, true, byteArrayOf(9, 8)) }

        override fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate =
            record("verifyForDeploy") {
                if (rejectVerification) throw VmVerificationException()
                verifiedArtifact = artifact.copyOf()
                candidate = RecordingCandidate(calls)
                candidate
            }

        override fun executableRevision(path: String): VmExecutableRevision = record("executableRevision") { VmExecutableRevision.Absent }

        override fun deploy(
            path: String,
            expected: VmExecutableRevision,
            candidate: ProgramDeploymentCandidate,
        ): VmExecutableRevision =
            record("deploy") {
                assertEquals(this.candidate, candidate)
                VmExecutableRevision.Present(8)
            }

        override fun submitCanonicalLine(line: CharArray) =
            record("submitCanonicalLine") {
                if (rejectCanonicalLine) throw VmCanonicalLineException(VmCanonicalLineFailure.INPUT_BUSY)
                canonicalLine = line.concatToString()
            }

        override fun submitRedstoneInput(packet: Int) = record("submitRedstoneInput") { }

        override fun confirmRedstoneOutput(packed: Int) = record("confirmRedstoneOutput") { }

        override fun close() =
            record("close") {
                closeCalls++
                Unit
            }

        private fun metadata() = VmFileMetadata(VmFileKind.FILE, 2, 7, true)

        private fun <T> record(
            operation: String,
            action: () -> T,
        ): T {
            calls += "$operation:${Thread.currentThread().name}"
            return action()
        }
    }

    private class RecordingCandidate(
        private val calls: MutableList<String>,
    ) : ProgramDeploymentCandidate {
        var closed = false

        override fun close() {
            calls += "candidateClose:${Thread.currentThread().name}"
            closed = true
        }
    }

    private companion object {
        val SOUND = CapabilityIdentity("compukter", "sound", 1, 0)
        val REDSTONE = CapabilityIdentity("compukter", "redstone", 1, 0)
        const val TIMEOUT_SECONDS = 5L
    }
}
