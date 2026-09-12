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
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CompilerCompletionRouterTest {
    @Test
    fun `bounded drain routes completions by full computer epoch and token address`() {
        val compiler = FakeComputerCompiler()
        val router = CompilerCompletionRouter(compiler, maximumCompletionsPerDrain = 1)
        val first = address(computer = 1, epoch = 3, token = 7)
        val second = address(computer = 2, epoch = 3, token = 7)
        val request = listOf(VmCompilationSource("/home/main.kt", "fun main() {}".encodeToByteArray()))
        router.submit(first, request)
        router.submit(second, request)
        val firstOutcome = success(1)
        val secondOutcome = success(2)
        compiler.completions += ComputerCompilerCompletion(second, secondOutcome)
        compiler.completions += ComputerCompilerCompletion(first, firstOutcome)

        router.routeCompletions()

        assertEquals(listOf(1), compiler.drainLimits)
        assertEquals(secondOutcome, router.take(second))
        assertNull(router.take(first))

        router.routeCompletions()

        assertEquals(firstOutcome, router.take(first))
    }

    @Test
    fun `cancel removes an address and rejects a late completion`() {
        val compiler = FakeComputerCompiler()
        val router = CompilerCompletionRouter(compiler)
        val address = address(computer = 1, epoch = 1, token = 9)
        router.submit(address, listOf(VmCompilationSource("/home/main.kt", byteArrayOf(1))))

        router.cancel(address)
        compiler.completions += ComputerCompilerCompletion(address, success(3))
        router.routeCompletions()

        assertEquals(listOf(address), compiler.cancellations)
        assertNull(router.take(address))
    }

    @Test
    fun `submission owns an immutable copy of source bytes and source order`() {
        val compiler = FakeComputerCompiler()
        val router = CompilerCompletionRouter(compiler)
        val address = address(computer = 1, epoch = 1, token = 1)
        val bytes = byteArrayOf(1, 2, 3)
        val sources = mutableListOf(VmCompilationSource("/home/main.kt", bytes))

        router.submit(address, sources)
        bytes[0] = 9
        sources.clear()
        val captured = compiler.submissions.single().sources
        val exposed = captured.single().utf8Bytes()
        exposed[1] = 9

        assertEquals(listOf<Byte>(1, 2, 3), captured.single().utf8Bytes().toList())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (captured as MutableList<VmCompilationSource>).clear()
        }
    }

    @Test
    fun `compiler outcomes enforce FFM payload bounds without exposing contents in models`() {
        val success = ComputerCompilationOutcome.Success(byteArrayOf(1, 2))
        val failure = ComputerCompilationOutcome.Failure("private diagnostics")

        assertEquals("Success(artifactBytes=2)", success.toString())
        assertEquals("Failure(diagnosticBytes=19)", failure.toString())
        assertFailsWith<IllegalArgumentException> {
            ComputerCompilationOutcome.Failure("x".repeat(64 * 1024 + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            ComputerCompilationOutcome.Success(ByteArray(16 * 1024 * 1024 + 1))
        }
    }

    private class FakeComputerCompiler : ComputerCompiler {
        val submissions = mutableListOf<ComputerCompilationRequest>()
        val cancellations = mutableListOf<ComputerCompilationAddress>()
        val completions = ArrayDeque<ComputerCompilerCompletion>()
        val drainLimits = mutableListOf<Int>()

        override fun submit(request: ComputerCompilationRequest): CompilerSubmissionResult {
            submissions += request
            return CompilerSubmissionResult.ACCEPTED
        }

        override fun drain(maximum: Int): List<ComputerCompilerCompletion> {
            drainLimits += maximum
            return List(minOf(maximum, completions.size)) { completions.removeFirst() }
        }

        override fun cancel(address: ComputerCompilationAddress): Boolean {
            cancellations += address
            return true
        }
    }

    private companion object {
        fun address(
            computer: Long,
            epoch: Long,
            token: Long,
        ) = ComputerCompilationAddress(ComputerId.fromLongs(0, computer), epoch, token)

        fun success(marker: Byte): ComputerCompilationOutcome.Success = ComputerCompilationOutcome.Success(byteArrayOf(marker))
    }
}
