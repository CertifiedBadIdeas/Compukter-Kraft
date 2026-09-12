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
import ru.lazyhat.compukters.compiler.runtime.CompilerCompletion
import ru.lazyhat.compukters.compiler.runtime.CompilerOutcome
import ru.lazyhat.compukters.compiler.runtime.CompilerServicePort
import ru.lazyhat.compukters.compiler.runtime.CompilerSubmissionResult
import ru.lazyhat.compukters.compiler.runtime.CompilerTarget
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.VmCompilationSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerComputerCompilerTest {
    @Test
    fun `full VM address survives opaque service targets and copied snapshots`() {
        val port = FakePort()
        val compiler = ServerComputerCompiler(port, WorkerLimits())
        val first = address(1, 7)
        val second = address(2, 7)
        val bytes = "fun main() {}".encodeToByteArray()

        assertEquals(
            CompilerSubmissionResult.ACCEPTED,
            compiler.submit(ComputerCompilationRequest(first, listOf(VmCompilationSource("/home/main.kt", bytes)))),
        )
        assertEquals(
            CompilerSubmissionResult.ACCEPTED,
            compiler.submit(ComputerCompilationRequest(second, listOf(VmCompilationSource("/home/main.kt", bytes)))),
        )
        bytes.fill(0)
        assertNotEquals(port.submissions[0].first, port.submissions[1].first)
        assertEquals(
            "home/main.kt",
            port.submissions[0]
                .second.sources
                .single()
                .path.value,
        )
        assertContentEquals(
            "fun main() {}".encodeToByteArray(),
            port.submissions[0]
                .second.sources
                .single()
                .content
                .toByteArray(),
        )

        port.completions +=
            CompilerCompletion(
                port.submissions[1].first,
                CompilerOutcome.Success(byteArrayOf(1, 2, 3), Hash256.zero(), cacheHit = false),
            )
        val completion = compiler.drain(1).single()
        assertEquals(second, completion.address)
        assertEquals(ComputerCompilationOutcome.Success(byteArrayOf(1, 2, 3)), completion.outcome)
        assertTrue(compiler.cancel(first))
        assertEquals(port.submissions[0].first, port.cancelled.single())
    }

    private fun address(
        high: Long,
        low: Long,
    ) = ComputerCompilationAddress(ComputerId.fromLongs(high, low), 3, 5)

    private class FakePort : CompilerServicePort {
        val submissions = mutableListOf<Pair<CompilerTarget, ProjectSnapshot>>()
        val completions = ArrayDeque<CompilerCompletion>()
        val cancelled = mutableListOf<CompilerTarget>()

        override fun submit(
            target: CompilerTarget,
            snapshot: ProjectSnapshot,
        ): CompilerSubmissionResult {
            submissions.add(target to snapshot)
            return CompilerSubmissionResult.ACCEPTED
        }

        override fun drain(maximum: Int): List<CompilerCompletion> =
            buildList { repeat(minOf(maximum, completions.size)) { add(completions.removeFirst()) } }

        override fun cancel(target: CompilerTarget): Boolean {
            cancelled.add(target)
            return true
        }
    }
}
