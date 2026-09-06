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

package ru.lazyhat.compukters.lang.runtime.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.vm.FfmBridge
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmDeploymentConflictException
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FfmBridgeIntegrationTest {
    @Test
    fun `JDK 25 FFM reads the native ABI version`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            assertEquals(9, bridge.abiVersion())
        }
    }

    @Test
    fun `verified deployment uses exact revisions and command submission remains explicit`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            val artifact = Path.of(requiredProperty("compukters.shell.artifact")).readBytes()
            VmSession.open(artifact, bridge).use { session ->
                val path = "/home/deployed"
                assertEquals(VmExecutableRevision.Absent, session.executableRevision(path))

                val first = session.verifyForDeploy(artifact)
                val installed =
                    assertIs<VmExecutableRevision.Present>(
                        session.deploy(path, VmExecutableRevision.Absent, first),
                    )
                assertEquals(installed, session.executableRevision(path))

                val retry = session.verifyForDeploy(artifact)
                assertFailsWith<VmDeploymentConflictException> {
                    session.deploy(path, VmExecutableRevision.Absent, retry)
                }
                val replaced = assertIs<VmExecutableRevision.Present>(session.deploy(path, installed, retry))
                assert(replaced.generation > installed.generation)

                advanceUntilWaiting(session)
                session.submitCanonicalLine(path.toCharArray())
            }
        }
    }

    @Test
    fun `terminal artifact runs through Kotlin FFM and Rust VM`() =
        runBlocking {
            FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
                ShellProgram.run(Path.of(requiredProperty("compukters.shell.artifact"))) { artifact ->
                    VmSession.open(artifact, bridge)
                }
            }
        }

    @Test
    fun `terminal FFM transport reuses one session scratch and isolates another session`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            val artifact = Path.of(requiredProperty("compukters.shell.artifact")).readBytes()
            VmSession.open(artifact, bridge).use { first ->
                val initial = first.terminalFullState()
                assertEquals(initial, first.terminalFullState())
                assertEquals(TerminalUpdate.Unchanged(initial.revision), first.terminalChangesSince(initial.revision))

                advanceUntilWaiting(first)
                first.commitTerminal()
                val delta = assertIs<TerminalUpdate.Delta>(first.terminalChangesSince(initial.revision))
                val current = first.terminalFullState()
                assertEquals(initial.revision, delta.baseRevision)
                assertEquals(current.revision, delta.targetRevision)
                assertEquals(current, first.terminalFullState())

                VmSession.open(artifact, bridge).use { second ->
                    val independent = second.terminalFullState()
                    assertEquals(0, independent.revision)
                    assertEquals(TerminalUpdate.Unchanged(0), second.terminalChangesSince(0))
                }
            }
        }
    }

    @Test
    fun `ordinary main releases JVM thread while terminal call waits and resumes in Rust VM`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            val artifact = Path.of(requiredProperty("compukters.blocking-call.artifact")).readBytes()
            VmSession.open(artifact, bridge).use { session ->
                advanceUntilWaiting(session)

                session.sendTerminalText("input")
                advanceUntilHalted(session)
                session.commitTerminal()

                assertEquals("event\n", terminalText(session.terminalFullState()))
            }
        }
    }

    @Test
    fun `FFM preserves typed create failures`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            assertFailsWith<VmVerificationException> { VmSession.open(byteArrayOf(0), bridge) }
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name test property" }

    private fun advanceUntilWaiting(session: VmSession) {
        repeat(10_000) {
            when (val outcome = session.advance(64, 64, Int.MAX_VALUE)) {
                VmOutcome.SliceExhausted -> Unit
                VmOutcome.WaitingForTerminalEvent -> return
                else -> error("unexpected VM outcome: $outcome")
            }
        }
        error("shell did not wait")
    }

    private fun advanceUntilHalted(session: VmSession) {
        repeat(10_000) {
            when (val outcome = session.advance(64, 64, Int.MAX_VALUE)) {
                VmOutcome.SliceExhausted -> Unit
                is VmOutcome.Halted -> return
                else -> error("unexpected VM outcome: $outcome")
            }
        }
        error("program did not halt")
    }

    private fun terminalText(state: TerminalState): String {
        val output = StringBuilder()
        repeat(state.height) { y ->
            val row = StringBuilder()
            repeat(state.width) { x -> row.appendCodePoint(state.cells[y * state.width + x].codePoint) }
            output.append(row.toString().trimEnd()).append('\n')
        }
        return output.toString().trimEnd('\n') + '\n'
    }
}
