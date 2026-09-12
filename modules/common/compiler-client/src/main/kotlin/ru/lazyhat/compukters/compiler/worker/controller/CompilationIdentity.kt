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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object CompilationIdentity {
    fun compute(request: CompileRequest): Hash256 {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("Compukter compilation identity v4\u0000".encodeToByteArray())
        val identity = request.expectedIdentity
        request.sources.forEach { source ->
            digest.field(1, entry(source.path.value.encodeToByteArray(), source.content.toByteArray()))
        }
        digest.field(2, request.target.name.encodeToByteArray())
        digest.field(3, identity.compilerVersion.encodeToByteArray())
        digest.field(4, identity.languageVersion.encodeToByteArray())
        digest.field(5, identity.codegenAbi.littleEndian())
        digest.field(6, identity.payloadHash.toByteArray())
        digest.field(7, identity.platformAbi.toByteArray())
        digest.field(8, identity.artifactWriterVersion.littleEndian())
        request.platformModules.forEach { bundle -> digest.field(9, entry(bundle.name.encodeToByteArray(), bundle.hash.toByteArray())) }
        digest.field(
            11,
            request.limits.sourceFiles
                .toUInt()
                .littleEndian(),
        )
        digest.field(
            12,
            request.limits.sourceFileBytes
                .toUInt()
                .littleEndian(),
        )
        digest.field(
            13,
            request.limits.sourceBytes
                .toUInt()
                .littleEndian(),
        )
        // Every request limit participates: each one can change admission, bounded
        // diagnostics/failure detail, temporary execution, or accepted output.
        digest.field(
            14,
            request.limits.frameBytes
                .toUInt()
                .littleEndian(),
        )
        digest.field(
            15,
            request.limits.artifactBytes
                .toUInt()
                .littleEndian(),
        )
        digest.field(
            16,
            request.limits.diagnostics
                .toUInt()
                .littleEndian(),
        )
        digest.field(
            17,
            request.limits.diagnosticTextBytes
                .toUInt()
                .littleEndian(),
        )
        digest.field(
            18,
            request.limits.stderrBytes
                .toUInt()
                .littleEndian(),
        )
        digest.field(
            19,
            request.limits.temporaryBytes
                .toULong()
                .littleEndian(),
        )
        digest.field(
            20,
            request.limits.temporaryFiles
                .toUInt()
                .littleEndian(),
        )
        return Hash256.of(digest.digest())
    }

    private fun entry(vararg fields: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        fields.forEach { bytes ->
            output.write(bytes.size.toUInt().littleEndian())
            output.write(bytes)
        }
        return output.toByteArray()
    }
}

class CompilationCacheArtifact private constructor(
    val artifact: BinaryValue,
    val artifactHash: Hash256,
) {
    companion object {
        fun admit(result: CompileResult): CompilationCacheArtifact? {
            val success = result as? CompileSuccess ?: return null
            val actual = Hash256.of(MessageDigest.getInstance("SHA-256").digest(success.artifact.toByteArray()))
            if (actual != success.artifactHash) return null
            return CompilationCacheArtifact(BinaryValue.of(success.artifact.toByteArray()), actual)
        }
    }
}

private fun MessageDigest.field(
    tag: Int,
    bytes: ByteArray,
) {
    update(tag.toByte())
    update(bytes.size.toUInt().littleEndian())
    update(bytes)
}

private fun UInt.littleEndian(): ByteArray = ByteArray(4) { index -> (this shr (index * 8)).toByte() }

private fun ULong.littleEndian(): ByteArray = ByteArray(8) { index -> (this shr (index * 8)).toByte() }
