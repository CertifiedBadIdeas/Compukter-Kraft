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

package ru.lazyhat.compukters.ide.compiler

import ru.lazyhat.compukters.compiler.worker.controller.CompilationIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfile
import ru.lazyhat.compukters.ide.compiler.profile.ResolvedPlatformModule
import java.security.MessageDigest

object ClientCompilationIdentity {
    fun compute(
        request: CompileRequest,
        manifestBytes: BinaryValue,
        lockBytes: BinaryValue,
        profile: CompileProfile,
    ): Hash256 {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.field(CompilationIdentity.compute(request).toByteArray())
        digest.field(manifestBytes.toByteArray())
        digest.field(lockBytes.toByteArray())
        digest.field(profile.toolchain.artifactAbi.toLittleEndian())
        profile.modules.forEach { module -> digest.module(module) }
        return Hash256.of(digest.digest())
    }

    private fun MessageDigest.module(module: ResolvedPlatformModule) {
        field(byteArrayOf(if (module.direct) 1 else 0))
        field(
            module.identity.id.value
                .encodeToByteArray(),
        )
        field(
            module.identity.major.value
                .toUInt()
                .toLittleEndian(),
        )
        field(module.identity.version.encodeToByteArray())
        field(module.identity.contentHash.toByteArray())
    }

    private fun MessageDigest.field(bytes: ByteArray) {
        update(bytes.size.toUInt().toLittleEndian())
        update(bytes)
    }

    private fun UInt.toLittleEndian() = ByteArray(UInt.SIZE_BYTES) { index -> (this shr (index * 8)).toByte() }

    private val DOMAIN = "Compukters client compilation identity v2\u0000".encodeToByteArray()
}
