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

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.computer.compuktersComputerBlockEntity
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.util.concurrent.CompletableFuture

internal object VmBenchmarkGameTestScenario {
    fun run(helper: GameTestHelper) {
        val server = helper.level.server
        val firstPosition = BlockPos.ZERO
        val secondPosition = firstPosition.east()
        val block = CompuktersRegistry.COMPUTER.get()
        helper.setBlock(firstPosition, block)
        helper.setBlock(secondPosition, block)
        val first = helper.compuktersComputerBlockEntity(firstPosition)
        val second = helper.compuktersComputerBlockEntity(secondPosition)
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
            }.thenExecute {
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "compukters vmbench capacity 2 2",
                )
            }.thenWaitUntil {
                val snapshot = VmBenchmarkCommands.snapshot(server)
                helper.assertTrue(snapshot != null, "capacity benchmark command did not create a fleet")
                helper.assertTrue(
                    snapshot!!.status == HeadlessVmBenchmarkStatus.COMPLETED,
                    "capacity benchmark is still ${snapshot.status}/${snapshot.phase}: " +
                        "active=${snapshot.activeActors}, waiting=${snapshot.waitingActors}, failed=${snapshot.failedActors}",
                )
            }.thenExecute {
                val snapshot = requireNotNull(VmBenchmarkCommands.snapshot(server))
                helper.assertTrue(snapshot.mode == HeadlessVmBenchmarkMode.CAPACITY, "capacity benchmark mode was not retained")
                helper.assertTrue(snapshot.completedActors == 2, "capacity benchmark actors did not halt")
                helper.assertTrue(snapshot.failedActors == 0, "capacity benchmark actor failed")
                helper.assertTrue(snapshot.settleTicks.samples == 2, "capacity settle distribution is incomplete")
                helper.assertTrue(snapshot.wakeTicks.samples == 2, "capacity wake distribution is incomplete")
                helper.assertTrue(snapshot.completionTicks.samples == 2, "capacity completion distribution is incomplete")
                helper.assertTrue(snapshot.memory.availableSamples == 2, "capacity memory samples are incomplete")
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
            }.thenExecute {
                terminals = null
                val position = helper.absolutePos(firstPosition)
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "compukters vmbench area ${position.x} ${position.y} ${position.z} " +
                        "${position.x} ${position.y} ${position.z} redstone 2",
                )
            }.thenWaitUntil {
                val signal = helper.level.getSignal(helper.absolutePos(firstPosition), Direction.DOWN)
                helper.assertTrue(signal == 15, "physical redstone benchmark did not commit its high pulse: signal=$signal")
                val snapshot = requireNotNull(VmBenchmarkCommands.areaSnapshot(server))
                helper.assertTrue(
                    snapshot.status == VmBenchmarkAreaStatus.RUNNING && snapshot.activeComputers == 1,
                    "physical area benchmark did not expose its active computer: $snapshot",
                )
                val position = helper.absolutePos(firstPosition)
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "compukters vmbench area ${position.x} ${position.y} ${position.z} " +
                        "${position.x} ${position.y} ${position.z} redstone 1",
                )
                helper.assertTrue(
                    VmBenchmarkCommands.areaSnapshot(server)?.rounds == 2,
                    "physical area benchmark replaced its active run",
                )
            }.thenWaitUntil {
                val signal = helper.level.getSignal(helper.absolutePos(firstPosition), Direction.DOWN)
                val pending = terminals ?: listOf(first.terminalFullStateAsync()).also { terminals = it }
                helper.assertTrue(pending.all { it.isDone }, "physical redstone benchmark terminal snapshot is still pending")
                val output = terminalText(pending.single().getNow(null))
                val completed =
                    signal == 0 &&
                        first.runtimeState == ProgramComputerState.WaitingForInput &&
                        output.contains("vmbench redstone: acknowledged transitions=4")
                if (!completed) terminals = null
                helper.assertTrue(
                    completed,
                    "physical redstone benchmark did not clear its output and return to the shell: " +
                        "signal=$signal state=${first.runtimeState}",
                )
                val snapshot = requireNotNull(VmBenchmarkCommands.areaSnapshot(server))
                helper.assertTrue(
                    snapshot.status == VmBenchmarkAreaStatus.COMPLETED && snapshot.completedComputers == 1,
                    "physical area benchmark did not expose completion: $snapshot",
                )
            }.thenSucceed()
    }

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
