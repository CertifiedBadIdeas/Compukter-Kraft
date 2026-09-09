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

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationAddress
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationOutcome
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilationRequest
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompiler
import ru.lazyhat.compukters.core.device.runtime.compiler.ComputerCompilerCompletion
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmBootException
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProgramRuntimeHostTest {
    @Test
    fun `deferred redstone commit suspends advancement until the matching host completion`() {
        val request = redstoneSide(1, 2, 7)
        val candidate = RedstoneWire.replaceOutput(0, 2, 7)
        val session = ScriptedSession(outcomes = listOf(VmOutcome.HostRequestBatch(listOf(request)), VmOutcome.Halted(null)))
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { session },
                redstoneHostPort = RedstoneHostPort { RedstoneCommitResult.Deferred },
            )
        host.start(byteArrayOf(1))
        session.confirmedOutputs.clear()

        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(emptyList(), session.responses)
        assertEquals(1, session.advances.size)
        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(1, session.advances.size)
        assertFalse(host.completeRedstoneOutput(candidate + 1, RedstoneCommitResult.Committed))

        assertTrue(host.completeRedstoneOutput(candidate, RedstoneCommitResult.Committed))
        assertEquals(listOf(candidate), session.confirmedOutputs)
        assertEquals(listOf(response(1, 1, HostResponse.UnitSuccess)), session.responses)
        assertEquals(ProgramRuntimeState.Halted(null), host.serverTick())
    }

    @Test
    fun `redstone batch commits once confirms before every success and stops this tick`() {
        val events = mutableListOf<String>()
        val requests =
            listOf(
                redstoneSide(1, 2, 7),
                redstoneAll(2, RedstoneWire.REGISTER_MASK),
                redstoneSide(3, 4, 0),
            )
        val candidate = RedstoneWire.replaceOutput(RedstoneWire.REGISTER_MASK, 4, 0)
        val session =
            ScriptedSession(
                outcomes = listOf(VmOutcome.HostRequestBatch(requests), VmOutcome.Halted(null)),
                lifecycleEvents = events,
            )
        val committed = mutableListOf<Int>()
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { session },
                tickBudget = ProgramTickBudget(8, 4, 2),
                redstoneHostPort =
                    RedstoneHostPort { packed ->
                        events += "port:$packed"
                        committed += packed
                        RedstoneCommitResult.Committed
                    },
            )
        host.start(byteArrayOf(1))
        events.clear()

        assertEquals(ProgramRuntimeState.Running, host.serverTick())

        assertEquals(listOf(candidate), committed)
        assertEquals(1, session.advances.size)
        assertEquals(
            listOf("port:$candidate", "confirm:$candidate", "resume:1", "resume:2", "resume:3"),
            events,
        )
        assertEquals(requests.map { response(it.taskId, it.id, HostResponse.UnitSuccess) }, session.responses)
    }

    @Test
    fun `redstone port failure fails every request while a no-op skips the physical port`() {
        val failure = RedstoneCommitResult.Failed(HostFailureKind.INPUT_OUTPUT, 9)
        val failedSession = ScriptedSession(outcomes = listOf(VmOutcome.HostRequestBatch(listOf(redstoneSide(1, 0, 4)))))
        val failedHost =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { failedSession },
                redstoneHostPort = RedstoneHostPort { failure },
            )
        failedHost.start(byteArrayOf(1))
        failedSession.confirmedOutputs.clear()

        failedHost.serverTick()

        assertEquals(emptyList(), failedSession.confirmedOutputs)
        assertEquals(
            listOf(response(1, 1, HostResponse.Failure(HostFailureKind.INPUT_OUTPUT, 9))),
            failedSession.responses,
        )

        var commits = 0
        val noOpSession = ScriptedSession(outcomes = listOf(VmOutcome.HostRequestBatch(listOf(redstoneAll(2, 7)))))
        val noOpHost =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { noOpSession },
                initialRedstoneOutput = 7,
                redstoneHostPort =
                    RedstoneHostPort {
                        commits++
                        RedstoneCommitResult.Committed
                    },
            )
        noOpHost.start(byteArrayOf(1))
        noOpSession.confirmedOutputs.clear()

        noOpHost.serverTick()

        assertEquals(0, commits)
        assertEquals(emptyList(), noOpSession.confirmedOutputs)
        assertEquals(listOf(response(1, 2, HostResponse.UnitSuccess)), noOpSession.responses)
    }

    @Test
    fun `replacement seeds confirmed output and the latest complete input snapshot`() {
        val first = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val second = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val sessions = ArrayDeque(listOf(first, second))
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() },
                initialRedstoneOutput = 17,
            )
        host.start(byteArrayOf(1))
        val packet = RedstoneWire.packInput(1 shl 2, intArrayOf(1, 2, 3, 4, 5, 6))
        assertTrue(host.submitRedstoneInput(packet))

        host.start(byteArrayOf(2))

        assertEquals(listOf(17), second.confirmedOutputs)
        assertEquals(listOf(RedstoneWire.withAllInputSidesChanged(packet)), second.redstoneInputs)
        assertEquals(0, second.advances.size)
    }

    @Test
    fun `lifecycle transitions never synthesize a redstone reset`() {
        val first = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val second = ScriptedSession(outcomes = listOf(VmOutcome.Faulted(VmFault.CORRUPT_LIFECYCLE)))
        val sessions = ArrayDeque(listOf(first, second))
        val commits = mutableListOf<Int>()
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() },
                initialRedstoneOutput = 23,
                redstoneHostPort =
                    RedstoneHostPort {
                        commits += it
                        RedstoneCommitResult.Committed
                    },
            )

        host.start(byteArrayOf(1))
        host.shutdown()
        host.start(byteArrayOf(2))
        host.serverTick()
        host.close()

        assertEquals(emptyList(), commits)
    }

    @Test
    fun `deployment facade delegates to the active VM session without exposing its handle`() {
        val candidate = fakeDeploymentCandidate()
        val session =
            ScriptedSession(
                defaultOutcome = VmOutcome.SliceExhausted,
                deploymentCandidate = candidate,
                executableRevision =
                    ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
                        .Present(4),
                deployedRevision =
                    ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
                        .Present(5),
            )
        val host = host(session)
        host.start(byteArrayOf(1))
        val artifact = byteArrayOf(7, 8, 9)

        assertSame(candidate, host.verifyForDeploy(artifact))
        artifact[0] = 0
        assertEquals(listOf<Byte>(7, 8, 9), session.verifiedArtifacts.single())
        assertEquals(
            ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
                .Present(4),
            host.executableRevision("/home/demo"),
        )
        assertEquals(
            ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
                .Present(5),
            host.deploy(
                "/home/demo",
                ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
                    .Present(4),
                candidate,
            ),
        )
        val line = "/home/demo".toCharArray()
        assertTrue(host.submitCanonicalLine(line))
        line[0] = 'X'

        assertEquals(listOf("/home/demo"), session.revisionPaths)
        assertEquals(listOf("/home/demo"), session.deploymentPaths)
        assertEquals(listOf("/home/demo"), session.canonicalLines)
    }

    @Test
    fun `deployment facade is unavailable before boot and after shutdown`() {
        val host = host(ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted))

        assertNull(host.verifyForDeploy(byteArrayOf(1)))
        assertNull(host.executableRevision("/home/demo"))
        assertFalse(host.submitCanonicalLine("/home/demo".toCharArray()))

        host.start(byteArrayOf(1))
        host.shutdown()
        assertNull(host.verifyForDeploy(byteArrayOf(1)))
    }

    @Test
    fun `compilation waits without advancing and applies completion on a tick before resuming`() {
        val sourceBytes = "fun main() {}".encodeToByteArray()
        val session =
            ScriptedSession(
                outcomes =
                    listOf(
                        VmOutcome.CompilationRequested(
                            VmCompilationRequest(7, listOf(VmCompilationSource("/home/main.kt", sourceBytes))),
                        ),
                    ),
                defaultOutcome = VmOutcome.SliceExhausted,
            )
        val compiler = FakeComputerCompiler()
        val router = CompilerCompletionRouter(compiler, maximumCompletionsPerDrain = 2)
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { session },
                tickBudget = ProgramTickBudget(8, 4, 1),
                computerId = ComputerId.fromLongs(10, 20),
                compilerRouter = router,
            )
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.WaitingForCompiler, host.serverTick())
        assertEquals(1, session.advances.size)
        assertEquals(1, compiler.submissions.size)
        assertEquals(
            sourceBytes.toList(),
            compiler.submissions
                .single()
                .sources
                .single()
                .utf8Bytes()
                .toList(),
        )
        assertTrue(host.sendTerminalText("queued while compiling"))
        assertEquals(listOf("queued while compiling"), session.terminalTexts)

        assertEquals(ProgramRuntimeState.WaitingForCompiler, host.serverTick())
        assertEquals(1, session.advances.size)
        val address = compiler.submissions.single().address
        compiler.completions +=
            ComputerCompilerCompletion(
                address,
                ComputerCompilationOutcome.Success(byteArrayOf(4, 5)),
            )

        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(listOf(address.token to byteArrayOf(4, 5).toList()), session.compilationArtifacts)
        assertEquals(1, session.advances.size)

        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(2, session.advances.size)
    }

    @Test
    fun `a compiler wait does not stop another computer from advancing`() {
        val compiler = FakeComputerCompiler()
        val router = CompilerCompletionRouter(compiler)
        val waitingSession =
            ScriptedSession(
                outcomes = listOf(compilationRequest(token = 1)),
                defaultOutcome = VmOutcome.SliceExhausted,
            )
        val runningSession = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val waitingHost = compilerHost(waitingSession, router, computer = 1)
        val runningHost = compilerHost(runningSession, router, computer = 2)
        waitingHost.start(byteArrayOf(1))
        runningHost.start(byteArrayOf(2))

        assertEquals(ProgramRuntimeState.WaitingForCompiler, waitingHost.serverTick())
        assertEquals(ProgramRuntimeState.Running, runningHost.serverTick())
        assertEquals(1, waitingSession.advances.size)
        assertEquals(1, runningSession.advances.size)
        assertEquals(ProgramRuntimeState.WaitingForCompiler, waitingHost.serverTick())
        assertEquals(ProgramRuntimeState.Running, runningHost.serverTick())
        assertEquals(1, waitingSession.advances.size)
        assertEquals(2, runningSession.advances.size)
    }

    @Test
    fun `replacement cancels the old epoch before close and a repeated token gets a new address`() {
        val events = mutableListOf<String>()
        val compiler = FakeComputerCompiler(events)
        val router = CompilerCompletionRouter(compiler)
        val first = ScriptedSession(outcomes = listOf(compilationRequest(token = 5)), closeEvent = { events += "close" })
        val second = ScriptedSession(outcomes = listOf(compilationRequest(token = 5)))
        val sessions = ArrayDeque(listOf(first, second))
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() },
                tickBudget = ProgramTickBudget(8, 4, 1),
                computerId = ComputerId.fromLongs(0, 11),
                compilerRouter = router,
            )
        host.start(byteArrayOf(1))
        host.serverTick()
        val oldAddress = compiler.submissions.single().address

        host.start(byteArrayOf(2))
        host.serverTick()
        val newAddress = compiler.submissions.last().address

        assertEquals(listOf("cancel", "close"), events)
        assertEquals(oldAddress.computerId, newAddress.computerId)
        assertEquals(oldAddress.token, newAddress.token)
        assertEquals(oldAddress.vmEpoch + 1, newAddress.vmEpoch)
    }

    @Test
    fun `compilation completion bridge failure closes the VM as a typed bridge failure`() {
        val compiler = FakeComputerCompiler()
        val router = CompilerCompletionRouter(compiler)
        val session =
            ScriptedSession(
                outcomes = listOf(compilationRequest(token = 3)),
                completionError = VmBridgeException("completion bridge failed"),
            )
        val host = compilerHost(session, router, computer = 1)
        host.start(byteArrayOf(1))
        host.serverTick()
        val address = compiler.submissions.single().address
        compiler.completions +=
            ComputerCompilerCompletion(address, ComputerCompilationOutcome.Success(byteArrayOf(1)))

        assertEquals(
            ProgramRuntimeState.Failed(ProgramFailure.Bridge("completion bridge failed")),
            host.serverTick(),
        )
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `boot opens a ROM session without artifact bytes`() {
        val session = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        var bootCalls = 0
        val host =
            ProgramRuntimeHost(
                sessionFactory =
                    object : ProgramVmSessionFactory {
                        override fun open(artifact: ByteArray): ProgramVmSession = error("artifact launch is not expected")

                        override fun boot(): ProgramVmSession {
                            bootCalls++
                            return session
                        }
                    },
            )

        assertEquals(ProgramStartResult.Started, host.startBoot())
        assertEquals(1, bootCalls)
        assertEquals(ProgramRuntimeState.Running, host.state)
    }

    @Test
    fun `execution limits must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 0,
                maintenanceBudgetPerAdvance = 1,
                maximumAdvancesPerTick = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 1,
                maintenanceBudgetPerAdvance = 0,
                maximumAdvancesPerTick = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 1,
                maintenanceBudgetPerAdvance = 1,
                maximumAdvancesPerTick = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(hostRequestsPerTick = 0)
        }
    }

    @Test
    fun `defaults expose a bounded idle runtime model`() {
        assertEquals(4_096, ProgramTickBudget().guestBudgetPerAdvance)
        assertEquals(256, ProgramTickBudget().maintenanceBudgetPerAdvance)
        assertEquals(32, ProgramTickBudget().maximumAdvancesPerTick)
        assertEquals(16, ProgramTickBudget().hostRequestsPerTick)
        assertEquals(ProgramRuntimeState.Idle, ProgramRuntimeState.Idle)
    }

    @Test
    fun `valid artifact starts one session in running state`() {
        val session = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val host = host(session)

        assertEquals(ProgramRuntimeState.Idle, host.state)
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(1, 2, 3)))
        assertEquals(ProgramRuntimeState.Running, host.state)
    }

    @Test
    fun `tick never advances more than configured maximum`() {
        val session = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val host = host(session, ProgramTickBudget(7, 3, 4))
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(1)))

        assertEquals(ProgramRuntimeState.Running, host.serverTick())

        assertEquals(
            listOf(
                AdvanceBudget(7, 3, 16),
                AdvanceBudget(7, 3, 16),
                AdvanceBudget(7, 3, 16),
                AdvanceBudget(7, 3, 16),
            ),
            session.advances,
        )
    }

    @Test
    fun `tick shares host credits and stops only when vm cannot progress`() {
        val addon = CapabilityIdentity("addon", "device", 1, 0)

        fun batch(
            first: Long,
            count: Int,
        ): VmOutcome.HostRequestBatch =
            VmOutcome.HostRequestBatch(
                List(count) { offset ->
                    VmHostRequest(
                        id = first + offset,
                        capability = addon,
                        operation = 0,
                        arguments = emptyList(),
                        taskId = if (offset % 2 == 0) 1 else 2,
                    )
                },
            )
        val session =
            ScriptedSession(
                outcomes =
                    listOf(
                        batch(1, 10),
                        batch(11, 6),
                        VmOutcome.SliceExhausted,
                        VmOutcome.WaitingForHostQuota,
                    ),
            )
        val host = host(session, ProgramTickBudget(8, 4, 4, hostRequestsPerTick = 16))
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(
            listOf(
                AdvanceBudget(8, 4, 16),
                AdvanceBudget(8, 4, 6),
                AdvanceBudget(8, 4, 0),
                AdvanceBudget(8, 4, 0),
            ),
            session.advances,
        )
    }

    @Test
    fun `host credits refill after quota wait on the next tick`() {
        val session =
            ScriptedSession(
                outcomes = listOf(VmOutcome.WaitingForHostQuota, VmOutcome.Halted(null)),
            )
        val host = host(session, ProgramTickBudget(8, 4, 1, hostRequestsPerTick = 16))
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(ProgramRuntimeState.Halted(null), host.serverTick())
        assertEquals(
            listOf(AdvanceBudget(8, 4, 16), AdvanceBudget(8, 4, 16)),
            session.advances,
        )
    }

    @Test
    fun `Rust terminal is committed once per active tick and remains available after halt`() {
        val terminal = terminalState(revision = 7)
        val session =
            ScriptedSession(
                outcomes = listOf(VmOutcome.WaitingForTerminalEvent, VmOutcome.Halted(VmValue.I32(0))),
                terminalState = terminal,
                terminalUpdate = TerminalUpdate.Unchanged(7),
            )
        val host = host(session, ProgramTickBudget(8, 4, 1))
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.WaitingForInput, host.serverTick())
        assertEquals(1, session.terminalCommits)
        assertEquals(terminal, host.terminalFullState())
        assertEquals(TerminalUpdate.Unchanged(7), host.terminalChangesSince(7))
        host.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS, setOf(TerminalModifier.SHIFT))
        host.sendTerminalText("λ😀")
        assertEquals(ProgramRuntimeState.Halted(VmValue.I32(0)), host.serverTick())
        assertEquals(2, session.terminalCommits)
        assertEquals(0, session.closeCalls)
        assertEquals(terminal, host.terminalFullState())
        host.shutdown()
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `canonical line wakes a runtime waiting for input`() {
        val session =
            ScriptedSession(
                outcomes = listOf(VmOutcome.WaitingForTerminalEvent, VmOutcome.Halted(VmValue.I32(0))),
            )
        val host = host(session, ProgramTickBudget(8, 4, 1))
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.WaitingForInput, host.serverTick())
        assertTrue(host.submitCanonicalLine("/home/demo".toCharArray()))
        assertEquals(ProgramRuntimeState.Halted(VmValue.I32(0)), host.serverTick())
        assertEquals(listOf("/home/demo"), session.canonicalLines)
    }

    @Test
    fun `typed creation failures never publish running`() {
        val cases =
            listOf(
                VmVerificationException() to ProgramFailure.Verification,
                VmAdmissionException(7) to ProgramFailure.Admission(7),
                VmStartException(8) to ProgramFailure.Start(8),
                VmBridgeException("bridge down") to ProgramFailure.Bridge("bridge down"),
            )

        cases.forEach { (error, expected) ->
            val host =
                ProgramRuntimeHost(
                    sessionFactory = ProgramVmSessionFactory { throw error },
                )

            assertEquals(ProgramStartResult.Rejected(expected), host.start(byteArrayOf(1)))
            assertEquals(ProgramRuntimeState.Failed(expected), host.state)
        }
    }

    @Test
    fun `typed boot failure is a bounded start rejection`() {
        val host =
            ProgramRuntimeHost(
                sessionFactory =
                    object : ProgramVmSessionFactory {
                        override fun open(artifact: ByteArray): ProgramVmSession = error("artifact launch is not expected")

                        override fun boot(): ProgramVmSession = throw VmBootException(9)
                    },
            )

        assertEquals(ProgramStartResult.Rejected(ProgramFailure.Start(9)), host.startBoot())
        assertEquals(ProgramRuntimeState.Failed(ProgramFailure.Start(9)), host.state)
    }

    @Test
    fun `starting a replacement closes the previous session exactly once`() {
        val first = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val second = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val sessions = ArrayDeque(listOf(first, second))
        val host = ProgramRuntimeHost(sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() })

        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(1)))
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(2)))

        assertEquals(1, first.closeCalls)
        assertEquals(0, second.closeCalls)
        assertEquals(ProgramRuntimeState.Running, host.state)
    }

    @Test
    fun `active session publishes its current filesystem generation`() {
        val session = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted, filesystemGeneration = 7)
        val host = host(session)

        host.start(byteArrayOf(1))
        assertEquals(7, host.filesystemGeneration())
        host.shutdown()
        assertNull(host.filesystemGeneration())
    }

    @Test
    fun `halt retains its session while VM failures release theirs`() {
        val cases =
            listOf(
                Triple(VmOutcome.Halted(VmValue.I32(42)), ProgramRuntimeState.Halted(VmValue.I32(42)), 0),
                Triple(
                    VmOutcome.AllocationExhausted(true),
                    ProgramRuntimeState.Failed(ProgramFailure.Allocation(collectionAttempted = true)),
                    1,
                ),
                Triple(
                    VmOutcome.QuotaExhausted(QuotaKind.HOST_REQUESTS, 10, 11),
                    ProgramRuntimeState.Failed(ProgramFailure.Quota(QuotaKind.HOST_REQUESTS, 10, 11)),
                    1,
                ),
                Triple(
                    VmOutcome.Crashed(GuestTrap.DIVISION_BY_ZERO),
                    ProgramRuntimeState.Failed(ProgramFailure.Trap(GuestTrap.DIVISION_BY_ZERO)),
                    1,
                ),
                Triple(
                    VmOutcome.Faulted(VmFault.CORRUPT_LIFECYCLE),
                    ProgramRuntimeState.Failed(ProgramFailure.Fault(VmFault.CORRUPT_LIFECYCLE)),
                    1,
                ),
                Triple(
                    VmOutcome.HostFailed(HostFailureKind.INPUT_OUTPUT, 9),
                    ProgramRuntimeState.Failed(ProgramFailure.Host(HostFailureKind.INPUT_OUTPUT, 9)),
                    1,
                ),
            )

        cases.forEach { (outcome, expected, closeCalls) ->
            val session = ScriptedSession(outcomes = listOf(outcome))
            val host = host(session)
            host.start(byteArrayOf(1))

            assertEquals(expected, host.serverTick())
            assertEquals(expected, host.serverTick())
            assertEquals(closeCalls, session.closeCalls)
            assertEquals(1, session.advances.size)
        }
    }

    @Test
    fun `shutdown is reusable while close is permanent and both are idempotent`() {
        val first = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val second = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val sessions = ArrayDeque(listOf(first, second))
        val host = ProgramRuntimeHost(sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() })
        host.start(byteArrayOf(1))

        host.shutdown()
        host.shutdown()
        assertEquals(ProgramRuntimeState.Idle, host.state)
        assertEquals(1, first.closeCalls)
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(2)))

        host.close()
        host.close()
        assertEquals(ProgramRuntimeState.Closed, host.state)
        assertEquals(1, second.closeCalls)
        assertEquals(ProgramStartResult.Closed, host.start(byteArrayOf(3)))
        assertEquals(ProgramRuntimeState.Closed, host.serverTick())
    }

    @Test
    fun `addon host requests remain host owned and receive stable unavailable failures`() {
        val unknown = CapabilityIdentity("addon", "terminal", 1, 0)
        val requests =
            VmOutcome.HostRequestBatch(
                listOf(
                    VmHostRequest(1, unknown, 0, listOf(VmValue.StringValue("x")), taskId = 2),
                    VmHostRequest(2, unknown, 0, listOf(VmValue.StringValue("y")), taskId = 3),
                ),
            )
        val session = ScriptedSession(outcomes = listOf(requests), defaultOutcome = VmOutcome.SliceExhausted)
        val host = host(session, ProgramTickBudget(8, 4, 1))
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(
            listOf(
                response(2, 1, HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0)),
                response(3, 2, HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0)),
            ),
            session.responses,
        )
    }

    @Test
    fun `raw terminal wait wakes after accepted input and executes only on the next tick`() {
        val session =
            ScriptedSession(
                outcomes =
                    listOf(
                        VmOutcome.WaitingForTerminalEvent,
                        VmOutcome.Halted(VmValue.StringValue("done")),
                    ),
            )
        val host = host(session)
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.WaitingForInput, host.serverTick())
        assertEquals(1, session.advances.size)
        assertEquals(1, session.terminalCommits)
        assertTrue(host.sendTerminalText("λ😀"))
        assertEquals(listOf("λ😀"), session.terminalTexts)
        assertEquals(ProgramRuntimeState.Running, host.state)
        assertEquals(1, session.advances.size)
        assertEquals(1, session.terminalCommits)

        assertEquals(ProgramRuntimeState.Halted(VmValue.StringValue("done")), host.serverTick())
        assertEquals(2, session.terminalCommits)
    }

    @Test
    fun `replacement and shutdown close the previous retained terminal`() {
        val first =
            ScriptedSession(
                outcomes = listOf(VmOutcome.WaitingForTerminalEvent),
            )
        val second = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val sessions = ArrayDeque(listOf(first, second))
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() },
                tickBudget = ProgramTickBudget(8, 4, 2),
            )
        host.start(byteArrayOf(1))
        assertEquals(ProgramRuntimeState.WaitingForInput, host.serverTick())

        host.start(byteArrayOf(2))

        assertEquals(1, first.closeCalls)
        host.shutdown()
        assertEquals(ProgramRuntimeState.Idle, host.state)
        assertEquals(1, second.closeCalls)
    }

    private fun host(
        session: ScriptedSession,
        budget: ProgramTickBudget = ProgramTickBudget(),
    ): ProgramRuntimeHost =
        ProgramRuntimeHost(
            tickBudget = budget,
            sessionFactory = ProgramVmSessionFactory { session },
        )

    private fun compilerHost(
        session: ScriptedSession,
        router: CompilerCompletionRouter,
        computer: Long,
    ): ProgramRuntimeHost =
        ProgramRuntimeHost(
            sessionFactory = ProgramVmSessionFactory { session },
            tickBudget = ProgramTickBudget(8, 4, 1),
            computerId = ComputerId.fromLongs(0, computer),
            compilerRouter = router,
        )

    private class ScriptedSession(
        outcomes: List<VmOutcome> = emptyList(),
        private val defaultOutcome: VmOutcome? = null,
        private val resumeError: VmBridgeException? = null,
        val terminalState: TerminalState = terminalState(0),
        val terminalUpdate: TerminalUpdate = TerminalUpdate.Unchanged(0),
        private val filesystemGeneration: Long = 0,
        private val deploymentCandidate: ProgramDeploymentCandidate = fakeDeploymentCandidate(),
        private val executableRevision: ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision =
            ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision.Absent,
        private val deployedRevision: ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision =
            ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
                .Present(1),
        private val completionError: VmBridgeException? = null,
        private val closeEvent: (() -> Unit)? = null,
        private val lifecycleEvents: MutableList<String>? = null,
    ) : ProgramVmSession {
        private val outcomes = ArrayDeque(outcomes)
        val advances = mutableListOf<AdvanceBudget>()
        val responses = mutableListOf<Pair<VmHostRequestIdentity, HostResponse>>()
        var closeCalls = 0
        var terminalCommits = 0
        val terminalKeys = mutableListOf<Triple<TerminalKey, TerminalKeyAction, Set<TerminalModifier>>>()
        val terminalTexts = mutableListOf<String>()
        val compilationArtifacts = mutableListOf<Pair<Long, List<Byte>>>()
        val compilationFailures = mutableListOf<Pair<Long, String>>()
        val verifiedArtifacts = mutableListOf<List<Byte>>()
        val revisionPaths = mutableListOf<String>()
        val deploymentPaths = mutableListOf<String>()
        val canonicalLines = mutableListOf<String>()
        val redstoneInputs = mutableListOf<Int>()
        val confirmedOutputs = mutableListOf<Int>()

        override fun advance(
            guestBudget: Int,
            maintenanceBudget: Int,
            hostRequestBudget: Int,
        ): VmOutcome {
            advances += AdvanceBudget(guestBudget, maintenanceBudget, hostRequestBudget)
            return outcomes.removeFirstOrNull() ?: requireNotNull(defaultOutcome) { "no scripted outcome" }
        }

        override fun resume(
            identity: VmHostRequestIdentity,
            response: HostResponse,
        ) {
            resumeError?.let { throw it }
            lifecycleEvents?.add("resume:${identity.requestId}")
            responses += identity to response
        }

        override fun submitRedstoneInput(packet: Int) {
            redstoneInputs += packet
            lifecycleEvents?.add("input:$packet")
        }

        override fun confirmRedstoneOutput(packed: Int) {
            confirmedOutputs += packed
            lifecycleEvents?.add("confirm:$packed")
        }

        override fun completeCompilationArtifact(
            token: Long,
            artifact: ByteArray,
        ) {
            completionError?.let { throw it }
            compilationArtifacts += token to artifact.toList()
        }

        override fun completeCompilationFailure(
            token: Long,
            diagnostics: String,
        ) {
            completionError?.let { throw it }
            compilationFailures += token to diagnostics
        }

        override fun close() {
            closeEvent?.invoke()
            closeCalls++
        }

        override fun commitTerminal() {
            terminalCommits++
        }

        override fun terminalFullState(): TerminalState = terminalState

        override fun terminalChangesSince(revision: Long): TerminalUpdate = terminalUpdate

        override fun sendTerminalKey(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier>,
        ) {
            terminalKeys += Triple(key, action, modifiers)
        }

        override fun sendTerminalText(value: String) {
            terminalTexts += value
        }

        override fun filesystemGeneration(): Long = filesystemGeneration

        override fun fileStat(path: VmVirtualPath): VmFileStat = error("unexpected fileStat($path)")

        override fun fileList(
            path: VmVirtualPath,
            startAfter: String?,
            maximumEntries: Int,
        ): VmDirectoryListing = error("unexpected fileList($path, $startAfter, $maximumEntries)")

        override fun fileRead(
            path: VmVirtualPath,
            offset: Long,
            maximumBytes: Int,
            expectedGeneration: Long,
        ): VmFileChunk = error("unexpected fileRead($path, $offset, $maximumBytes, $expectedGeneration)")

        override fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate {
            verifiedArtifacts += artifact.toList()
            return deploymentCandidate
        }

        override fun executableRevision(path: String): ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision {
            revisionPaths += path
            return executableRevision
        }

        override fun deploy(
            path: String,
            expected: ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision,
            candidate: ProgramDeploymentCandidate,
        ): ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision {
            deploymentPaths += path
            assertEquals(deploymentCandidate, candidate)
            return deployedRevision
        }

        override fun submitCanonicalLine(line: CharArray) {
            canonicalLines += line.concatToString()
        }
    }

    private class FakeComputerCompiler(
        private val events: MutableList<String>? = null,
    ) : ComputerCompiler {
        val submissions = mutableListOf<ComputerCompilationRequest>()
        val cancellations = mutableListOf<ComputerCompilationAddress>()
        val completions = ArrayDeque<ComputerCompilerCompletion>()

        override fun submit(request: ComputerCompilationRequest): CompilerSubmissionResult {
            submissions += request
            return CompilerSubmissionResult.ACCEPTED
        }

        override fun drain(maximum: Int): List<ComputerCompilerCompletion> =
            List(minOf(maximum, completions.size)) { completions.removeFirst() }

        override fun cancel(address: ComputerCompilationAddress): Boolean {
            events?.add("cancel")
            cancellations += address
            return true
        }
    }

    private fun response(
        taskId: Int,
        requestId: Long,
        response: HostResponse,
    ): Pair<VmHostRequestIdentity, HostResponse> = VmHostRequestIdentity(taskId, requestId) to response

    private data class AdvanceBudget(
        val guest: Int,
        val maintenance: Int,
        val hostRequests: Int,
    )

    private companion object {
        val REDSTONE = CapabilityIdentity("compukter", "redstone", 1, 0)

        fun redstoneSide(
            id: Long,
            side: Int,
            output: Int,
        ): VmHostRequest = VmHostRequest(id, REDSTONE, 6, listOf(VmValue.I32(side), VmValue.I32(output)))

        fun redstoneAll(
            id: Long,
            packed: Int,
        ): VmHostRequest = VmHostRequest(id, REDSTONE, 7, listOf(VmValue.I32(packed)))

        fun fakeDeploymentCandidate(): ProgramDeploymentCandidate =
            object : ProgramDeploymentCandidate {
                override fun close() = Unit
            }

        fun compilationRequest(token: Long): VmOutcome.CompilationRequested =
            VmOutcome.CompilationRequested(
                VmCompilationRequest(token, listOf(VmCompilationSource("/home/main.kt", byteArrayOf(1)))),
            )

        fun terminalState(revision: Long): TerminalState =
            TerminalState(
                revision = revision,
                width = 51,
                height = 19,
                cells = List(51 * 19) { TerminalCell(' '.code, 15, 0) },
                cursor = TerminalPosition(0, 0),
                cursorVisible = true,
            )
    }
}
