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

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import java.security.MessageDigest

object ArtifactWriter {
    fun moduleSemanticHash(
        module: Module,
        limits: ArtifactWriteLimits = ArtifactWriteLimits(),
    ): ByteArray = encodeModuleSections(ReferenceLiveness.derive(module), limits).semanticHash.copyOf()

    fun write(
        artifact: Artifact,
        limits: ArtifactWriteLimits = ArtifactWriteLimits(),
    ): ArtifactWriteResult {
        return try {
            val prepared = ReferenceLiveness.derive(artifact)
            val errors = validateArtifact(prepared, limits)
            if (errors.isNotEmpty()) return ArtifactWriteResult.Failure(errors)
            encodeArtifact(prepared, limits)
        } catch (failure: ArtifactEncodingException) {
            ArtifactWriteResult.Failure(listOf(ArtifactWriteError(failure.code, detail = requireNotNull(failure.message))))
        } catch (failure: IllegalArgumentException) {
            ArtifactWriteResult.Failure(
                listOf(ArtifactWriteError(ArtifactWriteErrorCode.INVALID_RANGE, detail = failure.message ?: "invalid artifact model")),
            )
        } catch (failure: ArithmeticException) {
            ArtifactWriteResult.Failure(
                listOf(ArtifactWriteError(ArtifactWriteErrorCode.OVERFLOW, detail = failure.message ?: "artifact arithmetic overflow")),
            )
        }
    }
}

private const val MANIFEST = 0x0001
private const val MODULES = 0x0002
private const val CAPABILITIES = 0x0003
private const val HEADER_SIZE = 64
private const val DIRECTORY_ENTRY_SIZE = 32
private const val DIGEST_SIZE = 32
private const val CORE_FLAGS = 3

private data class PhysicalSection(
    val kind: Int,
    val flags: Int,
    val scope: UInt,
    val payload: ByteArray,
    val count: UInt,
)

private data class SectionEntry(
    val section: PhysicalSection,
    val offset: Int,
)

private fun encodeArtifact(
    artifact: Artifact,
    limits: ArtifactWriteLimits,
): ArtifactWriteResult.Success {
    val encodedModules = artifact.modules.map { encodeModuleSections(it, limits) }
    val sections = mutableListOf<PhysicalSection>()
    sections += PhysicalSection(MANIFEST, CORE_FLAGS, 0u, encodeManifest(artifact, limits), 1u)
    sections += PhysicalSection(MODULES, CORE_FLAGS, 0u, encodeModules(artifact, encodedModules, limits), artifact.modules.size.toUInt())
    sections +=
        PhysicalSection(
            CAPABILITIES,
            CORE_FLAGS,
            0u,
            encodeCapabilities(artifact.capabilities, limits),
            artifact.capabilities.size.toUInt(),
        )
    encodedModules.forEachIndexed { moduleIndex, module ->
        val scope = moduleIndex.toUInt() + 1u
        module.semantic.forEach { sections += PhysicalSection(it.kind, CORE_FLAGS, scope, it.payload, it.count) }
        module.debug?.let { sections += PhysicalSection(it.kind, 0, scope, it.payload, it.count) }
    }
    sections.sortWith(compareBy(PhysicalSection::scope, PhysicalSection::kind))
    if (sections.size > limits.sections) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "section count exceeds ${limits.sections}")
    }

    val directoryEnd = Math.addExact(HEADER_SIZE, Math.multiplyExact(sections.size, DIRECTORY_ENTRY_SIZE))
    val firstPayload = checkedAlign8(directoryEnd)
    var cursor = firstPayload
    val entries =
        sections.map { section ->
            val entry = SectionEntry(section, cursor)
            cursor = checkedAlign8(Math.addExact(cursor, section.payload.size))
            entry
        }
    val last = entries.last()
    val payloadEnd = Math.addExact(last.offset, last.section.payload.size)
    val fileLength = Math.addExact(payloadEnd, DIGEST_SIZE)
    if (fileLength > limits.artifactBytes) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "artifact exceeds ${limits.artifactBytes} bytes")
    }

    val sink = BinarySink(limits.artifactBytes)
    sink.writeBytes(byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte()))
    sink.writeU16(3u)
    sink.writeU16(0u)
    sink.writeU16(artifact.minimumRuntimeAbi.major.toUInt())
    sink.writeU16(artifact.minimumRuntimeAbi.minor.toUInt())
    sink.writeU16(HEADER_SIZE.toUInt())
    sink.writeU16(DIRECTORY_ENTRY_SIZE.toUInt())
    sink.writeU32(sections.size.toUInt())
    sink.writeU32(featureMask(artifact.semanticFeatures))
    sink.writeU64(HEADER_SIZE.toULong())
    sink.writeU64(payloadEnd.toULong())
    sink.writeU32(artifact.entry.module.value)
    sink.writeU32(artifact.entry.function.value)
    sink.writeU8(artifact.entry.arguments.artifactTag)
    sink.writeBytes(ByteArray(15))
    entries.forEach { entry ->
        sink.writeU16(entry.section.kind.toUInt())
        sink.writeU16(entry.section.flags.toUInt())
        sink.writeU32(entry.section.scope)
        sink.writeU64(entry.offset.toULong())
        sink.writeU64(
            entry.section.payload.size
                .toULong(),
        )
        sink.writeU32(entry.section.count)
        sink.writeU32(0u)
    }
    sink.align8()
    entries.forEach { entry ->
        while (sink.size < entry.offset) sink.writeU8(0u)
        check(sink.size == entry.offset) { "section offset calculation diverged" }
        sink.writeBytes(entry.section.payload)
    }
    check(sink.size == payloadEnd) { "payload end calculation diverged" }
    val digest = MessageDigest.getInstance("SHA-256").digest(sink.toByteArray())
    sink.writeBytes(digest)
    check(sink.size == fileLength) { "artifact length calculation diverged" }
    return ArtifactWriteResult.Success(sink.toByteArray(), digest)
}

private fun encodeManifest(
    artifact: Artifact,
    limits: ArtifactWriteLimits,
): ByteArray {
    val requiredCapabilities = artifact.capabilities.count(Capability::required).toUInt()
    val optionalCapabilities = artifact.capabilities.size.toUInt() - requiredCapabilities
    return BinarySink(limits.artifactBytes)
        .apply {
            val manifest = artifact.manifest
            writeU32(manifest.requiredHeapBytes)
            writeU32(manifest.requiredStackBytes)
            writeU32(manifest.maximumCoroutines)
            writeU32(manifest.maximumCallDepth)
            writeU32(manifest.maximumHostRequests)
            writeU32(manifest.maximumEvents)
            writeU32(manifest.maximumBlockCost)
            writeU32(manifest.minimumSliceCost)
            writeU32(requiredCapabilities)
            writeU32(optionalCapabilities)
            writeBytes(manifest.compilerAbi)
            writeBytes(manifest.platformAbi)
            writeU32(manifest.maximumChannels)
            writeU32(manifest.maximumChannelValues)
        }.toByteArray()
}

private fun encodeModules(
    artifact: Artifact,
    encoded: List<EncodedModuleSections>,
    limits: ArtifactWriteLimits,
): ByteArray =
    encodeIndexed(
        artifact.modules.zip(encoded).map { (module, sections) ->
            BinarySink(limits.artifactBytes)
                .apply {
                    writeU32(module.name.value)
                    writeU32(if (module.kind == ModuleKind.APPLICATION) 1u else 2u)
                    writeBytes(sections.semanticHash)
                    writeU32(module.imports.size.toUInt())
                    writeU32(module.exports.size.toUInt())
                    writeU32(module.types.size.toUInt())
                    writeU32(module.functions.size.toUInt())
                    writeU32(0u)
                }.toByteArray()
        },
        limits.artifactBytes,
    )

private fun encodeCapabilities(
    capabilities: List<Capability>,
    limits: ArtifactWriteLimits,
): ByteArray =
    encodeIndexed(
        capabilities.map { capability ->
            BinarySink(limits.artifactBytes)
                .apply {
                    writeU32(capability.namespace.value)
                    writeU32(capability.name.value)
                    writeU16(capability.abi.major.toUInt())
                    writeU16(capability.abi.minor.toUInt())
                    writeU32(if (capability.required) 1u else 2u)
                    writeU32(capability.operationCount)
                    writeU32(0u)
                }.toByteArray()
        },
        limits.artifactBytes,
    )

private fun featureMask(features: Set<SemanticFeature>): UInt = features.fold(0u) { mask, feature -> mask or (1u shl feature.ordinal) }
