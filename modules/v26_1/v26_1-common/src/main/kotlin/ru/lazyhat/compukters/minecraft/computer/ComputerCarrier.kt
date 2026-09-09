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

package ru.lazyhat.compukters.minecraft.computer

import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.computer.ProgramComputer
import ru.lazyhat.compukters.core.device.computer.ProgramComputerFailure
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStateSink
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramDeploymentFailure
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramDeploymentToken
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorFailure
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing
import ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk
import ru.lazyhat.compukters.lang.runtime.fs.VmFileStat
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadException
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentConflictException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentFileSystemException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentProfileChangedException
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentWrongMachineException
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.util.concurrent.CompletableFuture

internal interface ComputerCarrier : AutoCloseable {
    val state: ProgramComputerState

    fun turnOn(): ProgramComputerState

    fun serverTick(worldTick: Long): ProgramComputerState

    fun terminalFullState(): TerminalState?

    fun terminalFullStateAsync(): CompletableFuture<TerminalState?> = CompletableFuture.completedFuture(terminalFullState())

    fun terminalChangesSince(revision: Long): TerminalUpdate?

    fun terminalChangesSinceAsync(revision: Long): CompletableFuture<TerminalUpdate?> =
        CompletableFuture.completedFuture(terminalChangesSince(revision))

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): Boolean

    fun sendTerminalKeyAsync(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): CompletableFuture<Boolean> = CompletableFuture.completedFuture(sendTerminalKey(key, action, modifiers))

    fun sendTerminalText(value: String): Boolean

    fun sendTerminalTextAsync(value: String): CompletableFuture<Boolean> = CompletableFuture.completedFuture(sendTerminalText(value))

    fun filesystemGeneration(): Long?

    fun fileStat(path: VmVirtualPath): VmFileStat? = null

    fun fileStatAsync(path: VmVirtualPath): CompletableFuture<VmFileStat?> = CompletableFuture.completedFuture(fileStat(path))

    fun fileList(
        path: VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): VmDirectoryListing? = null

    fun fileListAsync(
        path: VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): CompletableFuture<VmDirectoryListing?> = CompletableFuture.completedFuture(fileList(path, startAfter, maximumEntries))

    fun fileRead(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): VmFileChunk? = null

    fun fileReadAsync(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): CompletableFuture<VmFileChunk?> = CompletableFuture.completedFuture(fileRead(path, offset, maximumBytes, expectedGeneration))

    fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate? = null

    fun verifyForDeployAsync(artifact: ByteArray): CompletableFuture<ProgramDeploymentCandidate?> =
        CompletableFuture.completedFuture(verifyForDeploy(artifact))

    fun executableRevision(path: String): VmExecutableRevision? = null

    fun executableRevisionAsync(path: String): CompletableFuture<VmExecutableRevision?> =
        CompletableFuture.completedFuture(executableRevision(path))

    fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision? = null

    fun deployAsync(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): CompletableFuture<VmExecutableRevision?> = CompletableFuture.completedFuture(deploy(path, expected, candidate))

    fun submitCanonicalLine(line: CharArray): Boolean = false

    fun submitCanonicalLineAsync(line: CharArray): CompletableFuture<Boolean> = CompletableFuture.completedFuture(submitCanonicalLine(line))

    fun submitRedstoneInput(packet: Int): Boolean

    fun submitRedstoneInputAsync(packet: Int): CompletableFuture<Boolean> = CompletableFuture.completedFuture(submitRedstoneInput(packet))

    fun reboot(): ProgramComputerState

    fun shutdown()

    fun closeAsync(): CompletableFuture<Long?> {
        val generation = filesystemGeneration()
        close()
        return CompletableFuture.completedFuture(generation)
    }
}

internal fun interface ComputerCarrierFactory {
    fun create(
        deviceId: Int,
        machineEpoch: Long,
        stateSink: ProgramComputerStateSink,
        filesystem: ComputerFileSystemContext?,
        redstoneHostPort: RedstoneHostPort,
        initialRedstoneOutput: Int,
    ): ComputerCarrier?
}

internal object RuntimeComputerCarrierFactory : ComputerCarrierFactory {
    override fun create(
        deviceId: Int,
        machineEpoch: Long,
        stateSink: ProgramComputerStateSink,
        filesystem: ComputerFileSystemContext?,
        redstoneHostPort: RedstoneHostPort,
        initialRedstoneOutput: Int,
    ): ComputerCarrier? {
        val context = requireNotNull(filesystem) { "production computer boot requires a filesystem context" }
        val actorService = context.actorService
        if (actorService != null) {
            val endpoint = VmActorEndpoint(context.computerId, machineEpoch)
            val lease =
                actorService.attachBootable(
                    endpoint,
                    context.store,
                    context.romImage(),
                    compilerRouter = context.compilerRouter,
                    initialRedstoneOutput = initialRedstoneOutput,
                ) ?: return null
            return ActorComputerCarrier(
                deviceId,
                stateSink,
                ActorProgramComputer(actorService, lease, redstoneHostPort),
            )
        }
        return ProgramComputerCarrier(
            ProgramComputer(
                deviceId = deviceId,
                stateSink = stateSink,
                store = context.store,
                computerId = context.computerId,
                romImage = context.romImage(),
                compilerRouter = context.compilerRouter,
                redstoneHostPort = redstoneHostPort,
                initialRedstoneOutput = initialRedstoneOutput,
            ),
        )
    }
}

private class ActorComputerCarrier(
    private val deviceId: Int,
    private val stateSink: ProgramComputerStateSink,
    private val delegate: ActorProgramComputer,
) : ComputerCarrier {
    private var observedState: ProgramComputerState =
        ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)

    override val state: ProgramComputerState
        get() = observedState

    override fun turnOn(): ProgramComputerState {
        delegate.turnOn().whenComplete { _, failure -> if (failure == null) observeRuntime() else actorFailure(failure) }
        return observedState
    }

    override fun serverTick(worldTick: Long): ProgramComputerState {
        delegate.serverTick(worldTick)
        observeRuntime()
        return observedState
    }

    override fun terminalFullState(): TerminalState? = null

    override fun terminalChangesSince(revision: Long): TerminalUpdate? = null

    override fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ) = false

    override fun sendTerminalText(value: String) = false

    override fun filesystemGeneration(): Long? = delegate.fileSystemGeneration

    override fun fileStat(path: VmVirtualPath): VmFileStat? = null

    override fun fileList(
        path: VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): VmDirectoryListing? = null

    override fun fileRead(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): VmFileChunk? = null

    override fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate? = null

    override fun executableRevision(path: String): VmExecutableRevision? = null

    override fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision? = null

    override fun submitCanonicalLine(line: CharArray) = false

    override fun submitRedstoneInput(packet: Int) = false

    override fun terminalFullStateAsync() =
        request(ProgramRuntimeActorCommand::TerminalFullState) {
            (it as ProgramRuntimeActorValue.TerminalStateValue).state
        }

    override fun terminalChangesSinceAsync(revision: Long) =
        request({ ProgramRuntimeActorCommand.TerminalChangesSince(it, revision) }) {
            (it as ProgramRuntimeActorValue.TerminalUpdateValue).update
        }

    override fun sendTerminalKeyAsync(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ) = accepted { ProgramRuntimeActorCommand.SendTerminalKey(it, key, action, modifiers) }

    override fun sendTerminalTextAsync(value: String) = accepted { ProgramRuntimeActorCommand.SendTerminalText(it, value) }

    override fun fileStatAsync(path: VmVirtualPath) =
        request({ ProgramRuntimeActorCommand.FileStat(it, path) }) {
            (it as ProgramRuntimeActorValue.FileStatValue).stat
        }

    override fun fileListAsync(
        path: VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ) = request({ ProgramRuntimeActorCommand.FileList(it, path, startAfter, maximumEntries) }) {
        (it as ProgramRuntimeActorValue.FileListValue).listing
    }

    override fun fileReadAsync(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ) = request({ ProgramRuntimeActorCommand.FileRead(it, path, offset, maximumBytes, expectedGeneration) }) {
        (it as ProgramRuntimeActorValue.FileChunkValue).chunk
    }

    override fun verifyForDeployAsync(artifact: ByteArray): CompletableFuture<ProgramDeploymentCandidate?> =
        request({ ProgramRuntimeActorCommand.PrepareDeployment(it, artifact) }) {
            (it as ProgramRuntimeActorValue.DeploymentPrepared).token?.let(::ActorCandidate) as ProgramDeploymentCandidate?
        }

    override fun executableRevisionAsync(path: String) =
        request({ ProgramRuntimeActorCommand.ExecutableRevision(it, path) }) {
            (it as ProgramRuntimeActorValue.ExecutableRevisionValue).revision
        }

    override fun deployAsync(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): CompletableFuture<VmExecutableRevision?> {
        val token =
            (candidate as? ActorCandidate)?.take() ?: return CompletableFuture.failedFuture(
                IllegalArgumentException("deployment candidate belongs to another runtime"),
            )
        return request({ ProgramRuntimeActorCommand.Deploy(it, path, expected, token) }) {
            (it as ProgramRuntimeActorValue.DeployedRevision).revision
        }
    }

    override fun submitCanonicalLineAsync(line: CharArray) = accepted { ProgramRuntimeActorCommand.SubmitCanonicalLine(it, line) }

    override fun submitRedstoneInputAsync(packet: Int) = accepted { ProgramRuntimeActorCommand.SubmitRedstoneInput(it, packet) }

    override fun reboot(): ProgramComputerState {
        delegate.reboot().whenComplete { _, failure -> if (failure == null) observeRuntime() else actorFailure(failure) }
        return observedState
    }

    override fun shutdown() {
        delegate.shutdown().whenComplete { _, failure -> if (failure == null) observeRuntime() else actorFailure(failure) }
    }

    override fun close() {
        delegate.closeAsync()
        observeRuntime()
    }

    override fun closeAsync(): CompletableFuture<Long?> = delegate.closeAsync().also { observeRuntime() }

    private fun accepted(command: (ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand) =
        request(command) { (it as ProgramRuntimeActorValue.Accepted).accepted }

    private fun <T> request(
        command: (ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
        value: (ProgramRuntimeActorValue) -> T,
    ): CompletableFuture<T> =
        delegate.request(command).thenApply { reply ->
            observeRuntime()
            val rejected = reply.value as? ProgramRuntimeActorValue.Rejected
            if (rejected != null) throw rejected.failure.toException()
            value(reply.value)
        }

    private fun observeRuntime() {
        val next = delegate.state.toComputerState()
        if (next != observedState) {
            observedState = next
            stateSink.publishState(deviceId, next)
        }
    }

    private fun actorFailure(failure: Throwable) {
        val next =
            ProgramComputerState.PoweredOff(
                ProgramComputerStopReason.Failure(
                    ProgramComputerFailure.RuntimeContract(
                        ProgramRuntimeState.Failed(
                            ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
                                .Bridge(failure.message ?: "actor request failed"),
                        ),
                    ),
                ),
            )
        if (next != observedState) {
            observedState = next
            stateSink.publishState(deviceId, next)
        }
    }

    private inner class ActorCandidate(
        private var token: ProgramDeploymentToken?,
    ) : ProgramDeploymentCandidate {
        fun take(): ProgramDeploymentToken = checkNotNull(token).also { token = null }

        override fun close() {
            token?.let { pending -> delegate.request { ProgramRuntimeActorCommand.DiscardDeployment(it, pending) } }
            token = null
        }
    }
}

private fun ProgramRuntimeState.toComputerState(): ProgramComputerState =
    when (this) {
        ProgramRuntimeState.Idle -> {
            ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
        }

        ProgramRuntimeState.Running -> {
            ProgramComputerState.Running
        }

        ProgramRuntimeState.WaitingForInput -> {
            ProgramComputerState.WaitingForInput
        }

        ProgramRuntimeState.WaitingForCompiler -> {
            ProgramComputerState.WaitingForCompiler
        }

        is ProgramRuntimeState.Halted -> {
            ProgramComputerState.PoweredOff(ProgramComputerStopReason.Halted(value))
        }

        is ProgramRuntimeState.Failed -> {
            ProgramComputerState.PoweredOff(
                ProgramComputerStopReason.Failure(ProgramComputerFailure.Runtime(failure)),
            )
        }

        ProgramRuntimeState.Closed -> {
            ProgramComputerState.Closed
        }
    }

private fun ProgramRuntimeActorFailure.toException(): RuntimeException =
    when (this) {
        is ProgramRuntimeActorFailure.FileSystem -> {
            VmFileSystemReadException(failure)
        }

        is ProgramRuntimeActorFailure.InvalidRequest -> {
            IllegalArgumentException(detail)
        }

        is ProgramRuntimeActorFailure.Bridge -> {
            IllegalStateException(detail)
        }

        ProgramRuntimeActorFailure.Verification -> {
            VmVerificationException()
        }

        is ProgramRuntimeActorFailure.CanonicalLine -> {
            VmCanonicalLineException(failure)
        }

        is ProgramRuntimeActorFailure.Deployment -> {
            when (failure) {
                ProgramDeploymentFailure.CONFLICT -> VmDeploymentConflictException()
                ProgramDeploymentFailure.WRONG_MACHINE -> VmDeploymentWrongMachineException()
                ProgramDeploymentFailure.PROFILE_CHANGED -> VmDeploymentProfileChangedException()
                ProgramDeploymentFailure.FILESYSTEM -> VmDeploymentFileSystemException()
                ProgramDeploymentFailure.ADMISSION -> VmDeploymentAdmissionException()
            }
        }
    }

private class ProgramComputerCarrier(
    private val delegate: ProgramComputer,
) : ComputerCarrier {
    override val state: ProgramComputerState
        get() = delegate.state

    override fun turnOn(): ProgramComputerState = delegate.turnOn()

    override fun serverTick(worldTick: Long): ProgramComputerState = delegate.serverTick()

    override fun terminalFullState(): TerminalState? = delegate.terminalFullState()

    override fun terminalChangesSince(revision: Long): TerminalUpdate? = delegate.terminalChangesSince(revision)

    override fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): Boolean = delegate.sendTerminalKey(key, action, modifiers)

    override fun sendTerminalText(value: String): Boolean = delegate.sendTerminalText(value)

    override fun filesystemGeneration(): Long? = delegate.filesystemGeneration()

    override fun fileStat(path: VmVirtualPath): VmFileStat? = delegate.fileStat(path)

    override fun fileList(
        path: VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ): VmDirectoryListing? = delegate.fileList(path, startAfter, maximumEntries)

    override fun fileRead(
        path: VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ): VmFileChunk? = delegate.fileRead(path, offset, maximumBytes, expectedGeneration)

    override fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate? = delegate.verifyForDeploy(artifact)

    override fun executableRevision(path: String): VmExecutableRevision? = delegate.executableRevision(path)

    override fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision? = delegate.deploy(path, expected, candidate)

    override fun submitCanonicalLine(line: CharArray): Boolean = delegate.submitCanonicalLine(line)

    override fun submitRedstoneInput(packet: Int): Boolean = delegate.submitRedstoneInput(packet)

    override fun reboot(): ProgramComputerState = delegate.reboot()

    override fun shutdown() = delegate.shutdown()

    override fun close() = delegate.close()
}
