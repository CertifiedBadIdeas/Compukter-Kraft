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
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmBootException
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException

class ProgramRuntimeHost internal constructor(
    private val sessionFactory: ProgramVmSessionFactory,
    private val tickBudget: ProgramTickBudget = ProgramTickBudget(),
    private val computerId: ComputerId = ComputerId.fromLongs(0, 1),
    private val compilerRouter: CompilerCompletionRouter? = null,
    private val redstoneHostPort: RedstoneHostPort = UNAVAILABLE_REDSTONE_PORT,
    initialRedstoneOutput: Int = 0,
) : AutoCloseable {
    constructor(tickBudget: ProgramTickBudget = ProgramTickBudget()) : this(NativeProgramVmSessionFactory(), tickBudget)

    constructor(
        store: WorldFileSystemStore,
        computerId: ComputerId,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        redstoneHostPort: RedstoneHostPort = UNAVAILABLE_REDSTONE_PORT,
        initialRedstoneOutput: Int = 0,
    ) : this(
        NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, computerId, romImage)),
        tickBudget,
        computerId,
        compilerRouter,
        redstoneHostPort,
        initialRedstoneOutput,
    )

    private var session: ProgramVmSession? = null
    private var vmEpoch = 0L
    private var activeVmEpoch = 0L
    private var pendingCompilation: ComputerCompilationAddress? = null
    private var pendingRedstoneCommit: PendingRedstoneCommit? = null
    internal var lastClosedFileSystemGeneration: Long? = null
        private set
    private var confirmedRedstoneOutput = RedstoneWire.requireOutputRegister(initialRedstoneOutput)
    private var lastRedstoneInput = 0
    var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        private set

    fun start(artifact: ByteArray): ProgramStartResult {
        val launchArtifact = artifact.copyOf()
        return startSession { sessionFactory.open(launchArtifact) }
    }

    fun startBoot(): ProgramStartResult = startSession(sessionFactory::boot)

    private fun startSession(open: () -> ProgramVmSession): ProgramStartResult {
        if (state == ProgramRuntimeState.Closed) return ProgramStartResult.Closed
        releaseSession()
        state = ProgramRuntimeState.Idle
        val openingEpoch = Math.incrementExact(vmEpoch)
        vmEpoch = openingEpoch
        return try {
            session = open()
            requireNotNull(session).confirmRedstoneOutput(confirmedRedstoneOutput)
            requireNotNull(session).submitRedstoneInput(RedstoneWire.withAllInputSidesChanged(lastRedstoneInput))
            activeVmEpoch = openingEpoch
            state = ProgramRuntimeState.Running
            ProgramStartResult.Started
        } catch (_: VmVerificationException) {
            rejectStart(ProgramFailure.Verification)
        } catch (error: VmAdmissionException) {
            rejectStart(ProgramFailure.Admission(error.code))
        } catch (error: VmStartException) {
            rejectStart(ProgramFailure.Start(error.code))
        } catch (error: VmBootException) {
            rejectStart(ProgramFailure.Start(error.code))
        } catch (error: VmBridgeException) {
            releaseSession()
            rejectStart(ProgramFailure.Bridge(error.bridgeDetail()))
        }
    }

    fun serverTick(): ProgramRuntimeState {
        if (state != ProgramRuntimeState.Running && state != ProgramRuntimeState.WaitingForCompiler) return state
        val activeSession = requireNotNull(session)
        compilerRouter?.routeCompletions()
        if (state == ProgramRuntimeState.WaitingForCompiler) {
            applyCompilationCompletion(activeSession)
            return state
        }
        if (pendingRedstoneCommit != null) return state
        advanceForTick(activeSession)
        if (session !== activeSession) return state
        try {
            activeSession.commitTerminal()
        } catch (error: VmBridgeException) {
            return finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
        }
        return state
    }

    private fun advanceForTick(activeSession: ProgramVmSession) {
        var remainingHostRequests = tickBudget.hostRequestsPerTick
        repeat(tickBudget.maximumAdvancesPerTick) {
            val outcome =
                try {
                    activeSession.advance(
                        tickBudget.guestBudgetPerAdvance,
                        tickBudget.maintenanceBudgetPerAdvance,
                        remainingHostRequests,
                    )
                } catch (error: VmBridgeException) {
                    finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
                    return
                }
            when (outcome) {
                VmOutcome.SliceExhausted -> {
                    return@repeat
                }

                VmOutcome.WaitingForTerminalEvent -> {
                    state = ProgramRuntimeState.WaitingForInput
                    return
                }

                VmOutcome.WaitingForHostQuota -> {
                    return
                }

                is VmOutcome.Halted -> {
                    state = ProgramRuntimeState.Halted(outcome.value)
                    return
                }

                is VmOutcome.AllocationExhausted -> {
                    finish(
                        ProgramRuntimeState.Failed(ProgramFailure.Allocation(outcome.collectionAttempted)),
                    )
                    return
                }

                is VmOutcome.QuotaExhausted -> {
                    finish(
                        ProgramRuntimeState.Failed(
                            ProgramFailure.Quota(outcome.kind, outcome.limit, outcome.consumed),
                        ),
                    )
                    return
                }

                is VmOutcome.Crashed -> {
                    finish(ProgramRuntimeState.Failed(ProgramFailure.Trap(outcome.trap)))
                    return
                }

                is VmOutcome.Faulted -> {
                    finish(ProgramRuntimeState.Failed(ProgramFailure.Fault(outcome.fault)))
                    return
                }

                is VmOutcome.HostFailed -> {
                    finish(
                        ProgramRuntimeState.Failed(ProgramFailure.Host(outcome.kind, outcome.code)),
                    )
                    return
                }

                is VmOutcome.HostRequestBatch -> {
                    check(outcome.requests.size <= remainingHostRequests) {
                        "native VM exceeded supplied host request budget"
                    }
                    remainingHostRequests -= outcome.requests.size
                    if (outcome.requests.all(::isRedstoneOutputRequest)) {
                        commitRedstoneBatch(activeSession, outcome.requests)
                        return
                    }
                    for (request in outcome.requests) {
                        if (!resume(request, HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0))) return
                    }
                }

                is VmOutcome.CompilationRequested -> {
                    submitCompilation(activeSession, outcome.request)
                    return
                }
            }
        }
    }

    private fun submitCompilation(
        activeSession: ProgramVmSession,
        request: VmCompilationRequest,
    ) {
        val router = compilerRouter
        if (router == null) {
            completeCompilationFailure(activeSession, request.token, "compiler is unavailable")
            return
        }
        val address = ComputerCompilationAddress(computerId, activeVmEpoch, request.token)
        val submission =
            try {
                router.submit(address, request.sources)
            } catch (error: IllegalArgumentException) {
                completeCompilationFailure(activeSession, request.token, error.message ?: "invalid compilation request")
                return
            }
        when (submission) {
            CompilerSubmissionResult.ACCEPTED -> {
                pendingCompilation = address
                state = ProgramRuntimeState.WaitingForCompiler
            }

            CompilerSubmissionResult.BUSY -> {
                completeCompilationFailure(activeSession, request.token, "compiler is busy")
            }

            CompilerSubmissionResult.CLOSED -> {
                completeCompilationFailure(activeSession, request.token, "compiler is unavailable")
            }
        }
    }

    private fun applyCompilationCompletion(activeSession: ProgramVmSession) {
        val address = requireNotNull(pendingCompilation)
        val outcome = compilerRouter?.take(address) ?: return
        try {
            when (outcome) {
                is ComputerCompilationOutcome.Success -> {
                    activeSession.completeCompilationArtifact(address.token, outcome.artifactBytes())
                }

                is ComputerCompilationOutcome.Failure -> {
                    activeSession.completeCompilationFailure(address.token, outcome.diagnostics)
                }
            }
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            return
        }
        pendingCompilation = null
        state = ProgramRuntimeState.Running
    }

    private fun completeCompilationFailure(
        activeSession: ProgramVmSession,
        token: Long,
        diagnostics: String,
    ) {
        try {
            activeSession.completeCompilationFailure(token, diagnostics)
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
        }
    }

    fun terminalFullState(): TerminalState? = terminalQuery { terminalFullState() }

    fun terminalChangesSince(revision: Long): TerminalUpdate? = terminalQuery { terminalChangesSince(revision) }

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): Boolean = terminalInput { sendTerminalKey(key, action, modifiers) }

    fun sendTerminalText(value: String): Boolean = terminalInput { sendTerminalText(value) }

    fun filesystemGeneration(): Long? = session?.filesystemGeneration()

    fun fileStat(path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath) = session?.fileStat(path)

    fun fileList(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ) = session?.fileList(path, startAfter, maximumEntries)

    fun fileRead(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ) = session?.fileRead(path, offset, maximumBytes, expectedGeneration)

    fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate? = deploymentOperation { verifyForDeploy(artifact.copyOf()) }

    fun executableRevision(path: String): VmExecutableRevision? = deploymentOperation { executableRevision(path) }

    fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision? = deploymentOperation { deploy(path, expected, candidate) }

    fun submitCanonicalLine(line: CharArray): Boolean {
        val activeSession = session ?: return false
        deploymentOperation(activeSession) { submitCanonicalLine(line.copyOf()) }
        if (session !== activeSession) return false
        if (state == ProgramRuntimeState.WaitingForInput) state = ProgramRuntimeState.Running
        return true
    }

    fun submitRedstoneInput(packet: Int): Boolean {
        val validated = RedstoneWire.requireInputPacket(packet)
        lastRedstoneInput = validated
        if (
            state != ProgramRuntimeState.Running &&
            state != ProgramRuntimeState.WaitingForInput &&
            state != ProgramRuntimeState.WaitingForCompiler
        ) {
            return false
        }
        val activeSession = session ?: return false
        return try {
            activeSession.submitRedstoneInput(validated)
            if (state == ProgramRuntimeState.WaitingForInput) state = ProgramRuntimeState.Running
            true
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            false
        }
    }

    fun completeRedstoneOutput(
        packed: Int,
        result: RedstoneCommitResult,
    ): Boolean {
        val validated = RedstoneWire.requireOutputRegister(packed)
        if (result == RedstoneCommitResult.Deferred) return false
        val pending = pendingRedstoneCommit ?: return false
        if (pending.packed != validated || session !== pending.session) return false
        pendingRedstoneCommit = null
        val response =
            when (result) {
                RedstoneCommitResult.Committed -> {
                    confirmedRedstoneOutput = validated
                    try {
                        pending.session.confirmRedstoneOutput(validated)
                    } catch (error: VmBridgeException) {
                        finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
                        return true
                    }
                    HostResponse.UnitSuccess
                }

                is RedstoneCommitResult.Failed -> {
                    HostResponse.Failure(result.kind, result.code)
                }

                RedstoneCommitResult.Deferred -> {
                    error("deferred redstone completion was rejected")
                }
            }
        for (request in pending.requests) {
            if (!resume(request, response)) break
        }
        return true
    }

    fun shutdown() {
        if (state == ProgramRuntimeState.Closed) return
        releaseSession()
        state = ProgramRuntimeState.Idle
    }

    override fun close() {
        if (state == ProgramRuntimeState.Closed) return
        releaseSession()
        state = ProgramRuntimeState.Closed
    }

    private fun resume(
        request: VmHostRequest,
        response: HostResponse,
    ): Boolean =
        try {
            requireNotNull(session).resume(request.identity, response)
            true
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            false
        }

    private fun commitRedstoneBatch(
        activeSession: ProgramVmSession,
        requests: List<VmHostRequest>,
    ) {
        val batch =
            try {
                RedstoneOutputBatch.reduce(confirmedRedstoneOutput, requests)
            } catch (error: IllegalArgumentException) {
                finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.message ?: "invalid redstone output batch")))
                return
            }
        if (session !== activeSession) return
        val response =
            if (batch.packed == confirmedRedstoneOutput) {
                HostResponse.UnitSuccess
            } else {
                when (val result = redstoneHostPort.commitOutput(batch.packed)) {
                    RedstoneCommitResult.Committed -> {
                        if (session !== activeSession) return
                        confirmedRedstoneOutput = batch.packed
                        try {
                            activeSession.confirmRedstoneOutput(batch.packed)
                        } catch (error: VmBridgeException) {
                            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
                            return
                        }
                        HostResponse.UnitSuccess
                    }

                    is RedstoneCommitResult.Failed -> {
                        HostResponse.Failure(result.kind, result.code)
                    }

                    RedstoneCommitResult.Deferred -> {
                        pendingRedstoneCommit = PendingRedstoneCommit(activeSession, batch.packed, requests)
                        return
                    }
                }
            }
        for (request in requests) {
            if (!resume(request, response)) return
        }
    }

    private fun <T> terminalQuery(query: ProgramVmSession.() -> T): T? {
        val activeSession = session ?: return null
        return try {
            activeSession.query()
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            null
        }
    }

    private fun terminalInput(input: ProgramVmSession.() -> Unit): Boolean {
        if (
            state != ProgramRuntimeState.Running &&
            state != ProgramRuntimeState.WaitingForInput &&
            state != ProgramRuntimeState.WaitingForCompiler
        ) {
            return false
        }
        val activeSession = session ?: return false
        return try {
            activeSession.input()
            if (state == ProgramRuntimeState.WaitingForInput) state = ProgramRuntimeState.Running
            true
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            false
        }
    }

    private fun <T> deploymentOperation(operation: ProgramVmSession.() -> T): T? {
        val activeSession = session ?: return null
        return deploymentOperation(activeSession, operation)
    }

    private fun <T> deploymentOperation(
        activeSession: ProgramVmSession,
        operation: ProgramVmSession.() -> T,
    ): T =
        try {
            activeSession.operation()
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            throw error
        }

    private fun rejectStart(failure: ProgramFailure): ProgramStartResult.Rejected {
        state = ProgramRuntimeState.Failed(failure)
        return ProgramStartResult.Rejected(failure)
    }

    private fun finish(finalState: ProgramRuntimeState): ProgramRuntimeState {
        releaseSession()
        state = finalState
        return finalState
    }

    private fun releaseSession() {
        pendingCompilation?.let { compilerRouter?.cancel(it) }
        pendingCompilation = null
        pendingRedstoneCommit = null
        activeVmEpoch = 0
        try {
            try {
                session?.filesystemGeneration()?.let { lastClosedFileSystemGeneration = it }
            } finally {
                session?.close()
            }
        } finally {
            session = null
        }
    }

    private fun VmBridgeException.bridgeDetail(): String = message ?: "native VM bridge failure"

    private companion object {
        val REDSTONE_CAPABILITY = CapabilityIdentity("compukter", "redstone", 1, 0)
        val UNAVAILABLE_REDSTONE_PORT =
            RedstoneHostPort { RedstoneCommitResult.Failed(HostFailureKind.UNAVAILABLE, 0) }

        fun isRedstoneOutputRequest(request: VmHostRequest): Boolean =
            request.capability == REDSTONE_CAPABILITY && request.operation in 6..7
    }

    private data class PendingRedstoneCommit(
        val session: ProgramVmSession,
        val packed: Int,
        val requests: List<VmHostRequest>,
    )
}
