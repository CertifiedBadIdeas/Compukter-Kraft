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

package ru.lazyhat.compukters.ide.project

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectLockCodecTest {
    @Test
    fun `lock has one canonical representation`() {
        val lock =
            ProjectLock.of(
                toolchain(),
                listOf(
                    module("create:kinetics", 1, "1.4.0", 1),
                    module("std:terminal", 2, "2.3.1", 2),
                ),
            )

        val encoded = ProjectLockCodec.encode(lock)

        assertEquals(
            """
            format = 2

            [toolchain]
            compiler = "2.4.10"
            language = "2.4"
            codegen_abi = 1
            artifact_abi = 2
            artifact_writer = 3
            payload_sha256 = "0101010101010101010101010101010101010101010101010101010101010101"
            platform_abi_sha256 = "0202020202020202020202020202020202020202020202020202020202020202"

            [[modules]]
            id = "create:kinetics"
            major = 1
            version = "1.4.0"
            content_sha256 = "0101010101010101010101010101010101010101010101010101010101010101"
            direct = false

            [[modules]]
            id = "std:terminal"
            major = 2
            version = "2.3.1"
            content_sha256 = "0202020202020202020202020202020202020202020202020202020202020202"
            direct = true
            """.trimIndent() + "\n",
            encoded,
        )
        assertEquals(lock, ProjectLockCodec.decode(encoded))
    }

    @Test
    fun `lock supports an empty module set`() {
        val lock = ProjectLock.of(toolchain(), emptyList())

        assertEquals(lock, ProjectLockCodec.decode(ProjectLockCodec.encode(lock)))
        assertEquals(emptyList(), lock.modules)
    }

    @Test
    fun `lock rejects malformed unknown missing and duplicate data`() {
        invalid("format = 2")
        invalid(validLock().replace("format = 2", "format = 1"))
        invalid(validLock().replace("platform_abi_sha256", "standard_library_abi_sha256"))
        invalid(validLock() + "unknown = true\n")
        invalid(validLock().replace("compiler = \"2.4.10\"\n", ""))
        invalid(validLock().replace("artifact_abi = 2", "artifact_abi = \"2\""))
        invalid(validLock().replace("payload_sha256 = \"${hash(1).hex()}\"", "payload_sha256 = \"BAD\""))
        invalid(validLock().replace("id = \"std:terminal\"", "id = \"bad\""))
        invalid(validLock().replace("major = 2", "major = 0"))
        invalid(validLock().replace("version = \"2.3.1\"", "version = \"bad\\nversion\""))
        invalid(validLock() + "\n[[modules]]" + validLock().substringAfter("[[modules]]"))
    }

    @Test
    fun `lock enforces byte and module limits`() {
        assertFailsWith<ProjectLockException> {
            ProjectLockCodec.decode(validLock(), ProjectLimits(lockBytes = 8))
        }
        assertFailsWith<ProjectLockException> {
            ProjectLockCodec.decode(validLock(), ProjectLimits(modules = 0))
        }
    }

    private fun validLock(): String = ProjectLockCodec.encode(ProjectLock.of(toolchain(), listOf(module("std:terminal", 2, "2.3.1", 2))))

    private fun invalid(source: String) {
        assertFailsWith<ProjectLockException>(source) { ProjectLockCodec.decode(source) }
    }

    private fun toolchain() =
        ToolchainLockIdentity(
            compilerVersion = "2.4.10",
            languageVersion = "2.4",
            codegenAbi = 1u,
            artifactAbi = 2u,
            artifactWriterVersion = 3u,
            payloadHash = hash(1),
            platformAbi = hash(2),
        )

    private fun module(
        id: String,
        major: Int,
        version: String,
        hashByte: Int,
    ) = LockedModule(
        identity = ResolvedModule(ModuleId.parse(id), ApiMajor(major), version, hash(hashByte)),
        direct = id == "std:terminal",
    )

    private fun hash(byte: Int) = Hash256.of(ByteArray(32) { byte.toByte() })
}
