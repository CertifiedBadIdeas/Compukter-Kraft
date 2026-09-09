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

package ru.lazyhat.compukters.impl.benchmark

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInstance
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.computer.NeoForgeComputerBlockEntity
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.util.concurrent.CompletableFuture

internal class VmBenchmarkGameTest(
    testData: TestData<Holder<TestEnvironmentDefinition<*>>>,
) : GameTestInstance(testData) {
    override fun run(helper: GameTestHelper) {
        val server = helper.level.server
        val firstPosition = BlockPos.ZERO
        val secondPosition = firstPosition.east()
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(firstPosition, block)
        helper.setBlock(secondPosition, block)
        val first = helper.getBlockEntity(firstPosition, NeoForgeComputerBlockEntity::class.java)
        val second = helper.getBlockEntity(secondPosition, NeoForgeComputerBlockEntity::class.java)
        var terminals: List<CompletableFuture<TerminalState?>>? = null
        helper
            .startSequence()
            .thenExecute {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "compukters vmbench start 2 2",
                )
            }.thenWaitUntil {
                val snapshot = VmBenchmarkCommands.snapshot(server)
                helper.assertTrue(snapshot != null, "headless benchmark command did not create a fleet")
                helper.assertTrue(
                    snapshot!!.status == HeadlessVmBenchmarkStatus.COMPLETED,
                    "headless benchmark is still ${snapshot.status}: " +
                        "active=${snapshot.activeActors}, completed=${snapshot.completedActors}, failed=${snapshot.failedActors}",
                )
            }.thenExecute {
                val snapshot = requireNotNull(VmBenchmarkCommands.snapshot(server))
                helper.assertTrue(snapshot.admittedActors == 2, "headless benchmark did not admit both actors")
                helper.assertTrue(snapshot.completedActors == 2, "headless benchmark actors did not halt")
                helper.assertTrue(snapshot.failedActors == 0, "headless benchmark actor failed")
            }.thenWaitUntil {
                helper.assertTrue(
                    first.runtimeState == ProgramComputerState.WaitingForInput &&
                        second.runtimeState == ProgramComputerState.WaitingForInput,
                    "physical computers did not reach their shell prompts",
                )
            }.thenExecute {
                val from = helper.absolutePos(firstPosition)
                val to = helper.absolutePos(secondPosition)
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "compukters vmbench area ${from.x} ${from.y} ${from.z} ${to.x} ${to.y} ${to.z} 2",
                )
            }.thenWaitUntil {
                val pending = terminals ?: listOf(first.terminalFullStateAsync(), second.terminalFullStateAsync()).also { terminals = it }
                helper.assertTrue(pending.all { it.isDone }, "physical benchmark terminal snapshots are still pending")
                val completed =
                    pending.all { future ->
                        terminalText(future.getNow(null)).contains("vmbench cpu: checksum=-365826314")
                    }
                if (!completed) terminals = null
                helper.assertTrue(completed, "physical computers did not complete the area benchmark")
            }.thenSucceed()
    }

    override fun codec(): MapCodec<out GameTestInstance> = MapCodec.unit(this)

    override fun typeDescription(): MutableComponent = Component.literal("Compukters headless VM benchmark")

    private fun terminalText(state: TerminalState?): String {
        val terminal = state ?: return ""
        val output = StringBuilder()
        repeat(terminal.height) { y ->
            val row = StringBuilder()
            repeat(terminal.width) { x -> row.appendCodePoint(terminal.cells[y * terminal.width + x].codePoint) }
            output.append(row.toString().trimEnd()).append('\n')
        }
        return output.toString()
    }
}
