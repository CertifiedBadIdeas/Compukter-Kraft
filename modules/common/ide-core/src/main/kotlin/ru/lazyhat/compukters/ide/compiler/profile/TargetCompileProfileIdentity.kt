/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.security.MessageDigest

const val COMPUKTER_ARTIFACT_ABI: UInt = 2u

data class TargetCompileProfileIdentity(
    val hash: Hash256,
) {
    companion object {
        fun of(profile: TargetCompileProfile): TargetCompileProfileIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN)
            val toolchain = profile.toolchain
            digest.field(toolchain.compilerVersion)
            digest.field(toolchain.languageVersion)
            digest.uint(toolchain.codegenAbi)
            digest.uint(toolchain.artifactAbi)
            digest.uint(toolchain.artifactWriterVersion)
            digest.field(toolchain.payloadHash.toByteArray())
            digest.field(toolchain.platformAbi.toByteArray())
            digest.int(profile.modules.size)
            profile.modules.forEach { module ->
                digest.field(module.id.provider)
                digest.field(module.id.module)
                digest.int(module.major.value)
                digest.field(module.version)
                digest.field(module.contentHash.toByteArray())
            }
            val limits = profile.limits
            digest.int(limits.sourceFiles)
            digest.int(limits.sourceFileBytes)
            digest.int(limits.sourceBytes)
            digest.int(limits.frameBytes)
            digest.int(limits.artifactBytes)
            digest.int(limits.diagnostics)
            digest.int(limits.diagnosticTextBytes)
            digest.int(limits.stderrBytes)
            digest.long(limits.temporaryBytes)
            digest.int(limits.temporaryFiles)
            return TargetCompileProfileIdentity(Hash256.of(digest.digest()))
        }

        private val DOMAIN = "Compukters target compile profile v2\u0000".encodeToByteArray()
    }
}

private fun MessageDigest.field(value: String) = field(value.encodeToByteArray())

private fun MessageDigest.field(value: ByteArray) {
    int(value.size)
    update(value)
}

private fun MessageDigest.uint(value: UInt) = int(value.toInt())

private fun MessageDigest.int(value: Int) {
    repeat(Int.SIZE_BYTES) { index -> update((value ushr (index * Byte.SIZE_BITS)).toByte()) }
}

private fun MessageDigest.long(value: Long) {
    repeat(Long.SIZE_BYTES) { index -> update((value ushr (index * Byte.SIZE_BITS)).toByte()) }
}
