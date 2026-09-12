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

package ru.lazyhat.compukters.impl.computer

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInstance
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.ide.client.files.IdeComputerFileAccess
import ru.lazyhat.compukters.ide.client.files.IdeComputerFileCoordinator
import ru.lazyhat.compukters.ide.client.files.IdeComputerTransferState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.target.IdeAttachResult
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeFileListResult
import ru.lazyhat.compukters.ide.client.target.IdeFileReadResult
import ru.lazyhat.compukters.ide.client.target.IdeFileStatResult
import ru.lazyhat.compukters.ide.client.target.IdeTargetCapabilities
import ru.lazyhat.compukters.ide.client.target.IdeTargetClaim
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId
import ru.lazyhat.compukters.ide.client.target.IdeTargetVirtualPath
import ru.lazyhat.compukters.ide.client.workspace.DefaultIdeWorkspace
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfileIdentity
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.benchmark.VmBenchmarkGameTest
import ru.lazyhat.compukters.impl.fs.NeoForgeWorldFileSystemStores
import ru.lazyhat.compukters.impl.ide.target.IdeClaimResolution
import ru.lazyhat.compukters.impl.ide.target.IdeResolvedTarget
import ru.lazyhat.compukters.impl.ide.target.IdeTargetDeploymentOperations
import ru.lazyhat.compukters.impl.ide.target.IdeTargetFileSystemOperations
import ru.lazyhat.compukters.impl.ide.target.IdeTargetFileSystemService
import ru.lazyhat.compukters.impl.ide.target.IdeTargetLeaseService
import ru.lazyhat.compukters.impl.network.serverOperation
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.FileSystemStoreHealth
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

@EventBusSubscriber(modid = MOD_ID)
object ComputerBlockGameTest {
    @JvmStatic
    @SubscribeEvent
    fun registerTests(event: RegisterGameTestsEvent) {
        val environment =
            event.registerEnvironment(
                Identifier.fromNamespaceAndPath(MOD_ID, "empty"),
                TestEnvironmentDefinition.AllOf(),
            )
        val testData =
            TestData(
                environment,
                Identifier.withDefaultNamespace("bastion/mobs/empty"),
                // GameTestServer runs ticks without pacing; allow real filesystem/compiler workers to finish.
                10000,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                false,
                0,
            )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_lifecycle"),
            ComputerLifecycleGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_redstone"),
            ComputerRedstoneGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "computer_sound"),
            ComputerSoundGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "vm_actor_service"),
            VmActorServiceGameTest(testData),
        )
        event.registerTest(
            Identifier.fromNamespaceAndPath(MOD_ID, "vm_benchmark"),
            VmBenchmarkGameTest(testData),
        )
    }

    private class ComputerLifecycleGameTest(
        testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
    ) : GameTestInstance(testData) {
        override fun run(helper: GameTestHelper) {
            val position = BlockPos.ZERO
            val block = CompuktersRegistry.COMPUTER.get()
            helper.setBlock(position, block)
            helper.assertBlockPresent(block, position)
            val entity = helper.getBlockEntity(position, NeoForgeComputerBlockEntity::class.java)
            var persistenceStep: PersistenceStep? = null
            var initialTerminal: CompletableFuture<TerminalState?>? = null
            var initialResources: CompletableFuture<ProgramResourceSnapshot?>? = null
            helper.assertTrue(
                entity.type === CompuktersRegistry.COMPUTER_BLOCK_ENTITY.get(),
                "computer block created the wrong block entity type",
            )
            helper
                .startSequence()
                .thenWaitUntil {
                    if (entity.runtimeState != neverStarted() && initialTerminal == null) {
                        initialTerminal = entity.terminalFullStateAsync()
                        initialResources = entity.resourceSnapshotAsync()
                    }
                    helper.assertTrue(initialTerminal?.isDone == true, "computer terminal snapshot is still pending")
                    assertPopulatedTerminal(helper, initialTerminal!!.getNow(null))
                    helper.assertTrue(initialResources?.isDone == true, "computer resource snapshot is still pending")
                    assertAvailableResources(helper, initialResources!!.getNow(null))
                }.thenExecute {
                    helper.setBlock(position, Blocks.AIR)
                    helper.assertTrue(entity.isRemoved, "removing the block did not remove its computer block entity")
                    helper.assertTrue(entity.runtimeState == ProgramComputerState.Closed, "removing the block did not close its VM")
                    verifyForegroundProcessAndReboot(
                        helper,
                        ComputerId.fromLongs(0x50524F43L, 0x455353L),
                    )
                    verifyTwoComputerCompilation(helper)
                    verifyPersistentProgrammingLoop(helper)
                    verifyIdeTargetFileImport(helper)
                    persistenceStep = verifyTombstoneRecovery(helper, position)
                }.thenWaitUntil {
                    val step = persistenceStep
                    helper.assertTrue(step != null, "filesystem destruction stage was not initialized")
                    helper.assertTrue(step!!.ready(), "filesystem destruction or recovery is still pending")
                }.thenExecute {
                    requireNotNull(persistenceStep).verify()
                    persistenceStep = verifyTwoComputerWorldRestart(helper, position, position.east())
                }.thenWaitUntil {
                    val step = persistenceStep
                    helper.assertTrue(step != null, "filesystem reload stage was not initialized")
                    helper.assertTrue(step!!.ready(), "unloaded computers still own their filesystems")
                }.thenExecute {
                    requireNotNull(persistenceStep).verify()
                }.thenSucceed()
        }

        override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

        override fun typeDescription(): MutableComponent = Component.literal("Compukters computer lifecycle")
    }

    private fun assertPopulatedTerminal(
        helper: GameTestHelper,
        populated: TerminalState?,
    ) {
        helper.assertTrue(populated != null, "running computer did not expose its Rust terminal")
        helper.assertTrue(populated!!.revision > 0, "terminal output was not committed")
        helper.assertTrue(
            populated.cells.any { cell -> cell.codePoint != ' '.code },
            "terminal fixture did not draw any cells",
        )
    }

    private fun assertAvailableResources(
        helper: GameTestHelper,
        snapshot: ProgramResourceSnapshot?,
    ) {
        helper.assertTrue(snapshot is ProgramResourceSnapshot.Available, "running computer did not expose its VM resources")
        snapshot as ProgramResourceSnapshot.Available
        helper.assertTrue(snapshot.heapCapacityBytes > 0, "running computer exposed no Guest heap capacity")
        helper.assertTrue(snapshot.filesystemLogicalCapacityBytes > 0, "running computer exposed no virtual disk capacity")
        helper.assertTrue(snapshot.heapUsedBytes <= snapshot.heapCapacityBytes, "Guest heap usage exceeded capacity")
        helper.assertTrue(
            snapshot.filesystemLogicalBytes <= snapshot.filesystemLogicalCapacityBytes,
            "virtual disk usage exceeded capacity",
        )
    }

    private fun verifyTombstoneRecovery(
        helper: GameTestHelper,
        position: BlockPos,
    ): PersistenceStep {
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(position, block)
        val entity = helper.getBlockEntity(position, NeoForgeComputerBlockEntity::class.java)
        val computerId = entity.computerId()
        val context = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, computerId, emptyRom())
        writeFixture(context.store, computerId, "filesystem-write.cpkt")

        val absolutePosition = helper.absolutePos(position)
        block.playerWillDestroy(
            helper.level,
            absolutePosition,
            helper.level.getBlockState(absolutePosition),
            helper.makeMockPlayer(GameType.SURVIVAL),
        )
        helper.setBlock(position, Blocks.AIR)

        var recovery: CompletableFuture<Void>? = null
        return PersistenceStep(
            ready = {
                if (recovery == null && NeoForgeWorldFileSystemStores.contextSource.available(helper.level, computerId)) {
                    val tombstoneRejected =
                        try {
                            VmSession.openInStore(fixture("filesystem-read.cpkt"), context.store, computerId, emptyRom()).use { }
                            false
                        } catch (_: VmBridgeException) {
                            true
                        }
                    helper.assertTrue(tombstoneRejected, "tombstoned filesystem accepted a new machine")
                    recovery = NeoForgeWorldFileSystemStores.recover(helper.level, computerId)
                }
                recovery?.isDone == true
            },
            verify = {
                recovery!!.getNow(null)
                assertMarker(helper, context.store, computerId, FIRST_MARKER)
            },
        )
    }

    private class PersistenceStep(
        val ready: () -> Boolean,
        val verify: () -> Unit,
    )

    private fun verifyForegroundProcessAndReboot(
        helper: GameTestHelper,
        computerId: ComputerId,
    ) {
        val rom = processTestRom()
        val context = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, computerId, rom)
        val generation =
            VmSession.openInStore(fixture("process-install-rom-executable.cpkt"), context.store, computerId, rom).use { session ->
                helper.assertTrue(
                    advanceUntilHalted(session) == VmOutcome.Halted(VmValue.I32(0)),
                    "ROM executable installer failed",
                )
                session.filesystemGeneration()
            }
        context.store.flush(computerId, generation)
        TestProgramComputer(
            ProgramRuntimeHost(
                store = context.store,
                computerId = computerId,
                romImage = rom,
                tickBudget = ProgramTickBudget(64, 64, 4),
            ),
        ).use { computer ->
            helper.assertTrue(computer.turnOn() == ProgramComputerState.Running, "process test computer did not boot")
            advanceUntilInput(computer)
            helper.assertTrue(terminalText(computer.terminalFullState()) == ">\n", "boot did not reach a fresh shell")

            runHello(computer)
            helper.assertTrue(
                terminalText(computer.terminalFullState()).endsWith("> hello\nnested child ran\n>\n"),
                "shell did not resume after the foreground child",
            )

            enterCommand(computer, "vmbench cpu 2")
            advanceUntilInput(computer)
            helper.assertTrue(
                terminalText(computer.terminalFullState()).endsWith(
                    "> vmbench cpu 2\n" +
                        "vmbench cpu: rounds=2, iterations per round=1024\n" +
                        "vmbench cpu: checksum=-365826314\n>\n",
                ),
                "vmbench did not execute the deterministic CPU workload",
            )

            enterCommand(computer, "vmbench cpu 0")
            advanceUntilInput(computer)
            helper.assertTrue(
                terminalText(computer.terminalFullState()).endsWith(
                    "> vmbench cpu 0\nusage: vmbench <cpu|redstone> <rounds 1..1000000>\n>\n",
                ),
                "vmbench did not reject an invalid round count",
            )

            enterCommand(computer, "vmbench memory 2")
            advanceUntilInput(computer)
            helper.assertTrue(
                terminalText(computer.terminalFullState()).endsWith(
                    "> vmbench memory 2\nusage: vmbench <cpu|redstone> <rounds 1..1000000>\n>\n",
                ),
                "vmbench did not reject an invalid workload mode",
            )

            helper.assertTrue(computer.reboot() == ProgramComputerState.Running, "computer reboot did not start a new boot stack")
            advanceUntilInput(computer)
            helper.assertTrue(
                terminalText(computer.terminalFullState()) == ">\n",
                "reboot did not replace the terminal with exactly one fresh prompt",
            )

            runHello(computer)
            helper.assertTrue(
                terminalText(computer.terminalFullState()).endsWith("> hello\nnested child ran\n>\n"),
                "/home child was not executable after reboot",
            )
        }
    }

    private fun runHello(computer: TestProgramComputer) {
        check(computer.sendTerminalText("hello"))
        advanceUntilInput(computer)
        check(computer.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS))
        advanceUntilInput(computer)
    }

    private fun verifyTwoComputerCompilation(helper: GameTestHelper) {
        val rom = processTestRom()
        val firstId = ComputerId.fromLongs(0x434F4D50494C45L, 1)
        val secondId = ComputerId.fromLongs(0x434F4D50494C45L, 2)
        val firstContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, firstId, rom)
        val secondContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, secondId, rom)
        writeFixture(firstContext.store, firstId, "filesystem-compilation-source.cpkt")
        writeFixture(secondContext.store, secondId, "filesystem-compilation-source.cpkt")
        val router =
            ru.lazyhat.compukters.impl.compiler.NeoForgeCompilerServices
                .router(helper.level.server)

        TestProgramComputer(
            ProgramRuntimeHost(store = firstContext.store, computerId = firstId, romImage = rom, compilerRouter = router),
        ).use { first ->
            TestProgramComputer(
                ProgramRuntimeHost(store = secondContext.store, computerId = secondId, romImage = rom, compilerRouter = router),
            ).use { second ->
                helper.assertTrue(first.turnOn() == ProgramComputerState.Running, "first compiler computer did not boot")
                helper.assertTrue(second.turnOn() == ProgramComputerState.Running, "second compiler computer did not boot")
                advanceBothUntilInput(first, second)

                enterCommand(first, "kotlinc project/main.kt -o hello")
                enterCommand(second, "kotlinc project/main.kt -o hello")
                advanceBothUntilInput(first, second)
                helper.assertTrue(
                    terminalText(first.terminalFullState()).contains("compiled: /home/hello\n"),
                    "first computer did not compile through the shared service",
                )
                helper.assertTrue(
                    terminalText(second.terminalFullState()).contains("compiled: /home/hello\n"),
                    "second computer did not compile through the shared service",
                )

                enterCommand(first, "hello")
                enterCommand(second, "hello")
                advanceBothUntilInput(first, second)
            }
        }
    }

    private fun verifyPersistentProgrammingLoop(helper: GameTestHelper) {
        val rom = processTestRom()
        val firstId = ComputerId.fromLongs(0x454449544F52L, 1)
        val secondId = ComputerId.fromLongs(0x454449544F52L, 2)
        val firstContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, firstId, rom)
        val secondContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, secondId, rom)
        val router =
            ru.lazyhat.compukters.impl.compiler.NeoForgeCompilerServices
                .router(helper.level.server)

        val generation =
            TestProgramComputer(
                ProgramRuntimeHost(store = firstContext.store, computerId = firstId, romImage = rom, compilerRouter = router),
            ).use { computer ->
                helper.assertTrue(computer.turnOn() == ProgramComputerState.Running, "editor computer did not boot")
                advanceUntilInput(computer)

                enterCommand(computer, "edit demo.kt")
                advanceUntilInput(computer)
                helper.assertTrue(
                    terminalText(computer.terminalFullState()).startsWith("Compukters edit"),
                    "guest editor did not start",
                )
                check(
                    computer.sendTerminalText(
                        "import compukter.terminal.Terminal\nfun main() { Terminal.write(\"persistent editor loop\\n\") }",
                    ),
                )
                advanceUntilInput(computer)
                check(computer.sendTerminalKey(TerminalKey.S, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)))
                advanceUntilInput(computer)
                check(computer.sendTerminalKey(TerminalKey.X, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)))
                advanceUntilInput(computer)

                enterCommand(computer, "clear")
                advanceUntilInput(computer)
                enterCommand(computer, "kotlinc demo.kt")
                advanceUntilInput(computer)
                helper.assertTrue(
                    terminalText(computer.terminalFullState()).contains("compiled: /home/demo\n"),
                    "edited source did not compile",
                )
                enterCommand(computer, "demo")
                advanceUntilInput(computer)
                helper.assertTrue(
                    terminalText(computer.terminalFullState()).contains("persistent editor loop\n"),
                    "compiled editor program did not execute",
                )
                requireNotNull(computer.filesystemGeneration())
            }
        firstContext.store.flush(firstId, generation)

        TestProgramComputer(
            ProgramRuntimeHost(store = firstContext.store, computerId = firstId, romImage = rom, compilerRouter = router),
        ).use { restored ->
            helper.assertTrue(restored.turnOn() == ProgramComputerState.Running, "restored editor computer did not boot")
            advanceUntilInput(restored)
            enterCommand(restored, "stat demo.kt")
            advanceUntilInput(restored)
            enterCommand(restored, "stat demo")
            advanceUntilInput(restored)
            enterCommand(restored, "demo")
            advanceUntilInput(restored)
            val output = terminalText(restored.terminalFullState())
            helper.assertTrue(output.contains("file: /home/demo.kt\n"), "edited source did not survive reload")
            helper.assertTrue(output.contains("file: /home/demo\n"), "compiled program did not survive reload")
            helper.assertTrue(output.contains("persistent editor loop\n"), "restored program did not execute")
        }

        TestProgramComputer(
            ProgramRuntimeHost(store = secondContext.store, computerId = secondId, romImage = rom, compilerRouter = router),
        ).use { isolated ->
            helper.assertTrue(isolated.turnOn() == ProgramComputerState.Running, "isolated editor computer did not boot")
            advanceUntilInput(isolated)
            enterCommand(isolated, "stat demo.kt")
            advanceUntilInput(isolated)
            enterCommand(isolated, "stat demo")
            advanceUntilInput(isolated)
            val output = terminalText(isolated.terminalFullState())
            helper.assertTrue(output.contains("not found: /home/demo.kt\n"), "source leaked into a second computer")
            helper.assertTrue(output.contains("not found: /home/demo\n"), "compiled program leaked into a second computer")
        }
    }

    private fun verifyIdeTargetFileImport(helper: GameTestHelper) {
        val computerId = ComputerId.fromLongs(0x49444546494C45L, 1)
        val rom = processTestRom()
        val context = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, computerId, rom)
        writeFixture(context.store, computerId, "filesystem-write.cpkt", rom)
        TestProgramComputer(ProgramRuntimeHost(store = context.store, computerId = computerId, romImage = rom)).use { computer ->
            helper.assertTrue(computer.turnOn() == ProgramComputerState.Running, "IDE filesystem computer did not boot")
            advanceUntilInput(computer)
            val profile =
                ru.lazyhat.compukters.impl.compiler.NeoForgeCompilerServices
                    .targetProfile(helper.level.server)
            val resolved =
                IdeResolvedTarget(
                    machineIdentity = "gametest:$computerId",
                    profileId = IdeTargetProfileId(TargetCompileProfileIdentity.of(profile).hash),
                    profile = profile,
                    capabilities = IdeTargetCapabilities(true, true, true, true),
                    displayName = "GameTest computer",
                    alive = { computer.state != ProgramComputerState.Closed },
                    deployment = IdeTargetDeploymentOperations(verifyForDeploy = { null }),
                    fileSystem =
                        IdeTargetFileSystemOperations(
                            stat = computer::fileStat,
                            list = computer::fileList,
                            read = computer::fileRead,
                        ),
                )
            val owner = UUID(0, 536)
            IdeTargetLeaseService(
                resolver = { _, _ -> IdeClaimResolution.Resolved(resolved) },
                targetIds = { IdeTargetId("gametest-files") },
            ).use { leases ->
                val attached =
                    (leases.attach(owner, IdeTargetClaim.of(byteArrayOf(1)), 1) as? IdeAttachResult.Attached)?.target
                helper.assertTrue(attached != null, "IDE target lease was not attached")
                val files = IdeTargetFileSystemService(leases)
                val coordinator = IdeComputerFileCoordinator(serviceAccess(files, owner, attached!!))
                coordinator.attach(attached)
                drainCoordinator(coordinator) { it.state() is IdeComputerTreeState.Available }

                helper.assertTrue(
                    coordinator.drop(IdeTargetVirtualPath.of("/home/project"), ProjectPath.file("src")),
                    "IDE target directory was not accepted for transfer",
                )
                drainCoordinator(coordinator) { it.transfer() is IdeComputerTransferState.ConfirmationRequired }
                val transfer = coordinator.transfer() as IdeComputerTransferState.ConfirmationRequired

                val root = createTempDirectory("compukters-gametest-ide-")
                try {
                    DefaultIdeWorkspace(root).use { workspace ->
                        val project = workspace.createProject("target-copy").get(5, TimeUnit.SECONDS)
                        workspace.importTree(project.handle, transfer.import).get(5, TimeUnit.SECONDS)
                        val imported =
                            project.handle.canonicalPath
                                .resolve("src/project/main.kt")
                                .toFile()
                                .readBytes()
                        helper.assertTrue(imported.contentEquals(FIRST_MARKER.encodeToByteArray()), "IDE target copy changed file bytes")

                        attachWithoutReadableFileSystem(profile, owner).use { unavailable ->
                            val unavailableCoordinator = IdeComputerFileCoordinator(unavailable.access)
                            unavailableCoordinator.attach(unavailable.target)
                            helper.assertTrue(
                                unavailableCoordinator.state() is IdeComputerTreeState.Unavailable,
                                "target without readable filesystem was not shown as unavailable",
                            )
                        }
                        workspace.tree(project.handle).get(5, TimeUnit.SECONDS)
                    }
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    private fun serviceAccess(
        files: IdeTargetFileSystemService,
        owner: UUID,
        target: IdeAttachedTarget,
    ): IdeComputerFileAccess =
        object : IdeComputerFileAccess {
            override fun stat(path: IdeTargetVirtualPath): CompletableFuture<IdeFileStatResult> =
                serverOperation { files.stat(owner, target, path, 2) }

            override fun list(
                path: IdeTargetVirtualPath,
                startAfter: String?,
                maximumEntries: Int,
            ): CompletableFuture<IdeFileListResult> = serverOperation { files.list(owner, target, path, startAfter, maximumEntries, 2) }

            override fun read(
                path: IdeTargetVirtualPath,
                offset: Long,
                maximumBytes: Int,
                expectedGeneration: Long,
            ): CompletableFuture<IdeFileReadResult> =
                serverOperation { files.read(owner, target, path, offset, maximumBytes, expectedGeneration, 2) }
        }

    private fun attachWithoutReadableFileSystem(
        profile: ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile,
        owner: UUID,
    ): UnavailableTarget {
        val resolved =
            IdeResolvedTarget(
                machineIdentity = "gametest:unavailable",
                profileId = IdeTargetProfileId(TargetCompileProfileIdentity.of(profile).hash),
                profile = profile,
                capabilities = IdeTargetCapabilities(true, true, true, false),
                displayName = "Unavailable filesystem",
                alive = { true },
                deployment = IdeTargetDeploymentOperations(verifyForDeploy = { null }),
            )
        val leases =
            IdeTargetLeaseService(
                resolver = { _, _ -> IdeClaimResolution.Resolved(resolved) },
                targetIds = { IdeTargetId("gametest-unavailable") },
            )
        val target = (leases.attach(owner, IdeTargetClaim.of(byteArrayOf(2)), 1) as IdeAttachResult.Attached).target
        val files = IdeTargetFileSystemService(leases)
        return UnavailableTarget(target, serviceAccess(files, owner, target), leases)
    }

    private fun drainCoordinator(
        coordinator: IdeComputerFileCoordinator,
        complete: (IdeComputerFileCoordinator) -> Boolean,
    ) {
        repeat(1_000) {
            coordinator.tick()
            if (complete(coordinator)) return
        }
        error("IDE filesystem coordinator did not complete")
    }

    private data class UnavailableTarget(
        val target: IdeAttachedTarget,
        val access: IdeComputerFileAccess,
        private val leases: IdeTargetLeaseService,
    ) : AutoCloseable {
        override fun close() = leases.close()
    }

    private fun enterCommand(
        computer: TestProgramComputer,
        command: String,
    ) {
        check(computer.sendTerminalText(command))
        check(computer.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS))
    }

    private fun advanceBothUntilInput(
        first: TestProgramComputer,
        second: TestProgramComputer,
    ) {
        repeat(30_000) {
            val firstState = first.serverTick()
            val secondState = second.serverTick()
            if (firstState == ProgramComputerState.WaitingForInput && secondState == ProgramComputerState.WaitingForInput) return
            check(firstState == ProgramComputerState.Running || firstState == ProgramComputerState.WaitingForCompiler) {
                "first computer terminated before waiting for input: $firstState"
            }
            check(secondState == ProgramComputerState.Running || secondState == ProgramComputerState.WaitingForCompiler) {
                "second computer terminated before waiting for input: $secondState"
            }
            if (firstState == ProgramComputerState.WaitingForCompiler || secondState == ProgramComputerState.WaitingForCompiler) {
                Thread.sleep(1)
            }
        }
        error("compiler computers did not return to input")
    }

    private fun advanceUntilInput(computer: TestProgramComputer) {
        repeat(10_000) {
            when (val state = computer.serverTick()) {
                ProgramComputerState.WaitingForInput -> return
                ProgramComputerState.Running -> Unit
                ProgramComputerState.WaitingForCompiler -> Thread.sleep(1)
                else -> error("computer terminated before waiting for input: $state")
            }
        }
        error("computer did not wait for input")
    }

    /** Direct runtime harness for guest-program checks; production computers always use the actor carrier. */
    private class TestProgramComputer(
        private val host: ProgramRuntimeHost,
    ) : AutoCloseable {
        val state: ProgramComputerState
            get() = host.state.toComputerState()

        fun turnOn(): ProgramComputerState {
            check(host.startBoot() == ProgramStartResult.Started) { "test computer did not boot: ${host.state}" }
            return state
        }

        fun serverTick(): ProgramComputerState = host.serverTick().toComputerState()

        fun terminalFullState(): TerminalState? = host.terminalFullState()

        fun sendTerminalText(value: String): Boolean = host.sendTerminalText(value)

        fun sendTerminalKey(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier> = emptySet(),
        ): Boolean = host.sendTerminalKey(key, action, modifiers)

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

        fun reboot(): ProgramComputerState {
            host.shutdown()
            return turnOn()
        }

        override fun close() = host.close()
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
                    ProgramComputerStopReason.Failure(
                        ru.lazyhat.compukters.core.device.computer.ProgramComputerFailure
                            .Runtime(failure),
                    ),
                )
            }

            ProgramRuntimeState.Closed -> {
                ProgramComputerState.Closed
            }
        }

    private fun terminalText(state: TerminalState?): String {
        val terminal = requireNotNull(state) { "computer did not expose terminal state" }
        val output = StringBuilder()
        repeat(terminal.height) { y ->
            val row = StringBuilder()
            repeat(terminal.width) { x -> row.appendCodePoint(terminal.cells[y * terminal.width + x].codePoint) }
            output.append(row.toString().trimEnd()).append('\n')
        }
        return output.toString().trimEnd('\n') + '\n'
    }

    internal fun processTestRom(): ByteArray = ComputerGameTestFixtures.processTestRom()

    private fun verifyTwoComputerWorldRestart(
        helper: GameTestHelper,
        firstPosition: BlockPos,
        secondPosition: BlockPos,
    ): PersistenceStep {
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(firstPosition, block)
        helper.setBlock(secondPosition, block)
        val first = helper.getBlockEntity(firstPosition, NeoForgeComputerBlockEntity::class.java)
        val second = helper.getBlockEntity(secondPosition, NeoForgeComputerBlockEntity::class.java)
        val firstId = first.computerId()
        val secondId = second.computerId()
        helper.assertTrue(firstId != secondId, "two computers received the same identity")

        val firstContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, firstId, emptyRom())
        val secondContext = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, secondId, emptyRom())
        val firstGeneration = writeFixture(firstContext.store, firstId, "filesystem-write.cpkt")
        val secondGeneration = writeFixture(secondContext.store, secondId, "filesystem-write-alternate.cpkt")

        first.prepareTerminalAsync()
        second.prepareTerminalAsync()
        val restoredFirst = reloadComputer(helper, firstPosition, firstId)
        val restoredSecond = reloadComputer(helper, secondPosition, secondId)
        return PersistenceStep(
            ready = {
                // Tickers may already have reattached a ready machine before this sequence runs.
                (
                    restoredFirst.runtimeState != neverStarted() ||
                        NeoForgeWorldFileSystemStores.contextSource.available(
                            helper.level,
                            firstId,
                        )
                ) &&
                    (
                        restoredSecond.runtimeState != neverStarted() ||
                            NeoForgeWorldFileSystemStores.contextSource.available(helper.level, secondId)
                    )
            },
            verify = {
                restoredFirst.prepareTerminalAsync()
                restoredSecond.prepareTerminalAsync()

                NeoForgeWorldFileSystemStores.onLevelSave(LevelEvent.Save(helper.level))
                helper.assertTrue(
                    firstContext.store.durableGeneration(firstId) == firstGeneration,
                    "world save did not flush the first computer generation",
                )
                helper.assertTrue(
                    secondContext.store.durableGeneration(secondId) == secondGeneration,
                    "world save did not flush the second computer generation",
                )
                helper.assertTrue(firstContext.store.health() == FileSystemStoreHealth.ACTIVE, "world store was not active before stop")

                val stoppedStore = firstContext.store
                NeoForgeWorldFileSystemStores.onServerStopping(ServerStoppingEvent(helper.level.server))
                helper.assertTrue(restoredFirst.runtimeState == ProgramComputerState.Closed, "first VM was not drained on stop")
                helper.assertTrue(restoredSecond.runtimeState == ProgramComputerState.Closed, "second VM was not drained on stop")
                helper.assertTrue(rejectsAfterClose { stoppedStore.health() }, "closed store still exposed health")
                helper.assertTrue(
                    rejectsAfterClose { stoppedStore.flush(firstId, firstGeneration) },
                    "closed store accepted a write after worker shutdown",
                )

                val reopened = NeoForgeWorldFileSystemStores.contextSource.create(helper.level, firstId, emptyRom()).store
                assertMarker(helper, reopened, firstId, FIRST_MARKER)
                assertMarker(helper, reopened, secondId, SECOND_MARKER)
            },
        )
    }

    private fun reloadComputer(
        helper: GameTestHelper,
        position: BlockPos,
        expectedId: ru.lazyhat.compukters.lang.runtime.fs.ComputerId,
    ): NeoForgeComputerBlockEntity {
        val level = helper.level
        val absolutePosition = helper.absolutePos(position)
        val current = helper.getBlockEntity(position, NeoForgeComputerBlockEntity::class.java)
        val blockState = level.getBlockState(absolutePosition)
        val payload = current.saveWithFullMetadata(level.registryAccess())
        level.removeBlockEntity(absolutePosition)
        helper.assertTrue(current.isRemoved, "unloaded computer did not close its block entity")
        val restored =
            BlockEntity.loadStatic(absolutePosition, blockState, payload, level.registryAccess()) as? NeoForgeComputerBlockEntity
        helper.assertTrue(restored != null, "saved computer block entity did not reload")
        level.setBlockEntity(restored!!)
        helper.assertTrue(restored.computerId() == expectedId, "computer identity changed across unload/reload")
        return restored
    }

    private fun writeFixture(
        store: ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore,
        computerId: ru.lazyhat.compukters.lang.runtime.fs.ComputerId,
        name: String,
        rom: ByteArray = emptyRom(),
    ): Long {
        val generation =
            VmSession.openInStore(fixture(name), store, computerId, rom).use { session ->
                advanceUntilHalted(session)
                session.filesystemGeneration()
            }
        store.flush(computerId, generation)
        return generation
    }

    private fun assertMarker(
        helper: GameTestHelper,
        store: ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore,
        computerId: ru.lazyhat.compukters.lang.runtime.fs.ComputerId,
        marker: String,
    ) {
        VmSession.openInStore(fixture("filesystem-read.cpkt"), store, computerId, emptyRom()).use { session ->
            helper.assertTrue(
                advanceUntilHalted(session) == VmOutcome.Halted(VmValue.I32(marker.hashCode())),
                "filesystem marker was missing or belonged to another computer",
            )
        }
    }

    private fun advanceUntilHalted(session: VmSession): VmOutcome.Halted {
        repeat(10_000) {
            when (val outcome = session.advance(64, 64, Int.MAX_VALUE)) {
                VmOutcome.SliceExhausted -> Unit
                is VmOutcome.Halted -> return outcome
                else -> error("unexpected VM outcome: $outcome")
            }
        }
        error("filesystem reader did not halt")
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(ComputerBlockGameTest::class.java.getResourceAsStream("/fixtures/$name")) {
            "missing GameTest fixture $name"
        }.use { it.readAllBytes() }

    private fun resource(name: String): ByteArray =
        requireNotNull(ComputerBlockGameTest::class.java.getResourceAsStream(name)) {
            "missing GameTest resource $name"
        }.use { it.readAllBytes() }

    private fun emptyRom(): ByteArray {
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTROM\u0000".encodeToByteArray())
                .putShort(1.toShort())
                .putShort(0.toShort())
                .putInt(0)
                .array()
        return header + MessageDigest.getInstance("SHA-256").digest(header)
    }

    private inline fun rejectsAfterClose(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (_: IllegalStateException) {
            true
        }

    private fun neverStarted(): ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)

    private const val FIRST_MARKER = "fun main() = 42\n"
    private const val SECOND_MARKER = "fun main() = 7\n"
}
