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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.runtime.CompilerOutcome
import ru.lazyhat.compukters.compiler.runtime.CompilerServicePort
import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.compiler.runtime.CompilerTarget
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits

class ServerComputerCompiler(
    private val service: CompilerServicePort,
    private val limits: WorkerLimits,
) : ComputerCompiler {
    private val addresses = mutableMapOf<CompilerTarget, ComputerCompilationAddress>()
    private val targets = mutableMapOf<ComputerCompilationAddress, CompilerTarget>()
    private var nextOwner = 1L

    @Synchronized
    override fun submit(request: ComputerCompilationRequest): CompilerSubmissionResult {
        if (request.address in targets) return CompilerSubmissionResult.BUSY
        val target = CompilerTarget(nextOwner(), request.address.vmEpoch, request.address.token)
        val snapshot =
            ProjectSnapshot.of(
                request.sources.map { source ->
                    ProjectSource(
                        VirtualSourcePath.kotlin(source.path.removePrefix("/")),
                        BinaryValue.of(source.utf8Bytes()),
                    )
                },
                limits,
            )
        targets[request.address] = target
        addresses[target] = request.address
        return service.submit(target, snapshot).also { result ->
            if (result != CompilerSubmissionResult.ACCEPTED) remove(target, request.address)
        }
    }

    @Synchronized
    override fun drain(maximum: Int): List<ComputerCompilerCompletion> =
        service.drain(maximum).mapNotNull { completion ->
            val address = addresses.remove(completion.target) ?: return@mapNotNull null
            targets.remove(address)
            ComputerCompilerCompletion(address, completion.outcome.toComputerOutcome())
        }

    @Synchronized
    override fun cancel(address: ComputerCompilationAddress): Boolean {
        val target = targets.remove(address) ?: return false
        addresses.remove(target)
        return service.cancel(target)
    }

    private fun nextOwner(): Long {
        val value = nextOwner
        check(value != 0L) { "compiler target ID space exhausted" }
        nextOwner = if (value == Long.MAX_VALUE) 0 else value + 1
        return value
    }

    private fun remove(
        target: CompilerTarget,
        address: ComputerCompilationAddress,
    ) {
        targets.remove(address)
        addresses.remove(target)
    }
}

private fun CompilerOutcome.toComputerOutcome(): ComputerCompilationOutcome =
    when (this) {
        is CompilerOutcome.Success -> {
            ComputerCompilationOutcome.Success(artifact.toByteArray())
        }

        is CompilerOutcome.Rejected -> {
            ComputerCompilationOutcome.Failure(
                when (val rejection = result) {
                    is CompilerFailure -> {
                        rejection.diagnostics.joinToString("\n") { diagnostic ->
                            listOfNotNull(diagnostic.path?.value, diagnostic.message).joinToString(": ")
                        }
                    }

                    is PlatformFailure -> {
                        rejection.detail
                    }

                    else -> {
                        "compiler rejected the request"
                    }
                }.boundedDiagnostics(),
            )
        }

        is CompilerOutcome.PlatformFailure -> {
            ComputerCompilationOutcome.Failure(detail.boundedDiagnostics())
        }

        CompilerOutcome.Busy -> {
            ComputerCompilationOutcome.Failure("compiler busy")
        }
    }

private fun String.boundedDiagnostics(): String {
    if (encodeToByteArray().size <= MAXIMUM_DIAGNOSTIC_BYTES) return this
    var end = length
    while (end > 0 && substring(0, end).encodeToByteArray().size > MAXIMUM_DIAGNOSTIC_BYTES) end--
    return substring(0, end)
}

private const val MAXIMUM_DIAGNOSTIC_BYTES = 64 * 1024
