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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectWorkerProtocolTest {
    @Test
    fun `multi-source request with trusted bundle identities round trips canonically`() {
        val request =
            CompileRequest(
                requestId = RequestId.of(9uL),
                sources = listOf(source("api/A.kt", "package api\nclass A"), source("main.kt", "val a = api.A()")),
                target = TargetSettings.KOTLIN_2_4_JVM_17,
                expectedIdentity = identity(),
                limits = WorkerLimits(),
                platformModules =
                    listOf(
                        TrustedBundleIdentity.of("core-api", Hash256.of(ByteArray(32) { 1 })),
                        TrustedBundleIdentity.of("redstone", Hash256.of(ByteArray(32) { 2 })),
                    ),
            )

        assertEquals(request, WorkerMessageCodec.decode(WorkerMessageCodec.encode(request)))
    }

    @Test
    fun `request rejects empty unordered duplicate invalid and over-limit source projects`() {
        val limits = WorkerLimits(sourceFiles = 1, sourceFileBytes = 1, sourceBytes = 1)
        assertFailsWith<IllegalArgumentException> { request(emptyList(), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { request(listOf(source("b.kt", "b"), source("a.kt", "a")), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { request(listOf(source("a.kt", "a"), source("a.kt", "a")), WorkerLimits()) }
        assertFailsWith<IllegalArgumentException> { request(listOf(source("a.kt", "aa")), limits) }
        assertFailsWith<IllegalArgumentException> {
            request(
                listOf(ProjectSource(VirtualSourcePath.kotlin("a.kt"), BinaryValue.of(byteArrayOf(0xc3.toByte(), 0x28)))),
                WorkerLimits(),
            )
        }
    }

    @Test
    fun `request preserves topological platform module order and rejects duplicate identities`() {
        val z = TrustedBundleIdentity.of("z", Hash256.zero())
        val a = TrustedBundleIdentity.of("a", Hash256.zero())
        assertEquals(listOf(z, a), request(listOf(source("main.kt", "x")), WorkerLimits(), listOf(z, a)).platformModules)
        assertFailsWith<IllegalArgumentException> { request(listOf(source("main.kt", "x")), WorkerLimits(), listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> { TrustedBundleIdentity.of("", Hash256.zero()) }
    }

    @Test
    fun `protocol v3 explicitly rejects a v1 frame`() {
        val encoded = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(request(listOf(source("main.kt", "x")), WorkerLimits())))
        val v1 =
            encoded.copyOf().also { bytes ->
                bytes[4] = 1
                bytes[5] = 0
            }

        val failure = assertFailsWith<WorkerProtocolException> { WorkerCodec.decodeFrame(v1, WorkerLimits().frameBytes) }

        assertEquals(WorkerProtocolError.WRONG_VERSION, failure.error)
    }

    private fun request(
        sources: List<ProjectSource>,
        limits: WorkerLimits,
        api: List<TrustedBundleIdentity> = emptyList(),
    ) = CompileRequest(RequestId.of(1uL), sources, TargetSettings.KOTLIN_2_4_JVM_17, identity(), limits, api)

    private fun source(
        path: String,
        text: String,
    ) = ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(text.encodeToByteArray()))

    private fun identity() = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())
}
