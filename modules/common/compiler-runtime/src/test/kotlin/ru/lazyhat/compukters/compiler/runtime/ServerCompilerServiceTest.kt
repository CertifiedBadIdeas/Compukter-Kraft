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

package ru.lazyhat.compukters.compiler.runtime

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.cache.CompilationCachePolicy
import ru.lazyhat.compukters.compiler.cache.PersistentCompilationCache
import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerCompilerServiceTest {
    @Test
    fun `identical misses are single flight and a later hit avoids backend`() =
        fixture { service, backend, executor ->
            val source = snapshot("fun main() {}")
            val first = target(1)
            val second = target(2)
            assertEquals(CompilerSubmissionResult.ACCEPTED, service.submit(first, source))
            assertEquals(CompilerSubmissionResult.ACCEPTED, service.submit(second, source))
            assertEquals(0, backend.calls)
            assertTrue(service.drain(8).isEmpty())

            executor.runAll()
            assertEquals(1, backend.calls)
            backend.complete(byteArrayOf(4, 5, 6))
            executor.runAll()
            val completions = service.drain(8)
            assertEquals(listOf(first, second), completions.map(CompilerCompletion::target))
            completions.forEach {
                val success = assertIs<CompilerOutcome.Success>(it.outcome)
                assertFalse(success.cacheHit)
                assertContentEquals(byteArrayOf(4, 5, 6), success.artifact.toByteArray())
            }

            val third = target(3)
            assertEquals(CompilerSubmissionResult.ACCEPTED, service.submit(third, source))
            executor.runAll()
            assertEquals(1, backend.calls)
            assertTrue(assertIs<CompilerOutcome.Success>(service.drain(8).single().outcome).cacheHit)
        }

    @Test
    fun `configured target and platform modules reach the backend`() {
        val module = TrustedBundleIdentity.of("std:terminal", hash(3))
        val configuration =
            CompilerServiceConfiguration(
                identity(),
                WorkerLimits(),
                TargetSettings.KOTLIN_2_4_JVM_17,
                listOf(module),
            )
        fixture(configuration = configuration) { service, backend, executor ->
            service.submit(target(1), snapshot("fun main() {}"))

            executor.runAll()

            assertEquals(configuration.target, backend.target)
            assertEquals(configuration.platformModules, backend.platformModules)
        }
    }

    @Test
    fun `queue and waiter limits fail closed without unbounded backend work`() =
        fixture(
            policy = CompilerServicePolicy(maximumOutstandingTargets = 2, maximumWaitersPerIdentity = 1),
        ) { service, backend, executor ->
            val source = snapshot("fun main() {}")
            assertEquals(CompilerSubmissionResult.ACCEPTED, service.submit(target(1), source))
            assertEquals(CompilerSubmissionResult.ACCEPTED, service.submit(target(2), source))
            assertEquals(CompilerSubmissionResult.BUSY, service.submit(target(3), source))
            executor.runAll()
            assertEquals(1, backend.calls)
            val busy = service.drain(8).single()
            assertEquals(target(2), busy.target)
            assertIs<CompilerOutcome.Busy>(busy.outcome)
        }

    @Test
    fun `cancelling one waiter preserves shared work and close rejects submissions`() =
        fixture { service, backend, executor ->
            val source = snapshot("fun main() {}")
            val first = target(1)
            val second = target(2)
            service.submit(first, source)
            service.submit(second, source)
            executor.runAll()
            assertTrue(service.cancel(first))
            assertFalse(backend.cancelled)
            backend.complete(byteArrayOf(7))
            executor.runAll()
            assertEquals(second, service.drain(8).single().target)
            service.close()
            assertEquals(CompilerSubmissionResult.CLOSED, service.submit(target(3), source))
        }

    private fun fixture(
        policy: CompilerServicePolicy = CompilerServicePolicy(),
        configuration: CompilerServiceConfiguration = CompilerServiceConfiguration(identity(), WorkerLimits()),
        block: (ServerCompilerService, FakeBackend, ManualExecutor) -> Unit,
    ) {
        val root = Files.createTempDirectory("compukters-service-test-")
        val executor = ManualExecutor()
        val backend = FakeBackend()
        val limits = WorkerLimits()
        val cache =
            PersistentCompilationCache.open(
                root,
                CompilationCachePolicy(maximumEntries = 8, maximumArtifactBytes = 1024, maximumSingleArtifactBytes = 512),
                ArtifactVerifier { it.isNotEmpty() },
            )
        val service =
            ServerCompilerService(
                cache,
                backend,
                configuration,
                policy,
                executor,
            )
        try {
            block(service, backend, executor)
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun snapshot(text: String): ProjectSnapshot =
        ProjectSnapshot.of(
            listOf(ProjectSource(VirtualSourcePath.kotlin("main.kt"), BinaryValue.of(text.encodeToByteArray()))),
            WorkerLimits(),
        )

    private fun target(value: Long) = CompilerTarget(value, 1, value)

    private fun identity() = WorkerIdentity("test", "2.4", 1u, 1u, hash(1), hash(2))

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class FakeBackend : CompilerBackend {
        var calls = 0
        var cancelled = false
        lateinit var target: TargetSettings
        lateinit var platformModules: List<TrustedBundleIdentity>
        private lateinit var pending: CompletableFuture<CompileResult>

        override fun compile(
            snapshot: ProjectSnapshot,
            target: TargetSettings,
            platformModules: List<TrustedBundleIdentity>,
        ): CompletableFuture<CompileResult> {
            calls++
            this.target = target
            this.platformModules = platformModules
            pending = CompletableFuture()
            return pending
        }

        override fun cancel(future: CompletableFuture<CompileResult>) {
            cancelled = true
        }

        fun complete(bytes: ByteArray) {
            pending.complete(
                CompileSuccess(
                    RequestId.of(1uL),
                    BinaryValue.of(bytes),
                    Hash256.of(
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes),
                    ),
                    emptyList(),
                    CompilationMetrics(1u, 1u, 1u),
                ),
            )
        }
    }
}
