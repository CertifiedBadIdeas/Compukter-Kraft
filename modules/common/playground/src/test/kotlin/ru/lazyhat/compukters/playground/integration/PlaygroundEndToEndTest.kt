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

package ru.lazyhat.compukters.playground.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.lang.runtime.vm.FfmRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.playground.ForkedPlaygroundCompiler
import ru.lazyhat.compukters.playground.NativePlaygroundExecutor
import ru.lazyhat.compukters.playground.PlaygroundApplication
import ru.lazyhat.compukters.playground.PlaygroundExecution
import ru.lazyhat.compukters.playground.PlaygroundExecutor
import ru.lazyhat.compukters.playground.PlaygroundExit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaygroundEndToEndTest {
    init {
        VmRuntime.install(FfmRuntimeBackend)
    }

    @Test
    fun `real compiler FFM and VM execute hello with exact terminal transcript`() =
        runBlocking {
            val root = Path.of(requiredProperty("compukters.project.root"))
            val temporary = createTempDirectory("compukters-playground-e2e-")
            val emitted = temporary.resolve("hello.cpkt")
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            try {
                val application =
                    PlaygroundApplication(
                        compilerFactory = ::compiler,
                        executor =
                            NativePlaygroundExecutor(
                                Path.of(requiredProperty("compukters.ffi.library")),
                                ByteArrayInputStream("Ada\r\n".encodeToByteArray()),
                                stdout,
                            ),
                        stderr = PrintStream(stderr, true, Charsets.UTF_8),
                    )

                val exit = application.run(listOf(root.resolve("examples/hello").toString(), "--emit", emitted.toString()))

                assertEquals(PlaygroundExit.SUCCESS, exit, stderr.toString(Charsets.UTF_8))
                assertEquals("Your name: Hello, Ada!\n", stdout.toString(Charsets.UTF_8))
                assertEquals("", stderr.toString(Charsets.UTF_8))
                assertContentEquals("CPKT".encodeToByteArray(), emitted.readBytes().copyOf(4))
            } finally {
                temporary.toFile().deleteRecursively()
            }
        }

    @Test
    fun `real compiler reports positioned syntax diagnostics without entering VM`() =
        runBlocking {
            val project = createTempDirectory("compukters-playground-invalid-")
            val stderr = ByteArrayOutputStream()
            var executed = false
            try {
                project.resolve("compukter.toml").writeText("format = 1\nname = \"invalid\"\n")
                project.resolve("main.kt").writeText("fun main() { if ( }")
                val application =
                    PlaygroundApplication(
                        compilerFactory = ::compiler,
                        executor =
                            PlaygroundExecutor {
                                executed = true
                                PlaygroundExecution.Success
                            },
                        stderr = PrintStream(stderr, true, Charsets.UTF_8),
                    )

                val exit = application.run(listOf(project.toString()))
                val diagnostics = stderr.toString(Charsets.UTF_8)

                assertEquals(PlaygroundExit.COMPILATION, exit, diagnostics)
                assertTrue("main.kt@" in diagnostics, diagnostics)
                assertTrue(": error [syntax" in diagnostics, diagnostics)
                assertFalse(executed)
            } finally {
                project.toFile().deleteRecursively()
            }
        }

    private fun compiler() =
        ForkedPlaygroundCompiler(
            payloadRoot = Path.of(requiredProperty("compukters.worker.payload")),
            javaExecutable = javaExecutable(),
            policy =
                CompilerWorkerPolicy(
                    startupTimeoutNanos = 30_000_000_000,
                    compilationTimeoutNanos = 60_000_000_000,
                ),
        )

    private fun javaExecutable(): Path =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        )

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }
}
