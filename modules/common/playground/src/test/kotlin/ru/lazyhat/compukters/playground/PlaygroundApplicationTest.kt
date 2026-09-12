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
import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaygroundApplicationTest {
    @Test
    fun `successful compilation emits exact artifact executes it and closes compiler`() =
        runBlocking {
            val artifact = byteArrayOf(1, 2, 3)
            val compiler = FakeCompiler(success(artifact))
            val executor = FakeExecutor(PlaygroundExecution.Success)
            val output = Streams()
            val directory = createTempDirectory("compukters-playground-app-")
            val emitted = directory.resolve("nested/program.cpkt")
            try {
                val exit = application(compiler, executor, output).run(listOf("project", "--emit", emitted.toString()))

                assertEquals(PlaygroundExit.SUCCESS, exit)
                assertContentEquals(artifact, emitted.readBytes())
                assertContentEquals(artifact, executor.artifact)
                assertTrue(compiler.closed)
                assertEquals("", output.stderr())
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `compiler diagnostics are source positioned and prevent execution`() =
        runBlocking {
            val diagnostic =
                WorkerDiagnostic(
                    DiagnosticSeverity.ERROR,
                    DiagnosticCategory.TYPE,
                    "TYPE_MISMATCH",
                    "expected String",
                    VirtualSourcePath.kotlin("src/main.kt"),
                    3u,
                    7u,
                )
            val compiler = FakeCompiler(CompilerFailure(ID, listOf(diagnostic), METRICS))
            val executor = FakeExecutor(PlaygroundExecution.Success)
            val output = Streams()

            val exit = application(compiler, executor, output).run(listOf("project"))

            assertEquals(PlaygroundExit.COMPILATION, exit)
            assertEquals("src/main.kt@3..7: error [type/TYPE_MISMATCH] expected String\n", output.stderr())
            assertFalse(executor.called)
            assertTrue(compiler.closed)
        }

    @Test
    fun `worker and VM terminal failures have stable exit categories`() =
        runBlocking {
            val worker = Streams()
            assertEquals(
                PlaygroundExit.COMPILER_PLATFORM,
                application(
                    FakeCompiler(PlatformFailure(ID, PlatformFailureClass.TIMEOUT, "deadline")),
                    FakeExecutor(PlaygroundExecution.Success),
                    worker,
                ).run(listOf("project")),
            )
            assertEquals("compiler timeout: deadline\n", worker.stderr())

            val cases =
                listOf(
                    PlaygroundExecution.VerificationFailure to PlaygroundExit.ARTIFACT,
                    PlaygroundExecution.AdmissionFailure(7) to PlaygroundExit.VM_START,
                    PlaygroundExecution.StartFailure(8) to PlaygroundExit.VM_START,
                    PlaygroundExecution.Trap(GuestTrap.DIVISION_BY_ZERO) to PlaygroundExit.GUEST_TRAP,
                    PlaygroundExecution.Fault(VmFault.CORRUPT_HEAP) to PlaygroundExit.VM_FAULT,
                    PlaygroundExecution.HostFailure(HostFailureKind.END_OF_FILE, 0) to PlaygroundExit.HOST_FAILURE,
                    PlaygroundExecution.Quota(QuotaKind.HOST_REQUESTS, 4, 5) to PlaygroundExit.QUOTA,
                    PlaygroundExecution.ResourceFailure(collectionAttempted = true) to PlaygroundExit.RESOURCE,
                    PlaygroundExecution.PlatformFailure("native bridge") to PlaygroundExit.PLATFORM,
                )
            cases.forEach { (result, expected) ->
                val streams = Streams()
                assertEquals(expected, application(FakeCompiler(success()), FakeExecutor(result), streams).run(listOf("project")))
                assertTrue(streams.stderr().isNotBlank(), result.toString())
            }
        }

    @Test
    fun `usage and unexpected failures hide stack traces unless debug is enabled`() =
        runBlocking {
            val usage = Streams()
            assertEquals(PlaygroundExit.USAGE, application(FakeCompiler(success()), FakeExecutor(), usage).run(emptyList()))
            assertTrue(usage.stderr().startsWith("usage:"))

            val quiet = Streams()
            assertEquals(
                PlaygroundExit.PLATFORM,
                application(FakeCompiler(failure = IllegalStateException("boom")), FakeExecutor(), quiet).run(listOf("project")),
            )
            assertFalse("IllegalStateException" in quiet.stderr())

            val debug = Streams()
            assertEquals(
                PlaygroundExit.PLATFORM,
                application(FakeCompiler(failure = IllegalStateException("boom")), FakeExecutor(), debug)
                    .run(listOf("project", "--debug")),
            )
            assertTrue("IllegalStateException" in debug.stderr())
        }

    @Test
    fun `coroutine cancellation is never converted into a platform result`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                application(
                    FakeCompiler(success()),
                    FakeExecutor(failure = CancellationException("stop")),
                    Streams(),
                ).run(listOf("project"))
            }
        }
    }

    private fun application(
        compiler: FakeCompiler,
        executor: FakeExecutor,
        streams: Streams,
    ) = PlaygroundApplication({ compiler }, executor, streams.err)

    private fun success(artifact: ByteArray = byteArrayOf(1)): CompileSuccess =
        CompileSuccess(ID, BinaryValue.of(artifact), Hash256.of(ByteArray(32)), emptyList(), METRICS)

    private class FakeCompiler(
        private val result: CompileResult? = null,
        private val failure: RuntimeException? = null,
    ) : PlaygroundCompiler {
        var closed = false

        override fun compile(project: Path): CompileResult = failure?.let { throw it } ?: checkNotNull(result)

        override fun close() {
            closed = true
        }
    }

    private class FakeExecutor(
        private val result: PlaygroundExecution = PlaygroundExecution.Success,
        private val failure: Exception? = null,
    ) : PlaygroundExecutor {
        var called = false
        var artifact: ByteArray? = null

        override suspend fun execute(artifact: ByteArray): PlaygroundExecution {
            failure?.let { throw it }
            called = true
            this.artifact = artifact.copyOf()
            return result
        }
    }

    private class Streams {
        private val errors = ByteArrayOutputStream()
        val err = PrintStream(errors, true, Charsets.UTF_8)

        fun stderr(): String = errors.toString(Charsets.UTF_8)
    }

    private companion object {
        val ID = RequestId.of(1uL)
        val METRICS = CompilationMetrics(0uL, 0uL, 0uL)
    }
}
