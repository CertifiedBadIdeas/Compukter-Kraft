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

import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.assertEquals

internal object ShellProgram {
    fun run(
        artifactPath: Path,
        openSession: (ByteArray) -> VmSession = { VmSession.open(it) },
    ) {
        openSession(artifactPath.readBytes()).use { session ->
            advanceUntilWaiting(session)
            session.commitTerminal()
            assertEquals(">\n", terminalText(session.terminalFullState()))

            session.sendTerminalText("echo native")
            advanceUntilWaiting(session)
            session.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            advanceUntilWaiting(session)
            session.commitTerminal()
            assertEquals("> echo native\nnative\n>\n", terminalText(session.terminalFullState()))
        }
    }

    private fun advanceUntilWaiting(session: VmSession) {
        repeat(MAXIMUM_ADVANCES) {
            when (val outcome = session.advance(GUEST_BUDGET, MAINTENANCE_BUDGET, Int.MAX_VALUE)) {
                VmOutcome.SliceExhausted -> Unit
                VmOutcome.WaitingForTerminalEvent -> return
                else -> error("unexpected VM outcome: $outcome")
            }
        }
        error("shell did not wait within $MAXIMUM_ADVANCES advances")
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

    private const val GUEST_BUDGET = 64
    private const val MAINTENANCE_BUDGET = 64
    private const val MAXIMUM_ADVANCES = 10_000
}
