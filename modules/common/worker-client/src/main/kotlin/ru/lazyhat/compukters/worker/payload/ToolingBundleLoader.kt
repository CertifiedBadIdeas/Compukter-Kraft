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

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

data class ToolingBundleLoadLimits(
    val manifestBytes: Int = 1024 * 1024,
    val files: Int = 256,
    val payloadBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(manifestBytes > 0) { "tooling manifest byte limit must be positive" }
        require(files > 0) { "tooling file limit must be positive" }
        require(payloadBytes > 0) { "tooling payload byte limit must be positive" }
    }
}

object ToolingBundleLoader {
    fun load(
        root: Path,
        limits: ToolingBundleLoadLimits = ToolingBundleLoadLimits(),
    ): PublishedToolingBundle =
        try {
            loadChecked(root.toAbsolutePath().normalize(), limits)
        } catch (exception: ToolingBundleException) {
            throw exception
        } catch (exception: Exception) {
            throw ToolingBundleException("published tooling bundle is invalid", exception)
        }

    private fun loadChecked(
        root: Path,
        limits: ToolingBundleLoadLimits,
    ): PublishedToolingBundle {
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "tooling root is not a regular directory"
        }
        val documents =
            mapOf(
                TOOLING_BUNDLE_MANIFEST_FILE to readManifest(root.resolve(TOOLING_BUNDLE_MANIFEST_FILE), limits.manifestBytes),
                "manifests/analysis.payload" to readManifest(root.resolve("manifests/analysis.payload"), limits.manifestBytes),
                "manifests/compiler.payload" to readManifest(root.resolve("manifests/compiler.payload"), limits.manifestBytes),
            )
        val manifest = ToolingBundleManifestCodec.decode(documents)
        require(manifest.files.size <= limits.files) { "tooling file count exceeds limit" }
        require(manifest.files.fold(0L) { total, file -> Math.addExact(total, file.bytes) } <= limits.payloadBytes) {
            "tooling payload bytes exceed limit"
        }
        manifest.files.forEach { file -> validateFile(root, file) }
        return PublishedToolingBundle(root, manifest)
    }

    private fun readManifest(
        path: Path,
        maximumBytes: Int,
    ): ByteArray {
        require(!Files.isSymbolicLink(path) && path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "tooling manifest is missing" }
        require(path.fileSize() <= maximumBytes) { "tooling manifest exceeds limit" }
        return Files.readAllBytes(path)
    }

    private fun validateFile(
        root: Path,
        file: ToolingBundleFile,
    ) {
        val path = root.resolve(file.path).normalize()
        require(path.startsWith(root)) { "tooling file escapes its root" }
        var current = root
        root.relativize(path).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "tooling file path contains a symbolic link" }
        }
        require(path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && path.fileSize() == file.bytes) {
            "tooling file is missing or has an invalid size: ${file.path}"
        }
        require(Files.newInputStream(path).use(::sha256) == file.sha256) { "tooling file hash mismatch: ${file.path}" }
    }
}
