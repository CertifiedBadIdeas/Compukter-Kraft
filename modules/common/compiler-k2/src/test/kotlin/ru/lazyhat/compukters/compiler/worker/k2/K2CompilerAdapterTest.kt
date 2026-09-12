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

package ru.lazyhat.compukters.compiler.worker.k2

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudgetException
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class K2CompilerAdapterTest {
    @Test
    fun `worker packages one compiled platform bundle instead of trusted source inputs`() {
        assertTrue(K2CompilerAdapter.loadPackagedPlatform().modules.isNotEmpty())
        assertNull(
            K2CompilerAdapter::class.java.getResourceAsStream(
                "/compukters-platform/sources/libraries/std-terminal/compukter/terminal/Terminal.kt",
            ),
        )
    }

    @Test
    fun `valid Kotlin source reaches IR and request files are removed`() =
        withAdapter { adapter, root ->
            val result = adapter.compile(request("fun main() { val answer: Int = 42 }"))
            assertTrue(result.reachedIr)
            assertFalse(result.hasErrors)
            assertNotNull(result.artifact)
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    @Test
    fun `syntax and type diagnostics use virtual paths and UTF-16 offsets`() =
        withAdapter { adapter, _ ->
            val syntax = adapter.compile(request("fun main() { if ( }"))
            val syntaxError =
                assertNotNull(
                    syntax.diagnostics.firstOrNull {
                        it.severity == DiagnosticSeverity.ERROR && it.category == DiagnosticCategory.SYNTAX
                    },
                    syntax.diagnostics.toString(),
                )
            assertEquals(DiagnosticCategory.SYNTAX, syntaxError.category, syntax.diagnostics.toString())
            assertEquals("project/virtual.kt", syntaxError.path?.value)
            assertTrue(syntaxError.startUtf16 != null)
            assertNull(syntax.artifact)

            val type = adapter.compile(request("val answer: MissingType = 42"))
            assertTrue(type.diagnostics.any { it.category == DiagnosticCategory.TYPE && it.severity == DiagnosticSeverity.ERROR })
            assertNull(type.artifact)
        }

    @Test
    fun `Guest JVM value classes are rejected before FIR`() =
        withAdapter { adapter, _ ->
            val result = adapter.compile(request("@JvmInline value class Guest(val value: Int)"))

            assertFalse(result.reachedIr)
            assertTrue(result.diagnostics.any { it.code == "JVM_INLINE" })
            assertNull(result.artifact)
        }

    @Test
    fun `dependency refinement and mod imports cannot expand compiler inputs`() =
        withAdapter { adapter, _ ->
            val dependency = adapter.compile(request("@file:DependsOn(\"evil:payload:1\")\nval answer = 42"))
            assertFalse(dependency.reachedIr)
            assertTrue(dependency.diagnostics.any { it.category == DiagnosticCategory.TARGET })

            val modImport = adapter.compile(request("import ru.lazyhat.compukters.Compukters\nval answer = Compukters"))
            assertFalse(modImport.reachedIr)
            assertTrue(modImport.diagnostics.any { it.category == DiagnosticCategory.TYPE })
        }

    @Test
    fun `cross-file reference participates in one K2 session before bounded lowering`() =
        withAdapter { adapter, _ ->
            val result =
                adapter.compile(
                    request(
                        listOf(
                            source("project/Helper.kt", "package project\nfun shared() = 41"),
                            source("project/Main.kt", "package project\nfun main() { shared() + 1 }"),
                        ),
                    ),
                )

            assertTrue(result.reachedIr)
            assertNotNull(result.artifact, result.diagnostics.joinToString())
            assertFalse(result.hasErrors, result.diagnostics.toString())
            assertFalse(result.diagnostics.any { it.message.contains("unresolved reference", ignoreCase = true) })
        }

    @Test
    fun `diagnostic count text and physical paths are bounded`() {
        val physical = Path.of("private/request/source/main.kt")
        val collector =
            CompilerDiagnosticCollector(
                "val x = 1",
                physical,
                VirtualSourcePath.kotlin("project/main.kt"),
                WorkerLimits(diagnostics = 1, diagnosticTextBytes = 8),
            )
        repeat(3) {
            collector.report(
                CompilerMessageSeverity.ERROR,
                "$physical diagnostic text that is too long",
                CompilerMessageLocation.create(physical.toString(), 1, 5, "val x = 1"),
            )
        }

        assertEquals(1, collector.diagnostics.size)
        assertTrue(
            collector.diagnostics
                .single()
                .message
                .encodeToByteArray()
                .size <= 8,
        )
        assertFalse(
            collector.diagnostics
                .single()
                .message
                .contains("private/request"),
        )
        assertTrue(collector.hasErrors())
    }

    @Test
    fun `request temporary storage budget is enforced and cleaned`() =
        withAdapter { adapter, root ->
            assertFailsWith<TemporaryBudgetException> {
                adapter.compile(request("val answer: Int = 42", WorkerLimits(temporaryBytes = 0)))
            }
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    @Test
    fun `source footprint is rejected before temporary materialization`() {
        val source = "val answer: Int = 42"
        listOf(
            WorkerLimits(temporaryBytes = source.encodeToByteArray().size.toLong() - 1),
            WorkerLimits(temporaryFiles = 0),
        ).forEach { limits ->
            withAdapter { adapter, root ->
                root.fileSystem.newWatchService().use { watcher ->
                    root.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)

                    assertFailsWith<TemporaryBudgetException> { adapter.compile(request(source, limits)) }

                    assertNull(watcher.poll(), "temporary storage was created before rejecting $limits")
                }
            }
        }
    }

    @Test
    fun `IR compilation writes no temporary output beyond its exact source footprint`() =
        withAdapter { adapter, root ->
            val source = "fun main() { val answer: Int = 42 }"
            val result =
                adapter.compile(
                    request(
                        source,
                        WorkerLimits(
                            temporaryBytes = source.encodeToByteArray().size.toLong(),
                            // source/, source/project/, and the user source.
                            temporaryFiles = 3,
                        ),
                    ),
                )

            assertTrue(result.reachedIr)
            assertFalse(result.hasErrors)
            assertNotNull(result.artifact)
            Files.list(root).use { assertEquals(0, it.count()) }
        }

    private fun withAdapter(block: (K2CompilerAdapter, Path) -> Unit) {
        val root = createTempDirectory("compukters-k2-adapter-test-")
        try {
            val adapter =
                K2CompilerAdapter(
                    K2CompilerInputs(
                        temporaryRoot = root,
                        workerJar = Path.of(checkNotNull(System.getProperty("compukters.worker.jar"))),
                        expectedIdentity = identity(),
                    ),
                )
            block(adapter, root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun request(
        source: String,
        limits: WorkerLimits = WorkerLimits(),
    ): CompileRequest = request(listOf(source("project/virtual.kt", source)), limits)

    private fun request(
        sources: List<ProjectSource>,
        limits: WorkerLimits = WorkerLimits(),
    ): CompileRequest =
        CompileRequest(
            RequestId.of(1u),
            sources,
            TargetSettings.KOTLIN_2_4_JVM_17,
            identity(),
            limits,
        )

    private fun source(
        path: String,
        content: String,
    ) = ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(content.encodeToByteArray()))

    private fun identity() =
        WorkerIdentity(
            "2.4.10",
            "2.4",
            1u,
            1u,
            Hash256.zero(),
            Hash256.of(
                K2CompilerAdapter
                    .loadPackagedPlatform()
                    .identity.contentHash
                    .toByteArray(),
            ),
        )
}
