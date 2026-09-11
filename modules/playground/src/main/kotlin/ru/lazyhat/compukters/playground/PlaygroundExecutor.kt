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

package ru.lazyhat.compukters.playground

import kotlinx.coroutines.CancellationException
import ru.lazyhat.compukters.lang.runtime.capability.CapabilityRegistry
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.TerminalCapability
import ru.lazyhat.compukters.lang.runtime.capability.TerminalLimits
import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

fun interface PlaygroundExecutor {
    suspend fun execute(artifact: ByteArray): PlaygroundExecution
}

sealed interface PlaygroundExecution {
    data object Success : PlaygroundExecution

    data object VerificationFailure : PlaygroundExecution

    data class AdmissionFailure(
        val code: Int,
    ) : PlaygroundExecution

    data class StartFailure(
        val code: Int,
    ) : PlaygroundExecution

    data class Trap(
        val trap: GuestTrap,
    ) : PlaygroundExecution

    data class Fault(
        val fault: VmFault,
    ) : PlaygroundExecution

    data class HostFailure(
        val kind: HostFailureKind,
        val code: Long,
    ) : PlaygroundExecution

    data class Quota(
        val kind: QuotaKind,
        val limit: Long,
        val consumed: Long,
    ) : PlaygroundExecution

    data class ResourceFailure(
        val collectionAttempted: Boolean,
    ) : PlaygroundExecution

    data class PlatformFailure(
        val detail: String,
    ) : PlaygroundExecution
}

class NativePlaygroundExecutor(
    private val library: Path,
    input: InputStream,
    output: OutputStream,
    terminalLimits: TerminalLimits = TerminalLimits(),
    private val maximumAdvances: Int = 1024,
) : PlaygroundExecutor {
    private val terminal = TerminalCapability(input, output, terminalLimits)
    private val capabilities = CapabilityRegistry(listOf(terminal))
    private var loaded = false

    init {
        require(maximumAdvances > 0) { "maximum advances must be positive" }
    }

    override suspend fun execute(artifact: ByteArray): PlaygroundExecution =
        try {
            loadLibrary()
            VmSession.open(artifact).use { session ->
                var publishedTerminalText = ""
                repeat(maximumAdvances) {
                    when (val outcome = session.advance(GUEST_BUDGET, MAINTENANCE_BUDGET, Int.MAX_VALUE)) {
                        is VmOutcome.HostRequestBatch -> {
                            for (request in outcome.requests) {
                                session.resume(request.identity, capabilities.dispatch(request))
                            }
                        }

                        is VmOutcome.Halted -> {
                            publishTerminal(session, publishedTerminalText)?.let { return it }
                            return PlaygroundExecution.Success
                        }

                        VmOutcome.WaitingForTerminalEvent -> {
                            publishTerminal(session, publishedTerminalText)?.let { return it }
                            publishedTerminalText = terminalText(session.terminalFullState())
                            when (val response = terminal.invoke(compatibilityRequest(READ_OPERATION))) {
                                is HostResponse.StringSuccess -> {
                                    session.sendTerminalText(response.value)
                                    session.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                                }

                                is HostResponse.Failure -> {
                                    return PlaygroundExecution.HostFailure(response.kind, response.code)
                                }

                                HostResponse.UnitSuccess -> {
                                    return PlaygroundExecution.PlatformFailure("terminal input returned no line")
                                }

                                is HostResponse.BoolSuccess -> {
                                    return PlaygroundExecution.PlatformFailure("terminal input returned a Boolean")
                                }
                            }
                        }

                        VmOutcome.SliceExhausted -> {
                            return@repeat
                        }

                        VmOutcome.WaitingForHostQuota -> {
                            return PlaygroundExecution.PlatformFailure("unlimited host request budget was exhausted")
                        }

                        is VmOutcome.AllocationExhausted -> {
                            return PlaygroundExecution.ResourceFailure(outcome.collectionAttempted)
                        }

                        is VmOutcome.QuotaExhausted -> {
                            return PlaygroundExecution.Quota(outcome.kind, outcome.limit, outcome.consumed)
                        }

                        is VmOutcome.Crashed -> {
                            return PlaygroundExecution.Trap(outcome.trap)
                        }

                        is VmOutcome.Faulted -> {
                            return PlaygroundExecution.Fault(outcome.fault)
                        }

                        is VmOutcome.HostFailed -> {
                            return PlaygroundExecution.HostFailure(outcome.kind, outcome.code)
                        }

                        is VmOutcome.CompilationRequested -> {
                            return unsupportedPlaygroundOutcome(outcome)
                        }
                    }
                }
            }
            PlaygroundExecution.PlatformFailure("maximum VM slice count exceeded")
        } catch (_: VmVerificationException) {
            PlaygroundExecution.VerificationFailure
        } catch (exception: VmAdmissionException) {
            PlaygroundExecution.AdmissionFailure(exception.code)
        } catch (exception: VmStartException) {
            PlaygroundExecution.StartFailure(exception.code)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            PlaygroundExecution.PlatformFailure(exception.message ?: exception::class.java.simpleName)
        }

    @Synchronized
    private fun loadLibrary() {
        if (loaded) return
        VmRuntime.loadNativeLibrary(library)
        loaded = true
    }

    private suspend fun publishTerminal(
        session: VmSession,
        published: String,
    ): PlaygroundExecution? {
        session.commitTerminal()
        val current = terminalText(session.terminalFullState())
        if (!current.startsWith(published)) {
            return PlaygroundExecution.PlatformFailure("standalone terminal output rewrote published cells")
        }
        val appended = current.substring(published.length)
        if (appended.isEmpty()) return null
        return when (val response = terminal.invoke(compatibilityRequest(WRITE_OPERATION, appended))) {
            HostResponse.UnitSuccess -> null
            is HostResponse.Failure -> PlaygroundExecution.HostFailure(response.kind, response.code)
            is HostResponse.BoolSuccess -> PlaygroundExecution.PlatformFailure("terminal output returned a Boolean")
            is HostResponse.StringSuccess -> PlaygroundExecution.PlatformFailure("terminal output returned an input line")
        }
    }

    private fun compatibilityRequest(
        operation: Int,
        value: String? = null,
    ): VmHostRequest =
        VmHostRequest(
            id = 1,
            capability = terminal.identity,
            operation = operation,
            arguments = value?.let { listOf(VmValue.StringValue(it)) }.orEmpty(),
        )

    private fun terminalText(state: TerminalState): String =
        buildString {
            repeat(state.cursor.y) { y ->
                val rowStart = y * state.width
                val row = StringBuilder(state.width)
                repeat(state.width) { x -> row.appendCodePoint(state.cells[rowStart + x].codePoint) }
                append(row.toString().trimEnd(' '))
                append('\n')
            }
            val rowStart = state.cursor.y * state.width
            repeat(state.cursor.x) { x -> appendCodePoint(state.cells[rowStart + x].codePoint) }
        }

    private companion object {
        const val WRITE_OPERATION = 0
        const val READ_OPERATION = 2
        const val GUEST_BUDGET = 4096
        const val MAINTENANCE_BUDGET = 4096
    }
}

internal fun unsupportedPlaygroundOutcome(outcome: VmOutcome.CompilationRequested): PlaygroundExecution.PlatformFailure {
    check(outcome.request.sources.isNotEmpty())
    return PlaygroundExecution.PlatformFailure("guest requested the unavailable in-game compiler service")
}
