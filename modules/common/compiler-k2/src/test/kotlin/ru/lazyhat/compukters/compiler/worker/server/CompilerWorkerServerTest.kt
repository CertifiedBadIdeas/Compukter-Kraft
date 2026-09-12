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

import org.jetbrains.kotlin.cli.common.ExitCode
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.controller.TemporaryBudgetException
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilationResult
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticSeverity
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompilerWorkerServerTest {
    @Test
    fun `handshake is first and one request yields one matching success`() {
        val request = request(7uL, "val answer: Int = 42")
        val artifact = BinaryValue.of(byteArrayOf(1, 2, 3, 4))
        val messages =
            serve(listOf(request)) {
                K2CompilationResult(ExitCode.OK, emptyList(), true, artifact, false)
            }

        val handshake = assertIs<WorkerHandshake>(messages[0])
        assertEquals(identity(), handshake.identity)
        assertEquals(HARD_LIMITS, handshake.limits)
        val success = assertIs<CompileSuccess>(messages[1])
        assertEquals(request.requestId, success.requestId)
        assertEquals(artifact, success.artifact)
        assertContentEquals(sha256(artifact.toByteArray()).toByteArray(), success.artifactHash.toByteArray())
        assertEquals(2, messages.size)
    }

    @Test
    fun `compiler diagnostics become one compiler failure`() {
        val request = request(9uL, "val answer = )")
        val diagnostic =
            WorkerDiagnostic(
                DiagnosticSeverity.ERROR,
                DiagnosticCategory.SYNTAX,
                null,
                "expecting an expression",
                request.sources.single().path,
                13u,
                14u,
            )
        val messages =
            serve(listOf(request)) {
                K2CompilationResult(ExitCode.COMPILATION_ERROR, listOf(diagnostic), false, null, true)
            }

        val failure = assertIs<CompilerFailure>(messages[1])
        assertEquals(request.requestId, failure.requestId)
        assertEquals(listOf(diagnostic), failure.diagnostics)
        assertEquals(2, messages.size)
    }

    @Test
    fun `ordinary compiler exception becomes one internal platform failure`() {
        val request = request(11uL, "val answer: Int = 42")
        val messages = serve(listOf(request)) { error("private compiler path exploded") }

        val failure = assertIs<PlatformFailure>(messages[1])
        assertEquals(request.requestId, failure.requestId)
        assertEquals(PlatformFailureClass.INTERNAL_COMPILER, failure.failureClass)
        assertTrue(failure.detail.isNotBlank())
        assertEquals(2, messages.size)
    }

    @Test
    fun `temporary storage exhaustion becomes an output limit failure`() {
        val request = request(12uL, "val answer: Int = 42")
        val messages = serve(listOf(request)) { throw TemporaryBudgetException("request bytes exceed limit") }

        val failure = assertIs<PlatformFailure>(messages[1])
        assertEquals(request.requestId, failure.requestId)
        assertEquals(PlatformFailureClass.OUTPUT_LIMIT, failure.failureClass)
        assertEquals("request bytes exceed limit", failure.detail)
    }

    @Test
    fun `malformed frame stops after earlier terminal result and idle EOF is clean`() {
        val output = ByteArrayOutputStream()
        val valid = encode(request(13uL, "val answer: Int = 42"))
        val malformed = byteArrayOf(0x43, 0x50, 0x4b)
        CompilerWorkerServer(
            identity(),
            HARD_LIMITS,
            ByteArrayInputStream(valid + malformed),
            output,
            compiler = { K2CompilationResult(ExitCode.OK, emptyList(), true, BinaryValue.of(byteArrayOf(7)), false) },
        ).run()

        val messages = decodeAll(output.toByteArray())
        assertIs<WorkerHandshake>(messages[0])
        assertIs<CompileSuccess>(messages[1])
        assertEquals(2, messages.size)

        val idle = ByteArrayOutputStream()
        CompilerWorkerServer(
            identity(),
            HARD_LIMITS,
            ByteArrayInputStream(ByteArray(0)),
            idle,
            compiler = { error("must not compile") },
        ).run()
        assertEquals(1, decodeAll(idle.toByteArray()).size)
    }

    private fun serve(
        requests: List<CompileRequest>,
        compiler: CompilationHandler,
    ): List<WorkerMessage> {
        val output = ByteArrayOutputStream()
        CompilerWorkerServer(
            identity(),
            HARD_LIMITS,
            ByteArrayInputStream(requests.fold(ByteArray(0)) { bytes, request -> bytes + encode(request) }),
            output,
            compiler,
        ).run()
        return decodeAll(output.toByteArray())
    }

    private fun request(
        id: ULong,
        source: String,
    ) = CompileRequest(
        RequestId.of(id),
        listOf(ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(source.encodeToByteArray()))),
        TargetSettings.KOTLIN_2_4_JVM_17,
        identity(),
        HARD_LIMITS,
    )

    private fun encode(message: WorkerMessage): ByteArray = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))

    private fun decodeAll(bytes: ByteArray): List<WorkerMessage> {
        val messages = mutableListOf<WorkerMessage>()
        var offset = 0
        while (offset < bytes.size) {
            val payload =
                (0 until 4).fold(0) { value, index ->
                    value or ((bytes[offset + 8 + index].toInt() and 0xff) shl (index * 8))
                }
            val end = offset + 12 + payload
            messages += WorkerMessageCodec.decode(WorkerCodec.decodeFrame(bytes.copyOfRange(offset, end), HARD_LIMITS.frameBytes))
            offset = end
        }
        return messages
    }

    private fun identity() = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())

    private fun sha256(bytes: ByteArray): Hash256 {
        val algorithm = java.security.MessageDigest.getInstance("SHA-256")
        val digest = algorithm.digest(bytes)
        return Hash256.of(digest)
    }

    private companion object {
        val HARD_LIMITS = WorkerLimits()
    }
}
