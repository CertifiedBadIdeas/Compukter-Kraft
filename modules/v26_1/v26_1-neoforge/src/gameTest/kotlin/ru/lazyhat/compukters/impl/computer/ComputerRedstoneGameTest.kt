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
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInstance
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import java.util.concurrent.CompletableFuture

internal class ComputerRedstoneGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) {
        val computerPosition = BlockPos(2, 2, 2)
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(
            computerPosition,
            block.defaultBlockState().setValue(ComputerBlock.FACING, Direction.NORTH),
        )
        val entity = helper.getBlockEntity(computerPosition, NeoForgeComputerBlockEntity::class.java)
        entity.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        var terminal: CompletableFuture<TerminalState?>? = null

        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(
                    entity.runtimeState == ProgramComputerState.WaitingForInput,
                    "computer shell did not become ready for the redstone program",
                )
            }.thenExecute {
                val artifact = fixture()
                setup =
                    entity
                        .verifyForDeployAsync(artifact)
                        .thenCompose { candidate ->
                            requireNotNull(candidate)
                            entity.executableRevisionAsync("/home/redstone").thenCompose { expected ->
                                requireNotNull(expected)
                                helper.assertTrue(expected == VmExecutableRevision.Absent, "redstone executable already existed")
                                entity.deployAsync("/home/redstone", expected, candidate)
                            }
                        }.thenCompose {
                            entity.submitTerminalTextAsync("redstone")
                        }.thenCompose { accepted ->
                            helper.assertTrue(accepted, "shell rejected the redstone command")
                            entity.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                        }.thenAccept { accepted ->
                            helper.assertTrue(accepted, "shell rejected the redstone command enter key")
                        }
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "redstone program setup is still pending")
                setup!!.getNow(null)
            }.thenIdle(2)
            .thenExecute {
                helper.setBlock(computerPosition.west(), Blocks.REDSTONE_BLOCK)
            }.thenWaitUntil {
                val signal = helper.level.getSignal(helper.absolutePos(computerPosition), Direction.WEST)
                helper.assertTrue(signal == 15, "local RIGHT weak output expected level 15, got $signal")
            }.thenExecute {
                helper.setBlock(computerPosition.north(), Blocks.REDSTONE_BLOCK)
            }.thenWaitUntil {
                val direct = helper.level.getDirectSignal(helper.absolutePos(computerPosition), Direction.DOWN)
                val bottom = helper.level.getSignal(helper.absolutePos(computerPosition), Direction.UP)
                helper.assertTrue(direct == 15, "local TOP direct output expected level 15, got $direct")
                helper.assertTrue(bottom == 0, "local BOTTOM output expected level 0, got $bottom")
            }.thenWaitUntil {
                val state = entity.runtimeState
                if (terminal == null) terminal = entity.terminalFullStateAsync()
                helper.assertTrue(terminal!!.isDone, "redstone terminal snapshot is still pending")
                val output = terminalText(terminal!!.getNow(null))
                if (!output.endsWith("> redstone\n>\n")) terminal = null
                helper.assertTrue(
                    state == ProgramComputerState.WaitingForInput && output.endsWith("> redstone\n>\n"),
                    "redstone program did not return successfully after more than 64 writes: state=$state output=$output",
                )
            }.thenSucceed()
    }

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters redstone GPIO")

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/redstone.cpkt")) {
            "missing generated redstone GameTest fixture"
        }.use { it.readAllBytes() }

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
}
