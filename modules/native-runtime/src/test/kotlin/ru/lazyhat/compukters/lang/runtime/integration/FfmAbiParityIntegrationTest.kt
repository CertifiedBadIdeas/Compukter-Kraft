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

import ru.lazyhat.compukters.lang.runtime.vm.FfmAbiFunction
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FfmAbiParityIntegrationTest {
    @Test
    fun `built library resolves and safely invokes every ABI v11 descriptor`() {
        Arena.ofConfined().use { arena ->
            val lookup = SymbolLookup.libraryLookup(Path.of(requiredProperty("compukter.ffi.library")), arena)
            val linker = Linker.nativeLinker()

            FfmAbiFunction.entries.forEach { function ->
                val symbol = lookup.find(function.symbol).orElseThrow()
                val handle = linker.downcallHandle(symbol, function.descriptor)
                val probe = probe(function)

                assertEquals(probe.expected, handle.invokeWithArguments(probe.arguments), function.symbol)
            }
        }
    }

    private fun probe(function: FfmAbiFunction): Probe {
        val arguments =
            function.descriptor
                .argumentLayouts()
                .map { layout ->
                    when (layout) {
                        ValueLayout.ADDRESS -> MemorySegment.NULL
                        ValueLayout.JAVA_LONG -> 0L
                        ValueLayout.JAVA_INT -> 0
                        ValueLayout.JAVA_SHORT -> 0.toShort()
                        else -> error("unsupported ${function.symbol} probe layout: $layout")
                    }
                }.toMutableList()
        return when (function) {
            FfmAbiFunction.ABI_VERSION -> {
                Probe(arguments, 11)
            }

            FfmAbiFunction.MAXIMUM_OUTCOME_BYTES -> {
                Probe(arguments, 64L * 1024L)
            }

            FfmAbiFunction.MAXIMUM_CREATE_BYTES -> {
                Probe(arguments, 9L)
            }

            FfmAbiFunction.STORE_CLOSE,
            FfmAbiFunction.DEPLOYMENT_CANDIDATE_CLOSE,
            FfmAbiFunction.CLOSE,
            FfmAbiFunction.REDSTONE_SUBMIT_INPUT,
            FfmAbiFunction.REDSTONE_CONFIRM_OUTPUT,
            FfmAbiFunction.TERMINAL_COMMIT,
            -> {
                Probe(arguments, STATUS_INVALID_HANDLE)
            }

            FfmAbiFunction.RESOURCE_SNAPSHOT -> {
                Probe(arguments, STATUS_INVALID_ARGUMENT)
            }

            FfmAbiFunction.VERIFY_ARTIFACT -> {
                Probe(arguments.also { it[1] = 1L }, STATUS_INVALID_ARGUMENT)
            }

            FfmAbiFunction.FILESYSTEM_LIST -> {
                Probe(arguments.also { it[5] = 1 }, STATUS_INVALID_ARGUMENT)
            }

            FfmAbiFunction.FILESYSTEM_READ -> {
                Probe(arguments.also { it[4] = 1 }, STATUS_INVALID_ARGUMENT)
            }

            FfmAbiFunction.COMPILATION_COMPLETE -> {
                Probe(arguments.also { it[2] = 2 }, STATUS_INVALID_ARGUMENT)
            }

            FfmAbiFunction.SUBMIT_CANONICAL_LINE,
            FfmAbiFunction.TERMINAL_TEXT,
            -> {
                Probe(arguments.also { it[2] = 1L }, STATUS_INVALID_ARGUMENT)
            }

            FfmAbiFunction.TERMINAL_KEY -> {
                Probe(arguments.also { it[2] = 2 }, STATUS_INVALID_ARGUMENT)
            }

            FfmAbiFunction.STORE_OPEN,
            FfmAbiFunction.STORE_HEALTH,
            FfmAbiFunction.STORE_DURABLE_GENERATION,
            FfmAbiFunction.STORE_FLUSH,
            FfmAbiFunction.STORE_TOMBSTONE,
            FfmAbiFunction.STORE_RECOVER,
            FfmAbiFunction.CREATE,
            FfmAbiFunction.CREATE_IN_STORE,
            FfmAbiFunction.CREATE_BOOT_IN_STORE,
            FfmAbiFunction.VERIFY_FOR_DEPLOY,
            FfmAbiFunction.EXECUTABLE_REVISION,
            FfmAbiFunction.DEPLOY,
            FfmAbiFunction.FILESYSTEM_GENERATION,
            FfmAbiFunction.FILESYSTEM_STAT,
            FfmAbiFunction.ADVANCE,
            FfmAbiFunction.COMPILATION_REQUEST_SIZE,
            FfmAbiFunction.COMPILATION_REQUEST_COPY,
            FfmAbiFunction.RESUME_UNIT,
            FfmAbiFunction.RESUME_BOOL,
            FfmAbiFunction.RESUME_STRING,
            FfmAbiFunction.RESUME_FAILURE,
            FfmAbiFunction.TERMINAL_FULL_STATE,
            FfmAbiFunction.TERMINAL_CHANGES_SINCE,
            -> {
                Probe(arguments, STATUS_INVALID_ARGUMENT)
            }
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name" }

    private data class Probe(
        val arguments: List<Any>,
        val expected: Any,
    )

    private companion object {
        const val STATUS_INVALID_ARGUMENT = 1
        const val STATUS_INVALID_HANDLE = 5
    }
}
