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

package ru.lazyhat.compukters.core.device.runtime.compiler

import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource

class CompilerCompletionRouter(
    private val compiler: ComputerCompiler,
    private val maximumCompletionsPerDrain: Int = 32,
) {
    private val active = mutableSetOf<ComputerCompilationAddress>()
    private val routed = mutableMapOf<ComputerCompilationAddress, ComputerCompilationOutcome>()

    init {
        require(maximumCompletionsPerDrain > 0) { "compiler completion drain limit must be positive" }
    }

    @Synchronized
    fun submit(
        address: ComputerCompilationAddress,
        sources: List<VmCompilationSource>,
    ): CompilerSubmissionResult {
        if (address in active || address in routed) return CompilerSubmissionResult.BUSY
        val result = compiler.submit(ComputerCompilationRequest(address, sources))
        if (result == CompilerSubmissionResult.ACCEPTED) active += address
        return result
    }

    @Synchronized
    fun routeCompletions() {
        compiler.drain(maximumCompletionsPerDrain).forEach { completion ->
            if (active.remove(completion.address)) routed[completion.address] = completion.outcome
        }
    }

    @Synchronized
    fun take(address: ComputerCompilationAddress): ComputerCompilationOutcome? = routed.remove(address)

    @Synchronized
    fun cancel(address: ComputerCompilationAddress): Boolean {
        val wasActive = active.remove(address)
        val wasRouted = routed.remove(address) != null
        val compilerCancelled = compiler.cancel(address)
        return wasActive || wasRouted || compilerCancelled
    }
}
