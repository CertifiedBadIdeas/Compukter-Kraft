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

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import java.util.concurrent.CompletableFuture

internal object ComputerSoundGameTestScenario {
    fun run(helper: GameTestHelper) {
        val computerPosition = BlockPos(2, 2, 2)
        helper.setBlock(computerPosition, CompuktersRegistry.COMPUTER.get())
        val entity = helper.compuktersComputerBlockEntity(computerPosition)
        entity.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        var terminal: CompletableFuture<TerminalState?>? = null

        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(
                    entity.runtimeState == ProgramComputerState.WaitingForInput,
                    "computer shell did not become ready for the sound program",
                )
            }.thenExecute {
                setup =
                    entity
                        .verifyForDeployAsync(fixture())
                        .thenCompose { candidate ->
                            requireNotNull(candidate)
                            entity.executableRevisionAsync("/home/sound").thenCompose { expected ->
                                requireNotNull(expected)
                                helper.assertTrue(expected == VmExecutableRevision.Absent, "sound executable already existed")
                                entity.deployAsync("/home/sound", expected, candidate)
                            }
                        }.thenCompose {
                            entity.submitTerminalTextAsync("sound")
                        }.thenCompose { accepted ->
                            helper.assertTrue(accepted, "shell rejected the sound command")
                            entity.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                        }.thenAccept { accepted ->
                            helper.assertTrue(accepted, "shell rejected the sound command enter key")
                        }
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "sound program setup is still pending")
                setup!!.getNow(null)
            }.thenWaitUntil {
                val state = entity.runtimeState
                if (terminal == null) terminal = entity.terminalFullStateAsync()
                helper.assertTrue(terminal!!.isDone, "sound terminal snapshot is still pending")
                val snapshot = terminal!!.getNow(null)
                if (snapshot == null) {
                    terminal = null
                    helper.assertTrue(false, "sound terminal snapshot was temporarily unavailable: state=$state")
                    return@thenWaitUntil
                }
                val output = terminalText(snapshot)
                val completed =
                    output.endsWith("> sound\ntrue\nfalse\n>\n") ||
                        output.endsWith("> sound\ntrue\ntrue\n>\n")
                if (!completed) terminal = null
                helper.assertTrue(
                    state == ProgramComputerState.WaitingForInput && completed,
                    "sound program did not complete both asynchronous beep requests: state=$state output=$output",
                )
            }.thenSucceed()
    }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/sound.cpkt")) {
            "missing generated sound GameTest fixture"
        }.use { it.readAllBytes() }

    private fun terminalText(terminal: TerminalState): String {
        val output = StringBuilder()
        repeat(terminal.height) { y ->
            val row = StringBuilder()
            repeat(terminal.width) { x -> row.appendCodePoint(terminal.cells[y * terminal.width + x].codePoint) }
            output.append(row.toString().trimEnd()).append('\n')
        }
        return output.toString().trimEnd('\n') + '\n'
    }
}
