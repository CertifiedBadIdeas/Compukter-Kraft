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

import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerMeasurementReport
import ru.lazyhat.compukters.compiler.worker.controller.WorkerMetrics
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadLoader
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkerMeasurementTest {
    @Test
    fun `records stable worker measurement fields without performance thresholds`() {
        val payload = measurementPayload(Path.of(checkNotNull(System.getProperty("compukters.worker.payload"))))
        val limits = WorkerLimits()
        val temporary = createTempDirectory("compukters-worker-measurement-")
        val launch =
            WorkerLaunch(
                Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                512,
                256,
                temporary.resolve("child"),
                payload.manifest.identity,
                limits.frameBytes,
                limits.stderrBytes,
            )
        val started = System.nanoTime()
        val process = JdkWorkerProcessFactory().start(launch.processLaunch(payload))
        try {
            assertIs<WorkerHandshake>(read(process.readFrame(deadline())!!, limits))
            val startupMillis = elapsedMillis(started)
            val firstRequest = request(1uL, payload.manifest.identity, limits)
            val firstStarted = System.nanoTime()
            val firstFrame = exchange(process, firstRequest, limits)
            val firstMillis = elapsedMillis(firstStarted)
            val first = assertIs<CompileSuccess>(read(firstFrame, limits))
            val warmRequest = request(2uL, payload.manifest.identity, limits)
            val warmStarted = System.nanoTime()
            val warmFrame = exchange(process, warmRequest, limits)
            val warmMillis = elapsedMillis(warmStarted)
            assertIs<CompileSuccess>(read(warmFrame, limits))

            val report =
                WorkerMeasurementReport(
                    payloadBytes = payload.manifest.files.sumOf { file -> file.bytes },
                    payloadSha256 = payload.manifest.payloadHash.hex(),
                    coldStartupMillis = startupMillis,
                    firstCompilationMillis = firstMillis,
                    warmCompilationMillis = warmMillis,
                    workerHeapBytes = first.metrics.heapBytes,
                    workerMetaspaceBytes = first.metrics.metaspaceBytes,
                    peakRssBytes = null,
                    requestBytes = encode(firstRequest).size,
                    resultBytes = firstFrame.size,
                    artifactBytes = first.artifact.size,
                )
            val output = Path.of(checkNotNull(System.getProperty("compukters.worker.measurement-report")))
            WorkerMetrics.write(report, output)
            assertEquals(report.toJson(), Files.readString(output))
            assertTrue(report.artifactBytes > 0)
        } finally {
            process.close()
            temporary.toFile().deleteRecursively()
        }
    }

    private fun exchange(
        process: ru.lazyhat.compukters.compiler.worker.controller.WorkerProcess,
        request: CompileRequest,
        limits: WorkerLimits,
    ): ByteArray {
        process.writeFrame(encode(request))
        return checkNotNull(process.readFrame(deadline())) { "worker exited before result" }.also {
            WorkerCodec.decodeFrame(it, limits.frameBytes)
        }
    }

    private fun request(
        id: ULong,
        identity: WorkerIdentity,
        limits: WorkerLimits,
    ) = CompileRequest(
        RequestId.of(id),
        listOf(
            ProjectSource(
                VirtualSourcePath.kotlin("project/main.kt"),
                BinaryValue.of("fun main() { val answer: Int = 42 }".encodeToByteArray()),
            ),
        ),
        TargetSettings.KOTLIN_2_4_JVM_17,
        identity,
        limits,
    )

    private fun encode(message: WorkerMessage) = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))

    private fun read(
        bytes: ByteArray,
        limits: WorkerLimits,
    ) = WorkerMessageCodec.decode(WorkerCodec.decodeFrame(bytes, limits.frameBytes))

    private fun deadline() = System.nanoTime() + 90_000_000_000

    private fun elapsedMillis(started: Long) = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000
}

private fun measurementPayload(root: Path): PublishedWorkerPayload = WorkerPayloadLoader.loadToolingProfile(root)
