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

import io.airlift.compress.v3.zstd.ZstdInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

data class PackagedToolingBundleLimits(
    val entries: Int = 256,
    val bytes: Long = 512L * 1024 * 1024,
    val manifestBytes: Int = 1024 * 1024,
) {
    init {
        require(entries > 0) { "packaged tooling entry limit must be positive" }
        require(bytes > 0) { "packaged tooling byte limit must be positive" }
        require(manifestBytes > 0) { "packaged tooling manifest byte limit must be positive" }
    }
}

data class PublishedToolingProfile(
    val root: Path,
    val manifest: ToolingProfileManifest,
    val classpath: List<Path>,
)

class PublishedToolingBundle internal constructor(
    val root: Path,
    val manifest: ToolingBundleManifest,
) {
    fun profile(kind: String): PublishedToolingProfile {
        val profile = manifest.profiles[kind] ?: throw ToolingBundleException("tooling profile is unavailable: $kind")
        val classpath =
            profile.classpath.map { relative ->
                root.resolve(relative).normalize().also { path ->
                    if (!path.startsWith(root)) throw ToolingBundleException("tooling profile classpath escapes its root")
                }
            }
        return PublishedToolingProfile(root, profile, Collections.unmodifiableList(classpath))
    }
}

class PackagedToolingBundleException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object PackagedToolingBundle {
    fun publish(
        archive: InputStream,
        cacheRoot: Path,
        limits: PackagedToolingBundleLimits = PackagedToolingBundleLimits(),
    ): PublishedToolingBundle {
        require(cacheRoot.isAbsolute && cacheRoot.normalize() == cacheRoot) {
            "packaged tooling cache root must be absolute and normalized"
        }
        if (Files.isSymbolicLink(cacheRoot)) throw PackagedToolingBundleException("packaged tooling cache root is symbolic")
        cacheRoot.createDirectories()
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw PackagedToolingBundleException("packaged tooling cache root is not a regular directory")
        }
        val staging = cacheRoot.resolve(".packaged-${UUID.randomUUID()}")
        staging.createDirectories()
        try {
            ZstdInputStream(archive).use { decoded -> extract(decoded, staging, limits) }
            val manifest = loadManifest(staging, limits.manifestBytes)
            validateExtracted(staging, manifest)
            val destination = cacheRoot.resolve(manifest.bundleHash.hex())
            if (destination.exists()) {
                deleteTree(staging)
                return validatePublished(destination, manifest, limits.manifestBytes)
            }
            forceDirectory(staging)
            movePublished(staging, destination)
            return validatePublished(destination, manifest, limits.manifestBytes)
        } catch (exception: PackagedToolingBundleException) {
            throw exception
        } catch (exception: ToolingBundleException) {
            throw PackagedToolingBundleException(exception.message ?: "packaged tooling manifest is invalid", exception)
        } catch (exception: Exception) {
            throw PackagedToolingBundleException("packaged tooling publication failed", exception)
        } finally {
            deleteTree(staging)
        }
    }

    private fun extract(
        archive: InputStream,
        staging: Path,
        limits: PackagedToolingBundleLimits,
    ) {
        val names = mutableSetOf<String>()
        var entries = 0
        var bytes = 0L
        ZipInputStream(archive).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries = Math.incrementExact(entries)
                if (entries > limits.entries) throw PackagedToolingBundleException("packaged tooling entry count exceeds limit")
                val name = validateEntryName(entry.name, entry.isDirectory)
                if (!names.add(name)) throw PackagedToolingBundleException("duplicate packaged tooling entry: $name")
                val target = staging.resolve(name).normalize()
                if (!target.startsWith(staging)) throw PackagedToolingBundleException("packaged tooling entry escapes staging")
                val publish = !entry.isDirectory && !name.startsWith("META-INF/")
                if (entry.isDirectory) {
                    if (!name.startsWith("META-INF/")) target.createDirectories()
                    drain(zip) { count -> bytes = checkedBytes(bytes, count, limits) }
                } else if (publish) {
                    target.parent.createDirectories()
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes = checkedBytes(bytes, count, limits)
                            output.write(buffer, 0, count)
                        }
                    }
                    forceFile(target)
                } else {
                    drain(zip) { count -> bytes = checkedBytes(bytes, count, limits) }
                }
                zip.closeEntry()
            }
        }
        if (entries == 0) throw PackagedToolingBundleException("packaged tooling archive is empty")
    }

    private fun checkedBytes(
        current: Long,
        count: Int,
        limits: PackagedToolingBundleLimits,
    ): Long =
        Math.addExact(current, count.toLong()).also { total ->
            if (total > limits.bytes) throw PackagedToolingBundleException("packaged tooling bytes exceed limit")
        }

    private inline fun drain(
        input: InputStream,
        consumed: (Int) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            consumed(count)
        }
    }

    private fun loadManifest(
        root: Path,
        maximumBytes: Int,
    ): ToolingBundleManifest {
        val documents =
            mapOf(
                TOOLING_BUNDLE_MANIFEST_FILE to readRegularFile(root.resolve(TOOLING_BUNDLE_MANIFEST_FILE), maximumBytes),
                "manifests/analysis.payload" to readRegularFile(root.resolve("manifests/analysis.payload"), maximumBytes),
                "manifests/compiler.payload" to readRegularFile(root.resolve("manifests/compiler.payload"), maximumBytes),
            )
        return ToolingBundleManifestCodec.decode(documents)
    }

    private fun validateExtracted(
        root: Path,
        manifest: ToolingBundleManifest,
    ) {
        val expected = manifest.files.mapTo(mutableSetOf(), ToolingBundleFile::path)
        expected += TOOLING_BUNDLE_MANIFEST_FILE
        expected += "manifests/analysis.payload"
        expected += "manifests/compiler.payload"
        val actual =
            Files.walk(root).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                    .map { path -> root.relativize(path).joinToString("/") }
                    .toList()
                    .toSet()
            }
        if (actual != expected) throw PackagedToolingBundleException("packaged tooling entries do not match the manifest")
        manifest.files.forEach { file -> validateFile(root, file) }
    }

    private fun validatePublished(
        root: Path,
        expected: ToolingBundleManifest,
        maximumManifestBytes: Int,
    ): PublishedToolingBundle {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw PackagedToolingBundleException("published tooling root is invalid")
        }
        val loaded = loadManifest(root, maximumManifestBytes)
        if (loaded != expected) throw PackagedToolingBundleException("published tooling manifest does not match the package")
        validateExtracted(root, loaded)
        return PublishedToolingBundle(root, loaded)
    }

    private fun validateFile(
        root: Path,
        file: ToolingBundleFile,
    ) {
        val path = root.resolve(file.path).normalize()
        if (!path.startsWith(root)) throw PackagedToolingBundleException("tooling file escapes its root")
        requireNoSymbolicLinks(root, path)
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || path.fileSize() != file.bytes) {
            throw PackagedToolingBundleException("tooling file is missing or has an invalid size: ${file.path}")
        }
        if (Files.newInputStream(path).use(::sha256) != file.sha256) {
            throw PackagedToolingBundleException("tooling file hash mismatch: ${file.path}")
        }
    }

    private fun requireNoSymbolicLinks(
        root: Path,
        path: Path,
    ) {
        var current = root
        root.relativize(path).forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) throw PackagedToolingBundleException("published tooling contains a symbolic link")
        }
    }

    private fun readRegularFile(
        path: Path,
        maximumBytes: Int,
    ): ByteArray {
        if (Files.isSymbolicLink(path) || !path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            throw PackagedToolingBundleException("packaged tooling manifest is missing")
        }
        if (path.fileSize() > maximumBytes) throw PackagedToolingBundleException("packaged tooling manifest exceeds its byte limit")
        return Files.readAllBytes(path)
    }

    private fun validateEntryName(
        raw: String,
        directory: Boolean,
    ): String {
        if (raw.isEmpty() || raw.startsWith('/') || '\\' in raw || '\u0000' in raw) {
            throw PackagedToolingBundleException("packaged tooling entry path is invalid")
        }
        val name = if (directory) raw.removeSuffix("/") else raw
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw PackagedToolingBundleException("packaged tooling entry path is invalid")
        }
        val valid =
            if (directory) {
                name in FIXED_DIRECTORIES ||
                    name.startsWith("META-INF/licenses/")
            } else {
                name == TOOLING_BUNDLE_MANIFEST_FILE ||
                    name in PROFILE_MANIFESTS ||
                    isLibraryPath(name) ||
                    name in NOTICE_FILES ||
                    name.startsWith("META-INF/licenses/")
            }
        if (!valid) throw PackagedToolingBundleException("unexpected packaged tooling entry")
        return name
    }

    private fun isLibraryPath(name: String): Boolean {
        val parts = name.split('/')
        return parts.size == 3 && parts[0] in setOf("common", "compiler", "analysis") && parts[1] == "lib" && parts[2].endsWith(".jar")
    }

    private val PROFILE_MANIFESTS = setOf("manifests/analysis.payload", "manifests/compiler.payload")
    private val NOTICE_FILES = setOf("META-INF/NOTICE.txt", "META-INF/THIRD-PARTY-NOTICES.md")
    private val FIXED_DIRECTORIES =
        setOf(
            "common",
            "common/lib",
            "compiler",
            "compiler/lib",
            "analysis",
            "analysis/lib",
            "manifests",
            "META-INF",
            "META-INF/licenses",
        )
}
