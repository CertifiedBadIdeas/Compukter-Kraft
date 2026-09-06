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

package ru.lazyhat.compukters.core.device.computer

import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision

class ProgramComputer internal constructor(
    private val deviceId: Int,
    private val stateSink: ProgramComputerStateSink,
    private val host: ProgramHost,
) : AutoCloseable {
    constructor(
        deviceId: Int,
        stateSink: ProgramComputerStateSink,
        store: WorldFileSystemStore,
        computerId: ComputerId,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        redstoneHostPort: RedstoneHostPort =
            RedstoneHostPort { RedstoneCommitResult.Failed(HostFailureKind.UNAVAILABLE, 0) },
        initialRedstoneOutput: Int = 0,
    ) : this(
        deviceId,
        stateSink,
        RuntimeProgramHost(
            ProgramRuntimeHost(
                store = store,
                computerId = computerId,
                romImage = romImage,
                tickBudget = tickBudget,
                compilerRouter = compilerRouter,
                redstoneHostPort = redstoneHostPort,
                initialRedstoneOutput = initialRedstoneOutput,
            ),
        ),
    )

    var state: ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
        private set

    fun turnOn(): ProgramComputerState {
        if (state == ProgramComputerState.Closed || state.isPoweredOn()) return state
        return startBoot()
    }

    private fun startBoot(): ProgramComputerState =
        when (val result = host.startBoot()) {
            ProgramStartResult.Started -> transitionTo(ProgramComputerState.Running)
            is ProgramStartResult.Rejected -> transitionTo(failure(ProgramComputerFailure.Runtime(result.failure)))
            ProgramStartResult.Closed -> transitionTo(failure(ProgramComputerFailure.RuntimeContract(host.state)))
        }

    fun serverTick(): ProgramComputerState {
        if (!state.isPoweredOn()) return state
        val runtimeState = host.serverTick()
        return transitionFrom(runtimeState)
    }

    fun terminalFullState(): TerminalState? = host.terminalFullState()

    fun terminalChangesSince(revision: Long): TerminalUpdate? = host.terminalChangesSince(revision)

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): Boolean = state.isPoweredOn() && host.sendTerminalKey(key, action, modifiers)

    fun sendTerminalText(value: String): Boolean = state.isPoweredOn() && host.sendTerminalText(value)

    fun filesystemGeneration(): Long? = host.filesystemGeneration()

    fun fileStat(path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath) = host.fileStat(path)

    fun fileList(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ) = host.fileList(path, startAfter, maximumEntries)

    fun fileRead(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ) = host.fileRead(path, offset, maximumBytes, expectedGeneration)

    fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate? = host.verifyForDeploy(artifact)

    fun executableRevision(path: String): VmExecutableRevision? = host.executableRevision(path)

    fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision? = host.deploy(path, expected, candidate)

    fun submitCanonicalLine(line: CharArray): Boolean = host.submitCanonicalLine(line)

    fun submitRedstoneInput(packet: Int): Boolean = state.isPoweredOn() && host.submitRedstoneInput(packet)

    fun shutdown() {
        if (state == ProgramComputerState.Closed || state == SHUTDOWN_STATE) return
        host.shutdown()
        transitionTo(SHUTDOWN_STATE)
    }

    fun reboot(): ProgramComputerState {
        if (state == ProgramComputerState.Closed) return state
        host.shutdown()
        return startBoot()
    }

    override fun close() {
        if (state == ProgramComputerState.Closed) return
        host.close()
        transitionTo(ProgramComputerState.Closed)
    }

    private fun transitionTo(next: ProgramComputerState): ProgramComputerState {
        if (next == state) return state
        state = next
        stateSink.publishState(deviceId, next)
        return state
    }

    private fun failure(failure: ProgramComputerFailure): ProgramComputerState.PoweredOff =
        ProgramComputerState.PoweredOff(ProgramComputerStopReason.Failure(failure))

    private fun transitionFrom(runtimeState: ProgramRuntimeState): ProgramComputerState =
        when (runtimeState) {
            ProgramRuntimeState.Running -> {
                transitionTo(ProgramComputerState.Running)
            }

            ProgramRuntimeState.WaitingForInput -> {
                transitionTo(ProgramComputerState.WaitingForInput)
            }

            ProgramRuntimeState.WaitingForCompiler -> {
                transitionTo(ProgramComputerState.WaitingForCompiler)
            }

            is ProgramRuntimeState.Halted -> {
                transitionTo(ProgramComputerState.PoweredOff(ProgramComputerStopReason.Halted(runtimeState.value)))
            }

            is ProgramRuntimeState.Failed -> {
                transitionTo(failure(ProgramComputerFailure.Runtime(runtimeState.failure)))
            }

            ProgramRuntimeState.Idle,
            ProgramRuntimeState.Closed,
            -> {
                transitionTo(failure(ProgramComputerFailure.RuntimeContract(runtimeState)))
            }
        }

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this == ProgramComputerState.Running ||
            this == ProgramComputerState.WaitingForInput ||
            this == ProgramComputerState.WaitingForCompiler

    private companion object {
        val SHUTDOWN_STATE = ProgramComputerState.PoweredOff(ProgramComputerStopReason.Shutdown)
    }
}
