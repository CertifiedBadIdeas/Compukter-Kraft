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

package ru.lazyhat.compukters.worker.payload

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class PackagedWorkerPayloadLimits(
    val entries: Int = 256,
    val bytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(entries > 0) { "packaged worker entry limit must be positive" }
        require(bytes > 0) { "packaged worker byte limit must be positive" }
    }
}

data class WorkerPayloadExpectation(
    val kind: String,
    val identityProperties: Map<String, String>,
) {
    init {
        require(kind.isNotBlank()) { "expected worker kind must not be blank" }
        require(identityProperties.isNotEmpty()) { "expected worker identity must not be empty" }
    }
}

class PackagedWorkerPayloadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object PackagedWorkerPayload {
    fun publish(
        archive: InputStream,
        cacheRoot: Path,
        expectation: WorkerPayloadExpectation? = null,
        limits: PackagedWorkerPayloadLimits = PackagedWorkerPayloadLimits(),
    ): PublishedWorkerPayload {
        require(cacheRoot.isAbsolute && cacheRoot.normalize() == cacheRoot) {
            "packaged worker cache root must be absolute and normalized"
        }
        if (Files.isSymbolicLink(cacheRoot)) throw PackagedWorkerPayloadException("packaged worker cache root is symbolic")
        cacheRoot.createDirectories()
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw PackagedWorkerPayloadException("packaged worker cache root is not a regular directory")
        }
        val staging = cacheRoot.resolve(".packaged-${UUID.randomUUID()}")
        staging.createDirectories()
        try {
            extract(archive, staging, limits)
            val unpacked = WorkerPayloadLoader.load(staging)
            verifyExpectation(unpacked.manifest, expectation)
            return WorkerPayloadPublisher.publish(
                unpacked.manifest,
                { path -> Files.newInputStream(staging.resolve(path), StandardOpenOption.READ) },
                cacheRoot,
            )
        } catch (exception: PackagedWorkerPayloadException) {
            throw exception
        } catch (exception: WorkerPayloadException) {
            throw exception
        } catch (exception: Exception) {
            throw PackagedWorkerPayloadException("packaged worker publication failed", exception)
        } finally {
            deleteTree(staging)
        }
    }

    private fun verifyExpectation(
        manifest: WorkerPayloadManifest,
        expectation: WorkerPayloadExpectation?,
    ) {
        if (expectation == null) return
        if (manifest.kind != expectation.kind) {
            throw PackagedWorkerPayloadException("packaged worker kind mismatch")
        }
        if (manifest.identityProperties != expectation.identityProperties) {
            throw PackagedWorkerPayloadException("packaged worker identity mismatch")
        }
    }

    private fun extract(
        archive: InputStream,
        staging: Path,
        limits: PackagedWorkerPayloadLimits,
    ) {
        val names = mutableSetOf<String>()
        var entries = 0
        var bytes = 0L
        ZipInputStream(archive).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries = Math.incrementExact(entries)
                if (entries > limits.entries) throw PackagedWorkerPayloadException("packaged worker entry count exceeds limit")
                val name = validateEntryName(entry.name, entry.isDirectory)
                if (!names.add(name)) throw PackagedWorkerPayloadException("duplicate packaged worker entry: $name")
                val target = staging.resolve(name).normalize()
                if (!target.startsWith(staging)) throw PackagedWorkerPayloadException("packaged worker entry escapes staging")
                if (entry.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent.createDirectories()
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes = Math.addExact(bytes, count.toLong())
                            if (bytes > limits.bytes) throw PackagedWorkerPayloadException("packaged worker bytes exceed limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entries == 0) throw PackagedWorkerPayloadException("packaged worker archive is empty")
    }

    private fun validateEntryName(
        raw: String,
        directory: Boolean,
    ): String {
        if (raw.isEmpty() || raw.startsWith('/') || '\\' in raw || '\u0000' in raw) {
            throw PackagedWorkerPayloadException("packaged worker entry path is invalid")
        }
        val name = if (directory) raw.removeSuffix("/") else raw
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw PackagedWorkerPayloadException("packaged worker entry path is invalid")
        }
        if (directory) {
            val expected =
                name == "lib" ||
                    name == "META-INF" ||
                    name == "META-INF/licenses" ||
                    name.startsWith("META-INF/licenses/")
            if (!expected) throw PackagedWorkerPayloadException("unexpected packaged worker directory")
        } else if (
            name != MANIFEST_FILE &&
            !(name.startsWith("lib/") && name.endsWith(".jar")) &&
            name !in LICENSE_METADATA_FILES &&
            !name.startsWith("META-INF/licenses/")
        ) {
            throw PackagedWorkerPayloadException("unexpected packaged worker file")
        }
        return name
    }

    private const val MANIFEST_FILE = "worker.payload"
    private val LICENSE_METADATA_FILES = setOf("META-INF/NOTICE.txt", "META-INF/THIRD-PARTY-NOTICES.md")
}
