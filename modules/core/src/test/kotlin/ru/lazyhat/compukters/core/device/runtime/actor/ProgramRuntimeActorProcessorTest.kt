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

import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSession
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSessionFactory
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryEntry
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileKind
import ru.lazyhat.compukters.lang.runtime.fs.VmFileMetadata
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgramRuntimeActorProcessorTest {
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
        val candidate = RecordingCandidate(calls)
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

        override fun advance(
            guestBudget: Int,
            maintenanceBudget: Int,
            hostRequestBudget: Int,
        ): VmOutcome = record("advance") { VmOutcome.SliceExhausted }

        override fun resume(
            identity: VmHostRequestIdentity,
            response: HostResponse,
        ) = record("resume") { }

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
                verifiedArtifact = artifact.copyOf()
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
        const val TIMEOUT_SECONDS = 5L
    }
}
