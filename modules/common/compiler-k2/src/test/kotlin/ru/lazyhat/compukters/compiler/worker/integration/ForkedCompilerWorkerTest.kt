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

package ru.lazyhat.compukters.compiler.worker.integration

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.controller.WorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForkedCompilerWorkerTest {
    @Test
    fun `forked worker resolves two project sources in one K2 session before lowering`() =
        withRealWorker { controller ->
            val snapshot =
                ProjectSnapshot.of(
                    listOf(
                        ProjectSource(
                            VirtualSourcePath.kotlin("project/Helper.kt"),
                            BinaryValue.of("package project\nfun shared() = 41".encodeToByteArray()),
                        ),
                        ProjectSource(
                            VirtualSourcePath.kotlin("project/Main.kt"),
                            BinaryValue.of("package project\nfun main() { shared() + 1 }".encodeToByteArray()),
                        ),
                    ),
                    WorkerLimits(),
                )

            val success = assertIs<CompileSuccess>(controller.compile(snapshot).get(90, TimeUnit.SECONDS))

            assertTrue(success.artifact.toByteArray().isNotEmpty())
        }

    @Test
    fun `real worker is deterministic and remains healthy after compiler failures`() {
        val payload = payload(Path.of(checkNotNull(System.getProperty("compukters.worker.payload"))))
        val temporaryRoot = createTempDirectory("compukters-forked-worker-")
        var starts = 0
        val jdk = JdkWorkerProcessFactory()
        val factory = WorkerProcessFactory { launch -> starts++.let { jdk.start(launch) } }
        val limits = WorkerLimits()
        val launch =
            WorkerLaunch(
                Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                512,
                256,
                temporaryRoot.resolve("child"),
                payload.manifest.identity,
                limits.frameBytes,
                limits.stderrBytes,
            )
        try {
            CompilerWorkerController(
                payload,
                launch,
                limits,
                factory,
                CompilerWorkerPolicy(startupTimeoutNanos = 30_000_000_000, compilationTimeoutNanos = 60_000_000_000),
            ).use { controller ->
                val program = "fun main() { val answer: Int = 42 }"
                val first = assertIs<CompileSuccess>(compile(controller, program))
                val second = assertIs<CompileSuccess>(compile(controller, program))
                assertContentEquals(first.artifact.toByteArray(), second.artifact.toByteArray())
                assertEquals(first.artifactHash, second.artifactHash)
                assertContentEquals(
                    byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte()),
                    first.artifact.toByteArray().copyOf(4),
                )

                val syntax = assertIs<CompilerFailure>(compile(controller, "fun main() { if ( }"))
                assertTrue(
                    syntax.diagnostics.any { it.category == DiagnosticCategory.SYNTAX },
                    syntax.diagnostics.toString(),
                )
                val type = assertIs<CompilerFailure>(compile(controller, "val answer: Missing = 42"))
                assertTrue(type.diagnostics.any { it.category == DiagnosticCategory.TYPE })
                assertIs<CompileSuccess>(compile(controller, program))
                assertEquals(1, starts, "compiler failures must not restart a healthy worker")
            }
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun compile(
        controller: CompilerWorkerController,
        source: String,
    ) = controller
        .compile(
            ProjectSnapshot.of(
                listOf(ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(source.encodeToByteArray()))),
                WorkerLimits(),
            ),
        ).get(90, TimeUnit.SECONDS)

    private fun withRealWorker(block: (CompilerWorkerController) -> Unit) {
        val payload = payload(Path.of(checkNotNull(System.getProperty("compukters.worker.payload"))))
        val temporaryRoot = createTempDirectory("compukters-forked-project-")
        val limits = WorkerLimits()
        val launch =
            WorkerLaunch(
                Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                512,
                256,
                temporaryRoot.resolve("child"),
                payload.manifest.identity,
                limits.frameBytes,
                limits.stderrBytes,
            )
        try {
            CompilerWorkerController(
                payload,
                launch,
                limits,
                JdkWorkerProcessFactory(),
                CompilerWorkerPolicy(startupTimeoutNanos = 30_000_000_000, compilationTimeoutNanos = 60_000_000_000),
            ).use(block)
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun payload(root: Path): PublishedWorkerPayload = WorkerPayloadLoader.loadToolingProfile(root)
}
