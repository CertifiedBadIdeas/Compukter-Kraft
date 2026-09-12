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

package ru.lazyhat.compukters.compiler.worker.protocol

import ru.lazyhat.compukters.compiler.project.ProjectSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerCodecTest {
    @Test
    fun `SHA-256 hashes have one strict lowercase text form`() {
        val encoded = (0 until 32).joinToString("") { byte -> "%02x".format(byte) }

        assertEquals(encoded, Hash256.fromHex(encoded).hex())
        assertFailsWith<IllegalArgumentException> { Hash256.fromHex(encoded.uppercase()) }
        assertFailsWith<IllegalArgumentException> { Hash256.fromHex(encoded.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { Hash256.fromHex("g" + encoded.drop(1)) }
    }

    @Test
    fun `compile request frame has the canonical envelope`() {
        val encoded = WorkerCodec.encodeFrame(WorkerFrame(WorkerMessageType.COMPILE_REQUEST, byteArrayOf(1, 2, 3, 4, 5)))

        assertContentEquals(
            byteArrayOf(
                0x43,
                0x50,
                0x4b,
                0x57,
                0x03,
                0x00,
                0x02,
                0x00,
                0x05,
                0x00,
                0x00,
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
            ),
            encoded,
        )
        assertEquals(WorkerFrame(WorkerMessageType.COMPILE_REQUEST, byteArrayOf(1, 2, 3, 4, 5)), WorkerCodec.decodeFrame(encoded, 5))
    }

    @Test
    fun `decoder rejects malformed or oversized frames before payload use`() {
        val valid = WorkerCodec.encodeFrame(WorkerFrame(WorkerMessageType.HANDSHAKE, byteArrayOf(1)))

        assertEquals(WorkerProtocolError.TRUNCATED_FRAME, failure(valid.copyOf(11)).error)
        assertEquals(WorkerProtocolError.FRAME_TOO_LARGE, failure(valid, maximumPayloadBytes = 0).error)
        assertEquals(WorkerProtocolError.TRAILING_BYTES, failure(valid + byteArrayOf(0)).error)
        assertEquals(WorkerProtocolError.BAD_MAGIC, failure(valid.copyOf().also { it[0] = 0 }).error)
    }

    @Test
    fun `virtual source paths cannot address the host filesystem`() {
        assertEquals("project/main.kts", VirtualSourcePath.of("project/main.kts").value)

        listOf("/main.kts", "../main.kts", "project/../main.kts", "project\\main.kts", "project/\u0000main.kts").forEach {
            assertFailsWith<IllegalArgumentException> { VirtualSourcePath.of(it) }
        }
    }

    @Test
    fun `all worker messages have deterministic round trips`() {
        val identity =
            WorkerIdentity(
                compilerVersion = "2.4.10",
                languageVersion = "2.4",
                codegenAbi = UInt.MAX_VALUE,
                artifactWriterVersion = 0x8000_0000u,
                payloadHash = Hash256.zero(),
                platformAbi = Hash256.of(ByteArray(32) { 1 }),
            )
        val limits = WorkerLimits(sourceBytes = 1024, artifactBytes = 2048, diagnostics = 8)
        val diagnostic =
            WorkerDiagnostic(
                severity = DiagnosticSeverity.ERROR,
                category = DiagnosticCategory.TYPE,
                code = "UNRESOLVED_REFERENCE",
                message = "unresolved reference: missing",
                path = VirtualSourcePath.of("project/main.kts"),
                startUtf16 = 4u,
                endUtf16 = 11u,
            )
        val metrics = CompilationMetrics(wallNanos = 123uL, heapBytes = 456uL, metaspaceBytes = 789uL)
        val requestId = RequestId.of(7uL)

        val messages =
            listOf<WorkerMessage>(
                WorkerHandshake(identity, setOf(WorkerFeature.PROJECT_SNAPSHOT, WorkerFeature.KOTLIN_IR), limits),
                CompileRequest(
                    requestId = requestId,
                    sources =
                        listOf(
                            ProjectSource(
                                VirtualSourcePath.kotlin("project/main.kt"),
                                BinaryValue.of("val answer: Int = 42".encodeToByteArray()),
                            ),
                        ),
                    target = TargetSettings.KOTLIN_2_4_JVM_17,
                    expectedIdentity = identity,
                    limits = limits,
                ),
                CompileSuccess(
                    requestId,
                    BinaryValue.of(byteArrayOf(1, 2, 3)),
                    Hash256.of(ByteArray(32) { 2 }),
                    warnings = emptyList(),
                    metrics = metrics,
                ),
                CompilerFailure(requestId, listOf(diagnostic), metrics),
                PlatformFailure(requestId, PlatformFailureClass.TIMEOUT, "deadline exceeded"),
            )

        messages.forEach { message ->
            val frame = WorkerMessageCodec.encode(message)
            assertEquals(message, WorkerMessageCodec.decode(WorkerCodec.decodeFrame(WorkerCodec.encodeFrame(frame), 4096)))
        }
    }

    @Test
    fun `request identities and source text are checked before encoding`() {
        assertFailsWith<IllegalArgumentException> { RequestId.of(0uL) }
        assertFailsWith<IllegalArgumentException> {
            CompileRequest(
                requestId = RequestId.of(1uL),
                sources =
                    listOf(
                        ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(byteArrayOf(0xc3.toByte(), 0x28))),
                    ),
                target = TargetSettings.KOTLIN_2_4_JVM_17,
                expectedIdentity = testIdentity(),
                limits = WorkerLimits(),
            )
        }
    }

    @Test
    fun `message decoder reports invalid UTF-8 and unknown enums`() {
        val request =
            CompileRequest(
                RequestId.of(1uL),
                listOf(ProjectSource(VirtualSourcePath.kotlin("a.kt"), BinaryValue.of("ok".encodeToByteArray()))),
                TargetSettings.KOTLIN_2_4_JVM_17,
                testIdentity(),
                WorkerLimits(),
            )
        val payload = WorkerMessageCodec.encode(request).payload
        val invalidUtf8 =
            payload.copyOf().also {
                it[24] = 0xc3.toByte()
                it[25] = 0x28
            }
        val unknownTarget =
            payload.copyOf().also {
                it[26] = 0xff.toByte()
                it[27] = 0xff.toByte()
            }

        assertEquals(
            WorkerProtocolError.INVALID_UTF8,
            assertFailsWith<WorkerProtocolException> {
                WorkerMessageCodec.decode(WorkerFrame(WorkerMessageType.COMPILE_REQUEST, invalidUtf8))
            }.error,
        )
        assertEquals(
            WorkerProtocolError.UNKNOWN_ENUM_VALUE,
            assertFailsWith<WorkerProtocolException> {
                WorkerMessageCodec.decode(WorkerFrame(WorkerMessageType.COMPILE_REQUEST, unknownTarget))
            }.error,
        )
    }

    @Test
    fun `message encoder rejects invalid text and excessive diagnostic counts`() {
        assertFailsWith<IllegalArgumentException> {
            WorkerMessageCodec.encode(
                PlatformFailure(RequestId.of(1uL), PlatformFailureClass.PROTOCOL, "\ud800"),
            )
        }

        val payload = ByteArray(12)
        payload[0] = 1
        payload[8] = 0x01
        payload[9] = 0x10
        assertEquals(
            WorkerProtocolError.COUNT_LIMIT,
            assertFailsWith<WorkerProtocolException> {
                WorkerMessageCodec.decode(WorkerFrame(WorkerMessageType.COMPILER_FAILURE, payload))
            }.error,
        )
    }

    private fun testIdentity(): WorkerIdentity = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())

    private fun failure(
        bytes: ByteArray,
        maximumPayloadBytes: Int = 16,
    ): WorkerProtocolException =
        assertFailsWith {
            WorkerCodec.decodeFrame(bytes, maximumPayloadBytes)
        }
}
