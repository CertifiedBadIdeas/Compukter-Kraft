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

package ru.lazyhat.compukters.compiler.worker.server

import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudgetException
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilationResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerFeature
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest

fun interface CompilationHandler {
    fun compile(request: CompileRequest): K2CompilationResult
}

enum class WorkerServerExit { CLEAN_EOF, PROTOCOL_ERROR }

class CompilerWorkerServer(
    private val identity: WorkerIdentity,
    private val hardLimits: WorkerLimits,
    private val input: InputStream,
    private val output: OutputStream,
    private val compiler: CompilationHandler,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(): WorkerServerExit {
        write(
            WorkerHandshake(
                identity,
                setOf(WorkerFeature.PROJECT_SNAPSHOT, WorkerFeature.KOTLIN_IR),
                hardLimits,
            ),
        )
        while (true) {
            val bytes =
                try {
                    readFrame() ?: return WorkerServerExit.CLEAN_EOF
                } catch (_: IllegalArgumentException) {
                    return WorkerServerExit.PROTOCOL_ERROR
                }
            val request =
                try {
                    WorkerMessageCodec.decode(WorkerCodec.decodeFrame(bytes, hardLimits.frameBytes)) as? CompileRequest
                        ?: return WorkerServerExit.PROTOCOL_ERROR
                } catch (_: IllegalArgumentException) {
                    return WorkerServerExit.PROTOCOL_ERROR
                }
            val admitted = admit(request)
            if (admitted == null) {
                write(PlatformFailure(request.requestId, PlatformFailureClass.PROTOCOL, "compile request exceeds worker limits"))
                return WorkerServerExit.PROTOCOL_ERROR
            }
            write(compile(admitted))
        }
    }

    private fun compile(request: CompileRequest): CompileResult {
        val started = nanoTime()
        val result =
            try {
                compiler.compile(request)
            } catch (exception: TemporaryBudgetException) {
                return PlatformFailure(
                    request.requestId,
                    PlatformFailureClass.OUTPUT_LIMIT,
                    bounded(exception.message ?: "temporary storage limit exceeded", request.limits.diagnosticTextBytes),
                )
            } catch (exception: Exception) {
                return PlatformFailure(
                    request.requestId,
                    PlatformFailureClass.INTERNAL_COMPILER,
                    bounded(exception.message ?: "compiler invocation failed", request.limits.diagnosticTextBytes),
                )
            }
        val metrics = metrics(elapsed(started, nanoTime()))
        val artifact = result.artifact
        return when {
            artifact != null && !result.hasErrors -> {
                CompileSuccess(
                    request.requestId,
                    artifact,
                    Hash256.of(MessageDigest.getInstance("SHA-256").digest(artifact.toByteArray())),
                    result.diagnostics.filter { it.severity != DiagnosticSeverity.ERROR },
                    metrics,
                )
            }

            result.hasErrors -> {
                CompilerFailure(request.requestId, result.diagnostics, metrics)
            }

            else -> {
                PlatformFailure(
                    request.requestId,
                    PlatformFailureClass.INTERNAL_COMPILER,
                    "compiler returned neither artifact nor diagnostics",
                )
            }
        }
    }

    private fun admit(request: CompileRequest): CompileRequest? {
        if (request.expectedIdentity != identity || request.sources.size > hardLimits.sourceFiles) return null
        if (request.sources.any { it.content.size > hardLimits.sourceFileBytes }) return null
        if (request.sources.sumOf { it.content.size.toLong() } > hardLimits.sourceBytes.toLong()) return null
        val tightened = request.limits.tightenedWith(hardLimits)
        if (request.sources.size > tightened.sourceFiles) return null
        if (request.sources.any { it.content.size > tightened.sourceFileBytes }) return null
        if (request.sources.sumOf { it.content.size.toLong() } > tightened.sourceBytes.toLong()) return null
        return request.copy(limits = tightened)
    }

    private fun readFrame(): ByteArray? {
        val header = input.readExactlyOrEof(FRAME_HEADER_BYTES) ?: return null
        val payloadBytes =
            (0 until 4).fold(0L) { value, index ->
                value or ((header[8 + index].toLong() and 0xff) shl (index * 8))
            }
        if (payloadBytes > hardLimits.frameBytes || payloadBytes > Int.MAX_VALUE) {
            throw IllegalArgumentException("frame exceeds worker limit")
        }
        return header + input.readExactly(payloadBytes.toInt())
    }

    private fun write(message: ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage) {
        val bytes = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))
        require(bytes.size <= hardLimits.frameBytes) { "worker output frame exceeds hard limit" }
        output.write(bytes)
        output.flush()
    }

    private fun metrics(wallNanos: ULong): CompilationMetrics {
        val runtime = Runtime.getRuntime()
        val heap = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0).toULong()
        val metaspace =
            ManagementFactory
                .getMemoryPoolMXBeans()
                .filter { it.name.contains("Metaspace", ignoreCase = true) }
                .sumOf { it.usage?.used?.coerceAtLeast(0) ?: 0L }
                .toULong()
        return CompilationMetrics(wallNanos, heap, metaspace)
    }

    private fun elapsed(
        start: Long,
        end: Long,
    ): ULong = (end - start).coerceAtLeast(0).toULong()

    private companion object {
        const val FRAME_HEADER_BYTES = 12
    }
}

private fun InputStream.readExactlyOrEof(count: Int): ByteArray? {
    val first = read()
    if (first < 0) return null
    val bytes = ByteArray(count)
    bytes[0] = first.toByte()
    var offset = 1
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read < 0) throw IllegalArgumentException("frame is truncated")
        if (read > 0) offset += read
    }
    return bytes
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read < 0) throw IllegalArgumentException("frame is truncated")
        if (read > 0) offset += read
    }
    return bytes
}

private fun WorkerLimits.tightenedWith(hard: WorkerLimits): WorkerLimits =
    WorkerLimits(
        sourceFiles = minOf(sourceFiles, hard.sourceFiles),
        sourceFileBytes = minOf(sourceFileBytes, hard.sourceFileBytes),
        sourceBytes = minOf(sourceBytes, hard.sourceBytes),
        frameBytes = minOf(frameBytes, hard.frameBytes),
        artifactBytes = minOf(artifactBytes, hard.artifactBytes),
        diagnostics = minOf(diagnostics, hard.diagnostics),
        diagnosticTextBytes = minOf(diagnosticTextBytes, hard.diagnosticTextBytes),
        stderrBytes = minOf(stderrBytes, hard.stderrBytes),
        temporaryBytes = minOf(temporaryBytes, hard.temporaryBytes),
        temporaryFiles = minOf(temporaryFiles, hard.temporaryFiles),
    )

private fun bounded(
    value: String,
    maximumBytes: Int,
): String {
    val result = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val text = String(Character.toChars(codePoint))
        val encoded = text.encodeToByteArray().size
        if (bytes + encoded > maximumBytes) break
        result.append(text)
        bytes += encoded
        index += Character.charCount(codePoint)
    }
    return result.toString()
}
