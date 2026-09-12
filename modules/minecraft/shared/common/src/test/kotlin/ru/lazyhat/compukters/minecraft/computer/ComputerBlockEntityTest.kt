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

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStateSink
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerBlockEntityTest {
    @Test
    fun `resource snapshot is requested only from an attached carrier`() {
        val fixture = fixture()
        assertNull(fixture.entity.resourceSnapshotAsync().getNow(null))
        fixture.entity.prepareTerminalAsync()
        val expected = ProgramResourceSnapshot.Unavailable(ProgramRuntimeState.Idle, ProgramTickBudget())
        fixture.carriers.single().resourceSnapshot = expected

        assertEquals(expected, fixture.entity.resourceSnapshotAsync().getNow(null))
        assertEquals(1, fixture.carriers.single().resourceSnapshotCalls)
    }

    @Test
    fun `output register persists exactly and malformed reserved bits sanitize to zero`() {
        val fresh = fixture()
        val freshTag = fresh.entity.saveForTest()
        assertEquals(0, ComputerBlockEntityTestPersistence.redstoneOutput(freshTag))

        val valid = 1 or (17 shl 5) or (31 shl 25)
        val validTag = fresh.entity.saveForTest()
        ComputerBlockEntityTestPersistence.writeRedstoneOutput(validTag, valid)
        val restored = fixture()
        restored.entity.loadForTest(validTag)
        restored.entity.serverTick()
        assertEquals(valid, restored.carriers.single().initialRedstoneOutput)
        assertEquals(
            valid,
            ComputerBlockEntityTestPersistence.redstoneOutput(restored.entity.saveForTest()),
        )

        val malformedTag = fresh.entity.saveForTest()
        ComputerBlockEntityTestPersistence.writeRedstoneOutput(malformedTag, 1 shl 30)
        val sanitized = fixture()
        sanitized.entity.loadForTest(malformedTag)
        sanitized.entity.serverTick()
        assertEquals(0, sanitized.carriers.single().initialRedstoneOutput)
    }

    @Test
    fun `dirty sampling reads only selected faces and emits complete snapshots only on change`() {
        val fixture = fixture()
        val facing = fixture.entity.blockState.getValue(ComputerBlock.FACING)
        val sampled = mutableListOf<net.minecraft.core.Direction>()
        val first =
            fixture.entity.sampleRedstoneInputs { direction ->
                sampled += direction
                localSide(facing, direction).ordinal + 1
            }

        assertEquals(6, sampled.size)
        val expectedLevels = IntArray(6) { it + 1 }
        assertEquals(RedstoneWire.packInput(RedstoneWire.ALL_SIDES_MASK, expectedLevels), first)

        sampled.clear()
        fixture.entity.markRedstoneInputDirty(worldDirection(facing, LocalRedstoneSide.LEFT))
        val second =
            fixture.entity.sampleRedstoneInputs { direction ->
                sampled += direction
                if (localSide(facing, direction) == LocalRedstoneSide.LEFT) 15 else error("unexpected side")
            }
        expectedLevels[LocalRedstoneSide.LEFT.ordinal] = 15
        assertEquals(listOf(worldDirection(facing, LocalRedstoneSide.LEFT)), sampled)
        assertEquals(RedstoneWire.packInput(1 shl LocalRedstoneSide.LEFT.ordinal, expectedLevels), second)

        fixture.entity.markRedstoneInputDirty(worldDirection(facing, LocalRedstoneSide.LEFT))
        assertNull(fixture.entity.sampleRedstoneInputs { 15 })
    }

    @Test
    fun `deployment adapters address the current carrier without exposing it`() {
        val fixture = fixture()
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()

        val candidate = fixture.entity.verifyForDeployAsync(byteArrayOf(3, 4)).getNow(null)
        assertEquals(carrier.deploymentCandidate, candidate)
        assertEquals(VmExecutableRevision.Absent, fixture.entity.executableRevisionAsync("/home/demo").getNow(null))
        assertEquals(
            VmExecutableRevision.Present(1),
            fixture.entity.deployAsync("/home/demo", VmExecutableRevision.Absent, requireNotNull(candidate)).getNow(null),
        )
        assertTrue(fixture.entity.submitCanonicalLineAsync("/home/demo".toCharArray()).getNow(false))

        assertEquals(listOf<Byte>(3, 4), carrier.verifiedArtifact)
        assertEquals(listOf("/home/demo"), carrier.revisionPaths)
        assertEquals(listOf("/home/demo"), carrier.deploymentPaths)
        assertEquals(listOf("/home/demo"), carrier.canonicalLines)
    }

    @Test
    fun `server tick boots once and then advances once per tick`() {
        val fixture = fixture()

        fixture.entity.serverTick()
        fixture.entity.serverTick()

        val carrier = fixture.carriers.single()
        assertEquals(1, carrier.turnOnCalls)
        assertEquals(2, carrier.serverTickCalls)
    }

    @Test
    fun `server tick retries a carrier rejected by capacity after a bounded delay`() {
        var attempts = 0
        lateinit var accepted: FakeCarrier
        val entity =
            TestComputerBlockEntity(
                ComputerCarrierFactory { deviceId, _, stateSink, _, redstoneHostPort, _, initialRedstoneOutput ->
                    attempts++
                    if (attempts == 1) {
                        null
                    } else {
                        FakeCarrier(deviceId, stateSink, redstoneHostPort, initialRedstoneOutput).also { accepted = it }
                    }
                },
            )

        entity.serverTick()
        assertNull(entity.terminalMachineId)
        assertEquals(neverStarted(), entity.runtimeState)
        repeat(19) { entity.serverTick() }
        assertEquals(1, attempts)

        entity.serverTick()
        assertTrue(requireNotNull(entity.terminalMachineId) > 0)
        assertEquals(2, attempts)
        assertEquals(1, accepted.turnOnCalls)
        assertEquals(1, accepted.serverTickCalls)
    }

    @Test
    fun `terminal open retries carrier admission immediately`() {
        var attempts = 0
        val entity =
            TestComputerBlockEntity(
                ComputerCarrierFactory { deviceId, _, stateSink, _, redstoneHostPort, _, initialRedstoneOutput ->
                    attempts++
                    if (attempts == 1) {
                        null
                    } else {
                        FakeCarrier(deviceId, stateSink, redstoneHostPort, initialRedstoneOutput)
                    }
                },
            )

        entity.serverTick()

        assertEquals(terminalState("", 0), entity.prepareTerminalAsync().getNow(null))
        assertEquals(2, attempts)
    }

    @Test
    fun `server tick keeps advancing while compiler completion is pending`() {
        val fixture = fixture()
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()
        carrier.publishState(ProgramComputerState.WaitingForCompiler)

        fixture.entity.serverTick()

        assertEquals(2, carrier.serverTickCalls)
    }

    @Test
    fun `terminal open boots without advancing an extra tick`() {
        val fixture = fixture()

        assertEquals(terminalState("", 0), fixture.entity.prepareTerminalAsync().getNow(null))
        assertEquals(1, fixture.carriers.single().turnOnCalls)
        assertEquals(0, fixture.carriers.single().serverTickCalls)
        assertTrue(requireNotNull(fixture.entity.terminalMachineId) > 0)
    }

    @Test
    fun `terminal adapters expose Rust state deltas and merged input`() {
        val fixture = fixture()
        fixture.entity.serverTick()
        val carrier = fixture.carriers.single()
        carrier.terminal = terminalState("prompt", 1)
        carrier.update = TerminalUpdate.Unchanged(1)
        carrier.publishState(ProgramComputerState.WaitingForInput)

        assertEquals(carrier.terminal, fixture.entity.terminalFullStateAsync().getNow(null))
        assertEquals(TerminalUpdate.Unchanged(1), fixture.entity.terminalChangesSinceAsync(1).getNow(null))
        assertTrue(fixture.entity.submitTerminalTextAsync("Ada").getNow(false))
        assertEquals(listOf("Ada"), carrier.texts)
        assertEquals(terminalState("prompt", 1), fixture.entity.terminalFullStateAsync().getNow(null))
    }

    @Test
    fun `NBT persists stable identity but no installed artifact payload`() {
        val source = fixture()
        source.entity.serverTick()
        source.carriers.single().terminal = terminalState("transient", 1)
        val tag = source.entity.saveForTest()

        val restored = fixture()
        restored.entity.loadForTest(tag)

        assertEquals(source.entity.computerId(), restored.entity.computerId())
        assertNull(restored.entity.terminalFullStateAsync().getNow(null))
        assertEquals(neverStarted(), restored.entity.runtimeState)
        assertTrue(restored.carriers.isEmpty())
        assertEquals(setOf("compukters"), ComputerBlockEntityTestPersistence.keys(tag))
        assertFalse(tag.toString().contains("artifact"))
    }

    @Test
    fun `loading legacy payload ignores artifact and restarts lazily`() {
        val fixture = fixture()
        fixture.entity.serverTick()
        val first = fixture.carriers.single()
        val legacy = fixture.entity.saveForTest()
        val legacyPayload = CompoundTag()
        legacyPayload.putByteArray("artifact", byteArrayOf(1, 2, 3))
        ComputerBlockEntityTestPersistence.replacePayload(legacy, legacyPayload)

        fixture.entity.loadForTest(legacy)
        fixture.entity.serverTick()

        assertEquals(1, first.closeCalls)
        assertEquals(2, fixture.carriers.size)
        assertEquals(ProgramComputerState.Running, fixture.entity.runtimeState)
    }

    @Test
    fun `identity survives carrier recreation and removal closes once`() {
        val fixture = fixture()
        val id = fixture.entity.computerId()
        fixture.entity.serverTick()
        val first = fixture.carriers.single()

        fixture.entity.setRemoved()
        fixture.entity.setRemoved()
        fixture.entity.serverTick()

        assertEquals(id, fixture.entity.computerId())
        assertEquals(1, first.closeCalls)
        assertEquals(2, fixture.carriers.size)
    }

    private fun fixture(): Fixture {
        val carriers = mutableListOf<FakeCarrier>()
        val entity =
            TestComputerBlockEntity(
                ComputerCarrierFactory { deviceId, _, stateSink, _, redstoneHostPort, _, initialRedstoneOutput ->
                    FakeCarrier(deviceId, stateSink, redstoneHostPort, initialRedstoneOutput).also(carriers::add)
                },
            )
        return Fixture(entity, carriers)
    }

    private class TestComputerBlockEntity(
        carrierFactory: ComputerCarrierFactory,
    ) : ComputerBlockEntityTestPersistence(
            TEST_TYPE,
            BlockPos(2, 3, 4),
            Blocks.FURNACE.defaultBlockState(),
            carrierFactory,
        )

    private class FakeCarrier(
        private val deviceId: Int,
        private val stateSink: ProgramComputerStateSink,
        val redstoneHostPort: ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort,
        val initialRedstoneOutput: Int,
    ) : ComputerCarrier {
        override var state: ProgramComputerState = neverStarted()
            private set
        var turnOnCalls = 0
        var serverTickCalls = 0
        var shutdownCalls = 0
        var closeCalls = 0
        var terminal: TerminalState = terminalState("", 0)
        var update: TerminalUpdate = TerminalUpdate.Unchanged(0)
        val texts = mutableListOf<String>()
        val deploymentCandidate =
            object : ProgramDeploymentCandidate {
                override fun close() = Unit
            }
        var verifiedArtifact = emptyList<Byte>()
        val revisionPaths = mutableListOf<String>()
        val deploymentPaths = mutableListOf<String>()
        val canonicalLines = mutableListOf<String>()
        val redstoneInputs = mutableListOf<Int>()
        var resourceSnapshot: ProgramResourceSnapshot? = null
        var resourceSnapshotCalls = 0

        override fun turnOn(): ProgramComputerState {
            turnOnCalls++
            return publishState(ProgramComputerState.Running)
        }

        override fun serverTick(
            worldTick: Long,
            redstoneInput: Int?,
        ): ProgramComputerState {
            serverTickCalls++
            redstoneInput?.let(redstoneInputs::add)
            return state
        }

        override fun reboot(): ProgramComputerState {
            terminal = terminalState("", 0)
            return publishState(ProgramComputerState.Running)
        }

        override fun shutdown() {
            shutdownCalls++
            publishState(ProgramComputerState.PoweredOff(ProgramComputerStopReason.Shutdown))
        }

        override fun terminalFullStateAsync(): CompletableFuture<TerminalState?> = CompletableFuture.completedFuture(terminal)

        override fun terminalChangesSinceAsync(revision: Long): CompletableFuture<TerminalUpdate?> =
            CompletableFuture.completedFuture(update)

        override fun resourceSnapshotAsync(): CompletableFuture<ProgramResourceSnapshot?> {
            resourceSnapshotCalls++
            return CompletableFuture.completedFuture(resourceSnapshot)
        }

        override fun sendTerminalKeyAsync(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier>,
        ): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)

        override fun sendTerminalTextAsync(value: String): CompletableFuture<Boolean> {
            texts += value
            return CompletableFuture.completedFuture(true)
        }

        override fun filesystemGeneration(): Long? = null

        override fun verifyForDeployAsync(artifact: ByteArray): CompletableFuture<ProgramDeploymentCandidate?> {
            verifiedArtifact = artifact.toList()
            return CompletableFuture.completedFuture(deploymentCandidate)
        }

        override fun executableRevisionAsync(path: String): CompletableFuture<VmExecutableRevision?> {
            revisionPaths += path
            return CompletableFuture.completedFuture(VmExecutableRevision.Absent)
        }

        override fun deployAsync(
            path: String,
            expected: VmExecutableRevision,
            candidate: ProgramDeploymentCandidate,
        ): CompletableFuture<VmExecutableRevision?> {
            deploymentPaths += path
            assertEquals(deploymentCandidate, candidate)
            return CompletableFuture.completedFuture(VmExecutableRevision.Present(1))
        }

        override fun submitCanonicalLineAsync(line: CharArray): CompletableFuture<Boolean> {
            canonicalLines += line.concatToString()
            return CompletableFuture.completedFuture(true)
        }

        override fun fileStatAsync(path: VmVirtualPath) =
            CompletableFuture.completedFuture<ru.lazyhat.compukters.lang.runtime.fs.VmFileStat?>(null)

        override fun fileListAsync(
            path: VmVirtualPath,
            startAfter: String?,
            maximumEntries: Int,
        ) = CompletableFuture.completedFuture<ru.lazyhat.compukters.lang.runtime.fs.VmDirectoryListing?>(null)

        override fun fileReadAsync(
            path: VmVirtualPath,
            offset: Long,
            maximumBytes: Int,
            expectedGeneration: Long,
        ) = CompletableFuture.completedFuture<ru.lazyhat.compukters.lang.runtime.fs.VmFileChunk?>(null)

        override fun close() {
            closeCalls++
            publishState(ProgramComputerState.Closed)
        }

        fun publishState(next: ProgramComputerState): ProgramComputerState {
            state = next
            stateSink.publishState(deviceId, next)
            return state
        }
    }

    private data class Fixture(
        val entity: TestComputerBlockEntity,
        val carriers: List<FakeCarrier>,
    )

    companion object {
        @Suppress("unused")
        private val MINECRAFT_BOOTSTRAP =
            run {
                SharedConstants.tryDetectVersion()
                Bootstrap.bootStrap()
            }

        @Suppress("UNCHECKED_CAST")
        private val TEST_TYPE = BlockEntityType.FURNACE as BlockEntityType<ComputerBlockEntity>

        private fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)

        private fun terminalState(
            text: String,
            revision: Long,
        ): TerminalState {
            val codePoints = text.codePoints().toArray()
            return TerminalState(
                revision,
                51,
                19,
                List(51 * 19) { index -> TerminalCell(codePoints.getOrElse(index) { ' '.code }, 15, 0) },
                TerminalPosition(codePoints.size.coerceAtMost(50), 0),
                true,
            )
        }
    }
}
