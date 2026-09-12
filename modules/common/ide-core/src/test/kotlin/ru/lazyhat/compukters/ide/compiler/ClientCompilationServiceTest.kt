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

package ru.lazyhat.compukters.ide.compiler

import ru.lazyhat.compukters.compiler.cache.ArtifactVerifier
import ru.lazyhat.compukters.compiler.cache.CompilationCachePolicy
import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.WorkerQueueFullException
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfile
import ru.lazyhat.compukters.ide.compiler.profile.platformBundle
import ru.lazyhat.compukters.ide.compiler.profile.platformToolchain
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientCompilationServiceTest {
    @Test
    fun `deduplicates active build and admits one distinct queued build`() =
        withService { service, backend, _ ->
            val first = service.build(input("fun main() = 1"))
            assertSame(first, service.build(input("fun main() = 1")))
            backend.awaitCalls(1)
            val second = service.build(input("fun main() = 2"))
            val rejected = service.build(input("fun main() = 3"))

            assertIs<WorkerQueueFullException>(assertIs<ExecutionException>(runCatching { rejected.get() }.exceptionOrNull()).cause)
            backend.complete(0, success(backend.requests[0], byteArrayOf(1)))
            assertIs<ClientBuildResult.Success>(first.get(5, TimeUnit.SECONDS))
            backend.awaitCalls(2)
            backend.complete(1, success(backend.requests[1], byteArrayOf(2)))
            assertIs<ClientBuildResult.Success>(second.get(5, TimeUnit.SECONDS))
        }

    @Test
    fun `cache hit avoids another worker request and all IO stays on service thread`() =
        withService { service, backend, verifierThreads ->
            val input = input("fun main() = 4")
            val first = service.build(input)
            backend.awaitCalls(1)
            backend.complete(0, success(backend.requests[0], byteArrayOf(4, 5)))
            assertFalse(assertIs<ClientBuildResult.Success>(first.get(5, TimeUnit.SECONDS)).cacheHit)

            val hit = assertIs<ClientBuildResult.Success>(service.build(input).get(5, TimeUnit.SECONDS))
            assertTrue(hit.cacheHit)
            assertContentEquals(byteArrayOf(4, 5), hit.artifact.toByteArray())
            assertEquals(1, backend.requests.size)
            assertTrue(backend.threads.all { name -> name.startsWith("compukters-client-compilation-") })
            assertTrue(verifierThreads.all { name -> name.startsWith("compukters-client-compilation-") })
        }

    @Test
    fun `active cancellation reaches backend and later build recovers`() =
        withService { service, backend, _ ->
            val cancelled = service.build(input("fun main() = 5"))
            backend.awaitCalls(1)

            assertTrue(service.cancel(cancelled))
            backend.awaitCancelCalls(1)
            assertEquals(1, backend.cancelCalls)
            assertTrue(backend.cancelThreads.all { name -> name.startsWith("compukters-client-compilation-") })
            val cancellation = assertIs<ClientBuildResult.PlatformFailure>(cancelled.get(5, TimeUnit.SECONDS))
            assertEquals(PlatformFailureClass.CANCELLED, cancellation.failureClass)

            val recovered = service.build(input("fun main() = 6"))
            backend.awaitCalls(2)
            backend.complete(1, success(backend.requests[1], byteArrayOf(6)))
            assertIs<ClientBuildResult.Success>(recovered.get(5, TimeUnit.SECONDS))
        }

    private fun withService(block: (ClientCompilationService, FakeBackend, List<String>) -> Unit) {
        val root = Files.createTempDirectory("compukters-client-service-").toAbsolutePath().normalize()
        val verifierThreads = mutableListOf<String>()
        val cache =
            ClientCompilationCache.open(
                root,
                CompilationCachePolicy(maximumEntries = 8, maximumArtifactBytes = 1024, maximumSingleArtifactBytes = 512),
                ArtifactVerifier { artifact ->
                    synchronized(verifierThreads) { verifierThreads += Thread.currentThread().name }
                    artifact.isNotEmpty()
                },
            )
        val backend = FakeBackend()
        val service = DefaultClientCompilationService(cache, backend)
        try {
            block(service, backend, verifierThreads)
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun input(source: String): ClientBuildSnapshot {
        val limits = WorkerLimits(sourceFiles = 4, sourceFileBytes = 1024, sourceBytes = 2048)
        val platform = platformBundle()
        return ClientBuildSnapshot(
            ProjectSnapshot.of(
                listOf(ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(source.encodeToByteArray()))),
                limits,
            ),
            BinaryValue.of("manifest".encodeToByteArray()),
            BinaryValue.of("lock".encodeToByteArray()),
            CompileProfile(platformToolchain(platform), platform.identity, emptySet(), emptyList(), limits),
        )
    }

    private fun success(
        request: CompileRequest,
        artifact: ByteArray,
    ): CompileSuccess =
        CompileSuccess(
            request.requestId,
            BinaryValue.of(artifact),
            Hash256.of(MessageDigest.getInstance("SHA-256").digest(artifact)),
            emptyList(),
            CompilationMetrics(1uL, 1uL, 1uL),
        )

    private class FakeBackend : ClientCompilerBackend {
        val requests = mutableListOf<CompileRequest>()
        val threads = mutableListOf<String>()
        val cancelThreads = mutableListOf<String>()
        private val futures = mutableListOf<CompletableFuture<CompileResult>>()
        var cancelCalls = 0

        @Synchronized
        override fun compile(request: CompileRequest): CompletableFuture<CompileResult> {
            requests += request
            threads += Thread.currentThread().name
            return CompletableFuture<CompileResult>().also(futures::add)
        }

        @Synchronized
        override fun cancel(future: CompletableFuture<CompileResult>): Boolean {
            cancelCalls++
            cancelThreads += Thread.currentThread().name
            val index = futures.indexOf(future)
            if (index < 0 || future.isDone) return false
            return future.complete(PlatformFailure(requests[index].requestId, PlatformFailureClass.CANCELLED, "cancelled"))
        }

        @Synchronized
        fun complete(
            index: Int,
            result: CompileResult,
        ) {
            futures[index].complete(result)
        }

        fun awaitCalls(expected: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (synchronized(this) { requests.size >= expected }) return
                Thread.onSpinWait()
            }
            error("backend received ${synchronized(this) { requests.size }} requests, expected $expected")
        }

        fun awaitCancelCalls(expected: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (synchronized(this) { cancelCalls >= expected }) return
                Thread.onSpinWait()
            }
            error("backend received ${synchronized(this) { cancelCalls }} cancellations, expected $expected")
        }

        override fun close() = Unit
    }
}
