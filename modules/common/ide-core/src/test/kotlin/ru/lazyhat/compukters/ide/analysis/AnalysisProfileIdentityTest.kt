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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AnalysisProfileIdentityTest {
    @Test
    fun `identity covers exact lock modules toolchain and semantic settings`() {
        val base = identity()

        assertEquals(base, identity())
        assertNotEquals(base, identity(lock = byteArrayOf(2)))
        assertNotEquals(base, identity(toolchain = toolchain(payload = 9)))
        assertNotEquals(base, identity(settings = AnalysisSemanticSettings("2.4", "2.4", true)))
        assertNotEquals(
            base,
            identity(modules = listOf(AnalysisModuleIdentity("std.fs", hash(3)), AnalysisModuleIdentity("std.terminal", hash(1)))),
        )
    }

    @Test
    fun `module identity input must already be canonical unique and strict utf8`() {
        assertFailsWith<IllegalArgumentException> {
            identity(
                modules =
                    listOf(
                        AnalysisModuleIdentity("std.terminal", hash(1)),
                        AnalysisModuleIdentity("std.fs", hash(2)),
                    ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            identity(modules = List(2) { AnalysisModuleIdentity("std.fs", hash(1)) })
        }
        assertFailsWith<IllegalArgumentException> { AnalysisModuleIdentity("bad\uD800", hash(1)) }
        assertFailsWith<IllegalArgumentException> { AnalysisSemanticSettings("2.4", "bad\uDC00", false) }
    }

    private fun identity(
        lock: ByteArray = byteArrayOf(1),
        toolchain: ToolchainLockIdentity = toolchain(),
        modules: List<AnalysisModuleIdentity> =
            listOf(
                AnalysisModuleIdentity("std.fs", hash(2)),
                AnalysisModuleIdentity("std.terminal", hash(1)),
            ),
        settings: AnalysisSemanticSettings = AnalysisSemanticSettings("2.4", "2.4", false),
    ): AnalysisProfileIdentity =
        AnalysisProfileIdentity.of(
            toolchain = toolchain,
            canonicalLock = BinaryValue.of(lock),
            modules = modules,
            settings = settings,
        )

    private fun toolchain(payload: Int = 1) =
        ToolchainLockIdentity(
            compilerVersion = "2.4.10",
            languageVersion = "2.4",
            codegenAbi = 1u,
            artifactAbi = 1u,
            artifactWriterVersion = 1u,
            payloadHash = hash(payload),
            platformAbi = hash(2),
        )

    private fun hash(value: Int) = Hash256.of(ByteArray(32) { value.toByte() })
}
