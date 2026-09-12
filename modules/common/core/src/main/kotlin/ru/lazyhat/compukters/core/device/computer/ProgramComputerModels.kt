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

package ru.lazyhat.compukters.core.device.computer

import ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

sealed interface ProgramComputerState {
    data class PoweredOff(
        val reason: ProgramComputerStopReason,
    ) : ProgramComputerState

    data object Running : ProgramComputerState

    data object WaitingForInput : ProgramComputerState

    data object WaitingForCompiler : ProgramComputerState

    data object Closed : ProgramComputerState
}

sealed interface ProgramComputerStopReason {
    data object NeverStarted : ProgramComputerStopReason

    data object Shutdown : ProgramComputerStopReason

    data class Halted(
        val value: VmValue?,
    ) : ProgramComputerStopReason

    data class Failure(
        val failure: ProgramComputerFailure,
    ) : ProgramComputerStopReason
}

sealed interface ProgramComputerFailure {
    data class Runtime(
        val failure: ProgramFailure,
    ) : ProgramComputerFailure

    data class TerminalPublication(
        val detail: String,
    ) : ProgramComputerFailure

    data class RuntimeContract(
        val state: ProgramRuntimeState,
    ) : ProgramComputerFailure
}
