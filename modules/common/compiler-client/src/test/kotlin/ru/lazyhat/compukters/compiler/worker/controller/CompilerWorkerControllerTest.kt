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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerFeature
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompilerWorkerControllerTest {
    @Test
    fun `trusted bundle identities are forwarded with the canonical snapshot`() =
        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits))
            val api = TrustedBundleIdentity.of("api", Hash256.of(ByteArray(32) { 1 }))
            val addon = TrustedBundleIdentity.of("addon", Hash256.of(ByteArray(32) { 2 }))

            val future = controller.compile(source("first"), platformModules = listOf(api, addon))
            val request = assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueue(success(request.requestId, byteArrayOf(1)))

            assertIs<CompileSuccess>(future.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(api, addon), request.platformModules)
        }

    @Test
    fun `worker starts lazily handshakes before compiling and is reused`() =
        withController(1) { controller, factory, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits))

            assertEquals(0, factory.starts.size)
            val first = controller.compile(source("first"))
            val firstRequest = assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueue(success(firstRequest.requestId, byteArrayOf(1)))
            assertIs<CompileSuccess>(first.get(5, TimeUnit.SECONDS))

            val second = controller.compile(source("second"))
            val secondRequest = assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueue(success(secondRequest.requestId, byteArrayOf(2)))
            assertIs<CompileSuccess>(second.get(5, TimeUnit.SECONDS))

            assertEquals(1, factory.starts.size)
            assertEquals(CompilerWorkerState.IDLE, controller.currentState)
            assertEquals(1uL, firstRequest.requestId.value)
            assertEquals(2uL, secondRequest.requestId.value)
            assertEquals("read:HANDSHAKE", worker.operations.first())
        }

    @Test
    fun `one request may run and one queue while a third is rejected`() =
        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits))
            val first = controller.compile(source("first"))
            val firstRequest = assertIs<CompileRequest>(worker.awaitWrite())
            val second = controller.compile(source("second"))
            val third = controller.compile(source("third"))

            val failure = runCatching { third.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertIs<ExecutionException>(failure)
            assertIs<WorkerQueueFullException>(failure.cause)

            worker.enqueue(success(firstRequest.requestId, byteArrayOf(1)))
            assertIs<CompileSuccess>(first.get(5, TimeUnit.SECONDS))
            val secondRequest = assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueue(success(secondRequest.requestId, byteArrayOf(2)))
            assertIs<CompileSuccess>(second.get(5, TimeUnit.SECONDS))
            assertEquals(2uL, secondRequest.requestId.value)
        }

    @Test
    fun `wrong terminal ID invalidates worker and next request starts another`() =
        withController(2) { controller, factory, processes, identity, limits ->
            processes[0].enqueue(handshake(identity, limits))
            val first = controller.compile(source("first"))
            val request = assertIs<CompileRequest>(processes[0].awaitWrite())
            processes[0].enqueue(success(RequestId.of(request.requestId.value + 1u), byteArrayOf(1)))

            val failure = assertIs<PlatformFailure>(first.get(5, TimeUnit.SECONDS))
            assertEquals(PlatformFailureClass.PROTOCOL, failure.failureClass)
            assertEquals(1, processes[0].terminationCount)
            assertEquals(CompilerWorkerState.STOPPED, controller.currentState)

            processes[1].enqueue(handshake(identity, limits))
            val second = controller.compile(source("second"))
            val secondRequest = assertIs<CompileRequest>(processes[1].awaitWrite())
            processes[1].enqueue(success(secondRequest.requestId, byteArrayOf(2)))
            assertIs<CompileSuccess>(second.get(5, TimeUnit.SECONDS))
            assertEquals(2, factory.starts.size)
        }

    @Test
    fun `duplicate result is rejected as the next request result`() =
        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits))
            val first = controller.compile(source("first"))
            val firstRequest = assertIs<CompileRequest>(worker.awaitWrite())
            val result = success(firstRequest.requestId, byteArrayOf(1))
            worker.enqueue(result)
            worker.enqueue(result)
            assertIs<CompileSuccess>(first.get(5, TimeUnit.SECONDS))

            val second = controller.compile(source("second"))
            assertIs<CompileRequest>(worker.awaitWrite())
            val failure = assertIs<PlatformFailure>(second.get(5, TimeUnit.SECONDS))
            assertEquals(PlatformFailureClass.PROTOCOL, failure.failureClass)
            assertEquals(1, worker.terminationCount)
        }

    @Test
    fun `wrong-state message and EOF invalidate the process`() {
        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits))
            val result = controller.compile(source("wrong-state"))
            assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueue(handshake(identity, limits))
            assertEquals(PlatformFailureClass.PROTOCOL, assertIs<PlatformFailure>(result.get(5, TimeUnit.SECONDS)).failureClass)
            assertFalse(worker.isAlive)
        }

        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits))
            val result = controller.compile(source("eof"))
            assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueueEof()
            assertEquals(PlatformFailureClass.WORKER_EXIT, assertIs<PlatformFailure>(result.get(5, TimeUnit.SECONDS)).failureClass)
            assertTrue(worker.terminationCount > 0)
        }
    }

    @Test
    fun `handshake identity and hard limits must match before a request is written`() {
        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity.copy(compilerVersion = "different"), limits))
            val failure = assertIs<PlatformFailure>(controller.compile(source("identity")).get(5, TimeUnit.SECONDS))
            assertEquals(PlatformFailureClass.PROTOCOL, failure.failureClass)
            assertEquals(listOf("read:HANDSHAKE"), worker.operations)
        }

        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits.copy(sourceBytes = limits.sourceBytes - 1)))
            val failure = assertIs<PlatformFailure>(controller.compile(source("limits")).get(5, TimeUnit.SECONDS))
            assertEquals(PlatformFailureClass.PROTOCOL, failure.failureClass)
            assertEquals(listOf("read:HANDSHAKE"), worker.operations)
        }
    }

    @Test
    fun `invalid artifact hash invalidates the worker`() =
        withController(1) { controller, _, processes, identity, limits ->
            val worker = processes.single()
            worker.enqueue(handshake(identity, limits))
            val result = controller.compile(source("artifact"))
            val request = assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueue(
                CompileSuccess(
                    request.requestId,
                    BinaryValue.of(byteArrayOf(1)),
                    Hash256.zero(),
                    emptyList(),
                    CompilationMetrics(1u, 2u, 3u),
                ),
            )

            val failure = assertIs<PlatformFailure>(result.get(5, TimeUnit.SECONDS))
            assertEquals(PlatformFailureClass.PROTOCOL, failure.failureClass)
            assertFalse(worker.isAlive)
        }

    private fun withController(
        processCount: Int,
        block: (CompilerWorkerController, FakeWorkerProcessFactory, List<FakeWorkerProcess>, WorkerIdentity, WorkerLimits) -> Unit,
    ) {
        val manifest = WorkerPayloadManifest.create(baseIdentity(), "example.WorkerMain", emptyMap())
        val payload = PublishedWorkerPayload(Path.of("payload"), manifest, listOf(Path.of("payload", "lib", "worker.jar")))
        val processes = List(processCount) { FakeWorkerProcess() }
        val factory = FakeWorkerProcessFactory(processes)
        val limits = WorkerLimits()
        val launch = WorkerLaunch(Path.of("java"), 256, 128, Path.of("tmp"), manifest.identity)
        CompilerWorkerController(payload, launch, limits, factory).use { controller ->
            block(controller, factory, processes, manifest.identity, limits)
        }
    }

    private fun source(text: String): ProjectSnapshot =
        ProjectSnapshot.of(
            listOf(ProjectSource(VirtualSourcePath.kotlin("main.kt"), BinaryValue.of(text.encodeToByteArray()))),
            WorkerLimits(),
        )

    private fun handshake(
        identity: WorkerIdentity,
        limits: WorkerLimits,
    ) = WorkerHandshake(identity, setOf(WorkerFeature.PROJECT_SNAPSHOT, WorkerFeature.KOTLIN_IR), limits)

    private fun success(
        requestId: RequestId,
        artifact: ByteArray,
    ): CompileResult =
        CompileSuccess(
            requestId,
            BinaryValue.of(artifact),
            Hash256.of(MessageDigest.getInstance("SHA-256").digest(artifact)),
            emptyList(),
            CompilationMetrics(1u, 2u, 3u),
        )

    private fun baseIdentity(): WorkerIdentity = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())
}
