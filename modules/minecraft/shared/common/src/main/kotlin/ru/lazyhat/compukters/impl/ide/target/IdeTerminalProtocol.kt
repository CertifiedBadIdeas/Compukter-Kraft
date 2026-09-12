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

package ru.lazyhat.compukters.impl.ide.target

import ru.lazyhat.compukters.ide.client.target.IdeTargetFailureKind
import ru.lazyhat.compukters.impl.terminal.TerminalModel
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import java.util.UUID

sealed interface IdeTerminalCommand

data class IdeTerminalOpen(
    val generation: Long,
    val target: IdeTargetReference,
) : IdeTerminalCommand {
    init {
        requireTerminalGeneration(generation)
    }
}

data class IdeTerminalResync(
    val token: UUID,
    val machineId: Long,
    val revision: Long,
) : IdeTerminalCommand {
    init {
        requireTerminalSession(token, machineId)
        require(revision >= 0) { "terminal revision must not be negative" }
    }
}

data class IdeTerminalKeyInput(
    val token: UUID,
    val machineId: Long,
    val key: TerminalKey,
    val action: TerminalKeyAction,
    val modifiers: Set<TerminalModifier>,
) : IdeTerminalCommand {
    init {
        requireTerminalSession(token, machineId)
    }
}

data class IdeTerminalTextInput(
    val token: UUID,
    val machineId: Long,
    val text: String,
) : IdeTerminalCommand {
    init {
        requireTerminalSession(token, machineId)
        TerminalModel.requireAtomicText(text)
    }
}

data class IdeTerminalClose(
    val token: UUID,
) : IdeTerminalCommand {
    init {
        requireTerminalToken(token)
    }
}

sealed interface IdeTerminalEvent

data class IdeTerminalOpened(
    val generation: Long,
    val token: UUID,
    val machineId: Long,
    val state: TerminalState,
) : IdeTerminalEvent {
    init {
        requireTerminalGeneration(generation)
        requireTerminalSession(token, machineId)
        TerminalModel.validateState(state)
    }
}

data class IdeTerminalFull(
    val token: UUID,
    val machineId: Long,
    val state: TerminalState,
) : IdeTerminalEvent {
    init {
        requireTerminalSession(token, machineId)
        TerminalModel.validateState(state)
    }
}

data class IdeTerminalDelta(
    val token: UUID,
    val machineId: Long,
    val delta: TerminalUpdate.Delta,
) : IdeTerminalEvent {
    init {
        requireTerminalSession(token, machineId)
        TerminalModel.validateDelta(delta)
    }
}

data class IdeTerminalFailed(
    val generation: Long,
    val token: UUID?,
    val kind: IdeTargetFailureKind,
    val detail: String,
    val retryable: Boolean,
) : IdeTerminalEvent {
    init {
        requireTerminalGeneration(generation)
        token?.let(::requireTerminalToken)
        require(detail.length <= MAXIMUM_FAILURE_DETAIL_CODE_UNITS) { "terminal failure detail is too long" }
    }
}

private fun requireTerminalSession(
    token: UUID,
    machineId: Long,
) {
    requireTerminalToken(token)
    require(machineId > 0) { "terminal machine ID must be positive" }
}

private fun requireTerminalToken(token: UUID) {
    require(token.mostSignificantBits != 0L || token.leastSignificantBits != 0L) { "terminal session token must not be zero" }
}

private fun requireTerminalGeneration(generation: Long) {
    require(generation > 0) { "terminal client generation must be positive" }
}

private const val MAXIMUM_FAILURE_DETAIL_CODE_UNITS = 512
