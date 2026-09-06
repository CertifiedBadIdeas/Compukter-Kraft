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

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision

open class ComputerBlockEntity internal constructor(
    type: BlockEntityType<*>,
    position: BlockPos,
    blockState: BlockState,
    private val carrierFactory: ComputerCarrierFactory,
    private val identity: ComputerIdentityStorage = ComputerIdentityStorage(),
    private val filesystemContextSource: ComputerFileSystemContextSource? = null,
) : BlockEntity(type, position, blockState) {
    constructor(
        type: BlockEntityType<*>,
        position: BlockPos,
        blockState: BlockState,
    ) : this(
        type,
        position,
        blockState,
        RuntimeComputerCarrierFactory,
    )

    constructor(
        type: BlockEntityType<*>,
        position: BlockPos,
        blockState: BlockState,
        filesystemContextSource: ComputerFileSystemContextSource,
    ) : this(
        type,
        position,
        blockState,
        RuntimeComputerCarrierFactory,
        filesystemContextSource = filesystemContextSource,
    )

    private var carrier: ComputerCarrier? = null
    private var filesystemLease: ComputerFileSystemLease? = null
    private var lastMachineId = 0L
    private var committedRedstoneOutput = 0
    private var sampledRedstoneInputs = IntArray(RedstoneWire.SIDE_COUNT)
    private var dirtyRedstoneInputs = RedstoneWire.ALL_SIDES_MASK
    private val redstoneHostPort = RedstoneHostPort(::commitRedstoneOutput)

    var terminalMachineId: Long? = null
        private set

    var runtimeState: ProgramComputerState = neverStarted()
        private set

    fun computerId() = identity.id()

    fun terminalFullState(): TerminalState? = carrier?.terminalFullState()

    fun prepareTerminal(): TerminalState? {
        val current = carrier ?: createCarrier().also { carrier = it }
        if (current.state == neverStarted()) runtimeState = current.turnOn()
        return current.terminalFullState()
    }

    fun terminalChangesSince(revision: Long): TerminalUpdate? = carrier?.terminalChangesSince(revision)

    fun submitTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): Boolean = carrier?.sendTerminalKey(key, action, modifiers) == true

    fun submitTerminalText(value: String): Boolean = carrier?.sendTerminalText(value) == true

    fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate? = carrier?.verifyForDeploy(artifact)

    fun executableRevision(path: String): VmExecutableRevision? = carrier?.executableRevision(path)

    fun fileStat(path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath) = carrier?.fileStat(path)

    fun fileList(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        startAfter: String?,
        maximumEntries: Int,
    ) = carrier?.fileList(path, startAfter, maximumEntries)

    fun fileRead(
        path: ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath,
        offset: Long,
        maximumBytes: Int,
        expectedGeneration: Long,
    ) = carrier?.fileRead(path, offset, maximumBytes, expectedGeneration)

    fun deploy(
        path: String,
        expected: VmExecutableRevision,
        candidate: ProgramDeploymentCandidate,
    ): VmExecutableRevision? = carrier?.deploy(path, expected, candidate)

    fun submitCanonicalLine(line: CharArray): Boolean = carrier?.submitCanonicalLine(line) == true

    internal fun redstoneOutput(direction: Direction): Int =
        redstoneOutputField(committedRedstoneOutput, localSide(blockState.getValue(ComputerBlock.FACING), direction))

    internal fun markRedstoneInputDirty(direction: Direction? = null) {
        dirtyRedstoneInputs =
            if (direction == null) {
                RedstoneWire.ALL_SIDES_MASK
            } else {
                dirtyRedstoneInputs or (1 shl localSide(blockState.getValue(ComputerBlock.FACING), direction).ordinal)
            }
    }

    internal fun sampleRedstoneInputs(sample: (Direction) -> Int): Int? {
        val dirty = dirtyRedstoneInputs
        var changed = 0
        LocalRedstoneSide.entries.forEach { side ->
            val bit = 1 shl side.ordinal
            if (dirty and bit == 0) return@forEach
            val level = sample(worldDirection(blockState.getValue(ComputerBlock.FACING), side))
            require(level in 0..RedstoneWire.SIGNAL_MASK) { "vanilla redstone level is out of range" }
            if (sampledRedstoneInputs[side.ordinal] != level) {
                sampledRedstoneInputs[side.ordinal] = level
                changed = changed or bit
            }
        }
        dirtyRedstoneInputs = 0
        return changed.takeIf { it != 0 }?.let { RedstoneWire.packInput(it, sampledRedstoneInputs) }
    }

    internal fun serverTick() {
        val current = carrier ?: createCarrier().also { carrier = it }
        if (current.state == neverStarted()) {
            runtimeState = current.turnOn()
        }
        val serverLevel = level as? ServerLevel
        if (serverLevel != null) {
            sampleRedstoneInputs { direction ->
                serverLevel.getSignal(blockPos.relative(direction), direction)
            }?.let(current::submitRedstoneInput)
        }
        if (runtimeState.isPoweredOn()) {
            runtimeState = current.serverTick()
        }
    }

    override fun setRemoved() {
        closeCarrier()
        super.setRemoved()
    }

    internal fun destroyFileSystem() {
        val serverLevel = level as? ServerLevel ?: return
        val source = filesystemContextSource ?: return
        closeCarrier()
        source.tombstone(serverLevel, identity.id())
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        closeCarrier()
        val payload = input.child(ROOT_KEY).orElse(null)
        identity.load(payload)
        committedRedstoneOutput =
            payload?.getInt(REDSTONE_OUTPUT_KEY)?.orElse(0)?.let { packed ->
                runCatching { RedstoneWire.requireOutputRegister(packed) }.getOrDefault(0)
            } ?: 0
        sampledRedstoneInputs = IntArray(RedstoneWire.SIDE_COUNT)
        dirtyRedstoneInputs = RedstoneWire.ALL_SIDES_MASK
        runtimeState = neverStarted()
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        val payload = output.child(ROOT_KEY)
        identity.save(payload)
        payload.putInt(REDSTONE_OUTPUT_KEY, committedRedstoneOutput)
    }

    private fun createCarrier(): ComputerCarrier {
        val deviceId = blockPos.hashCode()
        terminalMachineId = nextMachineId()
        val filesystem =
            (level as? ServerLevel)?.let { serverLevel ->
                filesystemContextSource?.create(serverLevel, identity.id(), SystemRomImage.packaged())
            }
        val created =
            carrierFactory.create(
                deviceId = deviceId,
                stateSink = { _, state -> runtimeState = state },
                filesystem = filesystem,
                redstoneHostPort = redstoneHostPort,
                initialRedstoneOutput = committedRedstoneOutput,
            )
        filesystemLease = filesystem?.attach(created::filesystemGeneration, ::drainCarrier)
        return created
    }

    private fun commitRedstoneOutput(packed: Int): RedstoneCommitResult {
        val serverLevel = requireNotNull(level as? ServerLevel) { "redstone output requires a server level" }
        check(serverLevel.server.isSameThread) { "redstone output must be committed on the server thread" }
        val validated = RedstoneWire.requireOutputRegister(packed)
        if (validated == committedRedstoneOutput) return RedstoneCommitResult.Committed
        val previous = committedRedstoneOutput
        committedRedstoneOutput = validated
        setChanged()
        LocalRedstoneSide.entries.forEach { side ->
            if (redstoneOutputField(previous, side) != redstoneOutputField(validated, side)) {
                val direction = worldDirection(blockState.getValue(ComputerBlock.FACING), side)
                serverLevel.neighborChanged(blockPos.relative(direction), blockState.block, null)
            }
        }
        return RedstoneCommitResult.Committed
    }

    private fun closeCarrier() {
        val current = carrier
        val generation = current?.filesystemGeneration()
        current?.close()
        carrier = null
        terminalMachineId = null
        filesystemLease?.release(generation)
        filesystemLease = null
    }

    private fun drainCarrier(): Long? {
        val current = carrier
        val generation = current?.filesystemGeneration()
        current?.close()
        carrier = null
        terminalMachineId = null
        filesystemLease = null
        return generation
    }

    private fun nextMachineId(): Long {
        lastMachineId = Math.incrementExact(lastMachineId)
        return lastMachineId
    }

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this == ProgramComputerState.Running ||
            this == ProgramComputerState.WaitingForInput ||
            this == ProgramComputerState.WaitingForCompiler

    private companion object {
        const val ROOT_KEY = "compukters"
        const val REDSTONE_OUTPUT_KEY = "redstoneOutput"

        fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
    }
}
