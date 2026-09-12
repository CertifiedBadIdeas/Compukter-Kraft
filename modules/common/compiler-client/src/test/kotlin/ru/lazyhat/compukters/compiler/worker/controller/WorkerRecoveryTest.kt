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
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerFeature
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkerRecoveryTest {
    @Test
    fun `startup and compilation timeouts are distinct and terminate after grace`() {
        withController(2) { controller, processes, identity, limits ->
            processes[0].enqueueTimeout()
            val startup = assertIs<PlatformFailure>(controller.compile(source("startup")).get(5, TimeUnit.SECONDS))
            assertEquals(PlatformFailureClass.WORKER_STARTUP, startup.failureClass)
            assertEquals(listOf(37L), processes[0].terminationGraces)
            assertEquals(listOf(111L), processes[0].readDeadlines)

            processes[1].enqueue(handshake(identity, limits))
            val timed = controller.compile(source("compile"))
            assertIs<CompileRequest>(processes[1].awaitWrite())
            processes[1].enqueueTimeout()
            assertEquals(PlatformFailureClass.TIMEOUT, assertIs<PlatformFailure>(timed.get(5, TimeUnit.SECONDS)).failureClass)
            assertEquals(listOf(37L), processes[1].terminationGraces)
            assertEquals(listOf(111L, 123L), processes[1].readDeadlines)
        }
    }

    @Test
    fun `cancellation invalidates active worker and next request restarts`() =
        withController(2) { controller, processes, identity, limits ->
            processes[0].enqueue(handshake(identity, limits))
            val cancelled = controller.compile(source("cancel"))
            assertIs<CompileRequest>(processes[0].awaitWrite())
            assertEquals(true, controller.cancel(cancelled))
            assertEquals(PlatformFailureClass.CANCELLED, assertIs<PlatformFailure>(cancelled.get(5, TimeUnit.SECONDS)).failureClass)
            assertEquals(listOf(37L), processes[0].terminationGraces)

            processes[1].enqueue(handshake(identity, limits))
            val recovered = controller.compile(source("recover"))
            val request = assertIs<CompileRequest>(processes[1].awaitWrite())
            processes[1].enqueue(success(request.requestId))
            assertIs<CompileSuccess>(recovered.get(5, TimeUnit.SECONDS))
        }

    @Test
    fun `OOM-like stderr is classified separately from an unexpected exit`() {
        withController(1) { controller, processes, identity, limits ->
            val worker = processes.single()
            worker.stderr = "java.lang.OutOfMemoryError: Java heap space".encodeToByteArray()
            worker.exitCode = 1
            worker.enqueue(handshake(identity, limits))
            val result = controller.compile(source("oom"))
            assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueueEof()
            assertEquals(PlatformFailureClass.MEMORY_LIMIT, assertIs<PlatformFailure>(result.get(5, TimeUnit.SECONDS)).failureClass)
        }

        withController(1) { controller, processes, identity, limits ->
            val worker = processes.single()
            worker.stderr = "worker stopped".encodeToByteArray()
            worker.exitCode = 7
            worker.enqueue(handshake(identity, limits))
            val result = controller.compile(source("exit"))
            assertIs<CompileRequest>(worker.awaitWrite())
            worker.enqueueEof()
            assertEquals(PlatformFailureClass.WORKER_EXIT, assertIs<PlatformFailure>(result.get(5, TimeUnit.SECONDS)).failureClass)
        }
    }

    private fun withController(
        processCount: Int,
        block: (CompilerWorkerController, List<FakeWorkerProcess>, WorkerIdentity, WorkerLimits) -> Unit,
    ) {
        val manifest = WorkerPayloadManifest.create(baseIdentity(), "example.WorkerMain", emptyMap())
        val payload = PublishedWorkerPayload(Path.of("payload"), manifest, listOf(Path.of("payload", "lib", "worker.jar")))
        val processes = List(processCount) { FakeWorkerProcess() }
        val limits = WorkerLimits()
        val launch = WorkerLaunch(Path.of("java"), 256, 128, Path.of("tmp"), manifest.identity)
        val policy = CompilerWorkerPolicy(startupTimeoutNanos = 11, compilationTimeoutNanos = 23, terminationGraceMillis = 37)
        CompilerWorkerController(payload, launch, limits, FakeWorkerProcessFactory(processes), policy) { 100 }.use { controller ->
            block(controller, processes, manifest.identity, limits)
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

    private fun success(requestId: RequestId): CompileSuccess {
        val artifact = byteArrayOf(1)
        return CompileSuccess(
            requestId,
            BinaryValue.of(artifact),
            Hash256.of(MessageDigest.getInstance("SHA-256").digest(artifact)),
            emptyList(),
            CompilationMetrics(1u, 2u, 3u),
        )
    }

    private fun baseIdentity() = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())
}
