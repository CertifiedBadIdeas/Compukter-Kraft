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
import java.security.MessageDigest

data class AnalysisModuleIdentity(
    val name: String,
    val hash: Hash256,
) {
    init {
        require(name.isNotEmpty()) { "analysis module name must not be empty" }
        require(strictUtf8Size(name) <= MAX_IDENTITY_TEXT_UTF8_BYTES) { "analysis module name exceeds limit" }
    }
}

data class AnalysisSemanticSettings(
    val languageVersion: String,
    val apiVersion: String,
    val progressiveMode: Boolean,
) {
    init {
        validateIdentityText("language version", languageVersion)
        validateIdentityText("API version", apiVersion)
    }
}

data class AnalysisProfileIdentity(
    val hash: Hash256,
) {
    companion object {
        fun of(
            toolchain: ToolchainLockIdentity,
            canonicalLock: BinaryValue,
            modules: List<AnalysisModuleIdentity>,
            settings: AnalysisSemanticSettings,
        ): AnalysisProfileIdentity {
            require(modules.zipWithNext().all { (left, right) -> compareModuleIdentities(left, right) < 0 }) {
                "analysis module identities must be strictly sorted and unique"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN)
            digest.field(toolchain.compilerVersion)
            digest.field(toolchain.languageVersion)
            digest.uint(toolchain.codegenAbi)
            digest.uint(toolchain.artifactAbi)
            digest.uint(toolchain.artifactWriterVersion)
            digest.field(toolchain.payloadHash.toByteArray())
            digest.field(toolchain.platformAbi.toByteArray())
            digest.field(canonicalLock.toByteArray())
            digest.int(modules.size)
            modules.forEach { module ->
                digest.field(module.name)
                digest.field(module.hash.toByteArray())
            }
            digest.field(settings.languageVersion)
            digest.field(settings.apiVersion)
            digest.update(if (settings.progressiveMode) 1 else 0)
            return AnalysisProfileIdentity(Hash256.of(digest.digest()))
        }

        private val DOMAIN = "Compukters analysis profile v3\u0000".encodeToByteArray()
    }
}

private fun validateIdentityText(
    label: String,
    value: String,
) {
    require(value.isNotEmpty()) { "$label must not be empty" }
    require(strictUtf8Size(value) <= MAX_IDENTITY_TEXT_UTF8_BYTES) { "$label exceeds limit" }
}

private fun compareModuleIdentities(
    left: AnalysisModuleIdentity,
    right: AnalysisModuleIdentity,
): Int {
    val name = compareUnsigned(left.name.encodeToByteArray(), right.name.encodeToByteArray())
    return if (name != 0) name else compareUnsigned(left.hash.toByteArray(), right.hash.toByteArray())
}

private fun compareUnsigned(
    left: ByteArray,
    right: ByteArray,
): Int {
    repeat(minOf(left.size, right.size)) { index ->
        val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return left.size.compareTo(right.size)
}

private fun MessageDigest.field(value: String) = field(value.encodeToByteArray())

private fun MessageDigest.field(value: ByteArray) {
    int(value.size)
    update(value)
}

private fun MessageDigest.uint(value: UInt) = int(value.toInt())

private fun MessageDigest.int(value: Int) {
    repeat(Int.SIZE_BYTES) { shift -> update((value ushr (shift * 8)).toByte()) }
}

private const val MAX_IDENTITY_TEXT_UTF8_BYTES = 4 * 1024
