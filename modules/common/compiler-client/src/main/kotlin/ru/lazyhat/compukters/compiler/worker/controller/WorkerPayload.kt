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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.worker.payload.ToolingBundleFile
import ru.lazyhat.compukters.worker.payload.ToolingProfileManifest
import java.nio.file.Path
import ru.lazyhat.compukters.worker.payload.WorkerPayloadManifest as GenericWorkerPayloadManifest

data class WorkerPayloadFile(
    val path: String,
    val bytes: Long,
    val sha256: Hash256,
)

class WorkerPayloadManifest private constructor(
    val identity: WorkerIdentity,
    val mainClass: String,
    val files: List<WorkerPayloadFile>,
    val payloadHash: Hash256,
    internal val generic: GenericWorkerPayloadManifest?,
) {
    override fun equals(other: Any?): Boolean =
        other is WorkerPayloadManifest &&
            identity == other.identity &&
            mainClass == other.mainClass &&
            files == other.files &&
            payloadHash == other.payloadHash

    override fun hashCode(): Int = listOf(identity, mainClass, files, payloadHash).hashCode()

    companion object {
        fun create(
            identity: WorkerIdentity,
            mainClass: String,
            files: Map<String, ByteArray>,
        ): WorkerPayloadManifest = fromGeneric(generic(identity, mainClass, files))

        internal fun fromGeneric(manifest: GenericWorkerPayloadManifest): WorkerPayloadManifest {
            require(manifest.kind == COMPILER_KIND) { "worker payload kind must be compiler" }
            val payloadHash = Hash256.of(manifest.payloadHash.toByteArray())
            return WorkerPayloadManifest(
                identity(manifest.identityProperties, payloadHash),
                manifest.mainClass,
                manifest.files.map { file -> WorkerPayloadFile(file.path, file.bytes, Hash256.of(file.sha256.toByteArray())) },
                payloadHash,
                manifest,
            )
        }

        fun fromToolingProfile(
            profile: ToolingProfileManifest,
            bundleFiles: List<ToolingBundleFile>,
        ): WorkerPayloadManifest {
            require(profile.kind == COMPILER_KIND) { "tooling profile kind must be compiler" }
            val records = bundleFiles.associateBy(ToolingBundleFile::path)
            require(profile.classpath.toSet() == records.keys.intersect(profile.classpath.toSet())) {
                "compiler tooling profile references an unavailable file"
            }
            val payloadHash = Hash256.of(profile.payloadHash.toByteArray())
            return WorkerPayloadManifest(
                identity(profile.identityProperties, payloadHash),
                profile.mainClass,
                profile.classpath.map { path ->
                    val file = records.getValue(path)
                    WorkerPayloadFile(file.path, file.bytes, Hash256.of(file.sha256.toByteArray()))
                },
                payloadHash,
                null,
            )
        }

        private fun identity(
            properties: Map<String, String>,
            payloadHash: Hash256,
        ): WorkerIdentity {
            require(properties.keys == IDENTITY_KEYS) { "compiler payload identity properties are invalid" }
            return WorkerIdentity(
                compilerVersion = properties.getValue(COMPILER),
                languageVersion = properties.getValue(LANGUAGE),
                codegenAbi = properties.getValue(CODEGEN_ABI).toUInt(),
                artifactWriterVersion = properties.getValue(ARTIFACT_WRITER).toUInt(),
                payloadHash = payloadHash,
                platformAbi = Hash256.fromHex(properties.getValue(PLATFORM_ABI)),
            )
        }

        private fun generic(
            identity: WorkerIdentity,
            mainClass: String,
            files: Map<String, ByteArray>,
        ): GenericWorkerPayloadManifest =
            GenericWorkerPayloadManifest.create(
                kind = COMPILER_KIND,
                identityProperties =
                    mapOf(
                        COMPILER to identity.compilerVersion,
                        LANGUAGE to identity.languageVersion,
                        CODEGEN_ABI to identity.codegenAbi.toString(),
                        ARTIFACT_WRITER to identity.artifactWriterVersion.toString(),
                        PLATFORM_ABI to identity.platformAbi.hex(),
                    ),
                mainClass = mainClass,
                files = files,
            )

        private const val COMPILER_KIND = "compiler"
        private const val COMPILER = "compiler"
        private const val LANGUAGE = "language"
        private const val CODEGEN_ABI = "codegenAbi"
        private const val ARTIFACT_WRITER = "artifactWriter"
        private const val PLATFORM_ABI = "platformAbi"
        private val IDENTITY_KEYS = setOf(COMPILER, LANGUAGE, CODEGEN_ABI, ARTIFACT_WRITER, PLATFORM_ABI)
    }
}

typealias WorkerPayloadSource = ru.lazyhat.compukters.worker.payload.WorkerPayloadSource
typealias WorkerPayloadLimits = ru.lazyhat.compukters.worker.payload.WorkerPayloadLimits
typealias WorkerPayloadException = ru.lazyhat.compukters.worker.payload.WorkerPayloadException

data class PublishedWorkerPayload(
    val root: Path,
    val manifest: WorkerPayloadManifest,
    val classpath: List<Path>,
)

object WorkerPayloadPublisher {
    fun publish(
        manifest: WorkerPayloadManifest,
        source: WorkerPayloadSource,
        cacheRoot: Path,
        limits: WorkerPayloadLimits = WorkerPayloadLimits(),
    ): PublishedWorkerPayload {
        val published =
            ru.lazyhat.compukters.worker.payload.WorkerPayloadPublisher
                .publish(
                    requireNotNull(manifest.generic) { "shared tooling profiles cannot be republished as legacy payloads" },
                    source,
                    cacheRoot,
                    limits,
                )
        return PublishedWorkerPayload(published.root, WorkerPayloadManifest.fromGeneric(published.manifest), published.classpath)
    }
}
