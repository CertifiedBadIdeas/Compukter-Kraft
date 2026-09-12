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

package ru.lazyhat.compukters.ide.analysis.k2.integration

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisResultLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.analysis.protocol.ANALYSIS_PROTOCOL_VERSION
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFeature
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrame
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisHandshake
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessage
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageType
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQuerySuccess
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReady
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val root = Path.of(System.getProperty("java.io.tmpdir"))
    val mode = root.fileName.toString()
    when (mode) {
        "startup-hang" -> Thread.sleep(Long.MAX_VALUE)
        else -> runProtocolMode(root, mode)
    }
}

private fun runProtocolMode(
    root: Path,
    mode: String,
) {
    val identity = identity(root)
    val limits = AnalysisLimits()
    if (mode == "wrong-handshake") {
        write(
            AnalysisHandshake(
                ANALYSIS_PROTOCOL_VERSION,
                identity.copy(payloadHash = Hash256.zero()),
                AnalysisFeature.entries.toSet(),
                limits,
            ),
        )
        Thread.sleep(Long.MAX_VALUE)
    }
    write(AnalysisHandshake(ANALYSIS_PROTOCOL_VERSION, identity, AnalysisFeature.entries.toSet(), limits))
    val open = read() as OpenSnapshotRequest
    when (mode) {
        "wrong-open-request" -> {
            write(SnapshotReady(next(open.requestId), open.identity))
            Thread.sleep(Long.MAX_VALUE)
        }

        "wrong-open-profile" -> {
            write(SnapshotReady(open.requestId, open.identity.copy(profile = AnalysisProfileIdentity(Hash256.zero()))))
            Thread.sleep(Long.MAX_VALUE)
        }
    }
    write(SnapshotReady(open.requestId, open.identity))
    val query = read() as AnalysisQueryRequest
    when (mode) {
        "query-hang" -> {
            Thread.sleep(Long.MAX_VALUE)
        }

        "exit" -> {
            System.err.print("hostile worker exit")
        }

        "oom" -> {
            System.err.print("💥".repeat(8_000) + "\nOutOfMemoryError: Java heap space")
        }

        "malformed" -> {
            raw(byteArrayOf(1, 2, 3))
        }

        "truncated" -> {
            raw(AnalysisFrameCodec.encode(AnalysisFrame(AnalysisMessageType.QuerySuccess, ByteArray(0))).copyOf(6))
        }

        "oversized" -> {
            val header = AnalysisFrameCodec.encode(AnalysisFrame(AnalysisMessageType.QuerySuccess, ByteArray(0)))
            writeU32(header, 8, limits.frameBytes + 1)
            raw(header)
        }

        "wrong-query-request" -> {
            write(success(next(query.requestId), query.query.identity))
        }

        "wrong-query-snapshot" -> {
            val wrong = query.query.identity.copy(source = SourceSnapshotId(Hash256.zero()))
            write(success(query.requestId, wrong))
        }

        "wrong-query-profile" -> {
            val wrong = query.query.identity.copy(profile = AnalysisProfileIdentity(Hash256.zero()))
            write(success(query.requestId, wrong))
        }

        "excessive-result" -> {
            val frame = AnalysisMessageCodec.encode(success(query.requestId, query.query.identity), AnalysisProtocolContext.unbound())
            val payload = frame.payload
            writeU32(payload, COMPLETION_COUNT_OFFSET, limits.completionItems + 1)
            raw(AnalysisFrameCodec.encode(AnalysisFrame(frame.type, payload)))
        }

        else -> {
            error("unknown hostile analysis worker mode: $mode")
        }
    }
}

private fun success(
    requestId: RequestId,
    identity: AnalysisSnapshotIdentity,
): AnalysisQuerySuccess =
    AnalysisQuerySuccess(
        requestId,
        AnalysisResult.Completion.create(
            identity,
            EditorRange(0, 0),
            emptyList(),
            0,
            AnalysisResultLimits(),
        ),
    )

private fun identity(root: Path): AnalysisWorkerIdentity {
    val lines = Files.readAllLines(root.resolve("identity.txt"))
    return AnalysisWorkerIdentity(lines[0], lines[1], Hash256.fromHex(lines[2]), Hash256.fromHex(lines[3]))
}

private fun next(requestId: RequestId) = RequestId.of(requestId.value + 1uL)

private fun write(message: AnalysisMessage) {
    raw(AnalysisFrameCodec.encode(AnalysisMessageCodec.encode(message, AnalysisProtocolContext.unbound())))
}

private fun read(): AnalysisMessage {
    val header = System.`in`.readNBytes(FRAME_HEADER_BYTES)
    require(header.size == FRAME_HEADER_BYTES)
    val size = (0 until 4).fold(0) { value, index -> value or ((header[8 + index].toInt() and 0xff) shl (index * 8)) }
    val frame = header + System.`in`.readNBytes(size)
    return AnalysisMessageCodec.decode(AnalysisFrameCodec.decode(frame, AnalysisLimits().frameBytes), AnalysisProtocolContext.unbound())
}

private fun raw(bytes: ByteArray) {
    System.out.write(bytes)
    System.out.flush()
}

private fun writeU32(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
}

private const val FRAME_HEADER_BYTES = 12
private const val COMPLETION_COUNT_OFFSET = 82
