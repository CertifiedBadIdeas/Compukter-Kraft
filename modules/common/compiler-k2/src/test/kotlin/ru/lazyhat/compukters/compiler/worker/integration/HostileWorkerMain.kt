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

import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerFeature
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.security.MessageDigest
import kotlin.system.exitProcess

fun main() {
    write(WorkerHandshake(hostileIdentity(), setOf(WorkerFeature.PROJECT_SNAPSHOT, WorkerFeature.KOTLIN_IR), WorkerLimits()))
    val request = WorkerMessageCodec.decode(WorkerCodec.decodeFrame(readFrame(), WorkerLimits().frameBytes)) as CompileRequest
    when (
        request.sources
            .single()
            .content
            .toByteArray()
            .decodeToString()
    ) {
        "SLEEP" -> {
            Thread.sleep(10_000)
        }

        "TRUNCATED" -> {
            System.out.write(byteArrayOf(0x43, 0x50, 0x4b))
        }

        "OVERSIZED" -> {
            val header =
                WorkerCodec
                    .encodeFrame(WorkerMessageCodec.encode(success(request.requestId.value)))
                    .copyOf(12)
            header[8] = -1
            header[9] = -1
            header[10] = -1
            header[11] = 127
            System.out.write(header)
        }

        "STDERR" -> {
            System.err.write(ByteArray(128 * 1024) { 'x'.code.toByte() })
            exitProcess(7)
        }

        "OOM" -> {
            val retained = mutableListOf<ByteArray>()
            while (true) retained += ByteArray(1024 * 1024)
        }

        "WRONG_ID" -> {
            write(success(request.requestId.value + 1uL))
        }

        "DUPLICATE" -> {
            val result = success(request.requestId.value)
            write(result)
            write(result)
        }

        else -> {
            write(success(request.requestId.value))
        }
    }
    System.out.flush()
}

private fun success(id: ULong): CompileSuccess {
    val artifact = BinaryValue.of(byteArrayOf(1, 2, 3))
    val hash = Hash256.of(MessageDigest.getInstance("SHA-256").digest(artifact.toByteArray()))
    return CompileSuccess(
        ru.lazyhat.compukters.compiler.worker.protocol.RequestId
            .of(id),
        artifact,
        hash,
        emptyList(),
        CompilationMetrics(1uL, 1uL, 1uL),
    )
}

private fun write(message: WorkerMessage) {
    System.out.write(WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message)))
    System.out.flush()
}

private fun readFrame(): ByteArray {
    val header = System.`in`.readNBytes(12)
    val size = (0 until 4).fold(0) { value, index -> value or ((header[8 + index].toInt() and 0xff) shl (index * 8)) }
    return header + System.`in`.readNBytes(size)
}

internal fun hostileManifest() =
    WorkerPayloadManifest.create(
        WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero()),
        "ru.lazyhat.compukters.compiler.worker.integration.HostileWorkerMainKt",
        emptyMap(),
    )

internal fun hostileIdentity() = hostileManifest().identity
